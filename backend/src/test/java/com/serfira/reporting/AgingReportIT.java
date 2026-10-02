package com.serfira.reporting;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.ContractResponse;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.application.ContractCommandService;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * T8 — the aging report end to end over HTTP against PostgreSQL (PRD D-2, ADR-013 A-4/A-6/A-7): DPD-boundary
 * bucketing, the Σ bucket = outstanding invariant at both levels, penalty included in outstanding, the
 * today-only {@code as_of} rule, paging, and the role matrix.
 *
 * <p>The clock is fixed to {@link #TODAY}. Contracts are created and activated through the real command
 * service, then each installment's {@code due_date} and amounts are seeded with test-only SQL so its DPD on
 * {@link #TODAY} lands exactly on a bucket boundary. With the seeded grace of 3 days,
 * {@code DPD = TODAY − due_date − 3}, so a target DPD {@code d >= 1} needs {@code due_date = TODAY − 3 − d}.
 */
@Import({TestcontainersConfiguration.class, AgingReportIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class AgingReportIT {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
	private static final int GRACE_DAYS = 3;
	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ContractCommandService commands;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	private UUID actorId;
	private String token;

	@BeforeEach
	void resetAndSeedActor() {
		truncateDomainTables();
		((FixedClock) clock).setDate(TODAY);
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Aging IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "aging-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void bucketsEachInstallmentByItsDaysPastDueAndSumsToOutstanding() throws Exception {
		UUID contractId = activateContract("B1234XY", "3171012501900001", "08123456789");
		// One owed installment on each boundary: DPD 0 (not yet due), 1, 30, 31, 60, 61, 90, 91.
		// principal residual is the whole outstanding here (no interest recognized, no payment).
		setInstallment(contractId, 1, dueForDpd(0), "100.00");   // Current
		setInstallment(contractId, 2, dueForDpd(1), "200.00");   // 1-30
		setInstallment(contractId, 3, dueForDpd(30), "300.00");  // 1-30
		setInstallment(contractId, 4, dueForDpd(31), "400.00");  // 31-60
		setInstallment(contractId, 5, dueForDpd(60), "500.00");  // 31-60
		setInstallment(contractId, 6, dueForDpd(61), "600.00");  // 61-90
		setInstallment(contractId, 7, dueForDpd(90), "700.00");  // 61-90
		setInstallment(contractId, 8, dueForDpd(91), "800.00");  // >90
		// Periods 9-12 carry no outstanding, so they never appear.
		zeroOutInstallment(contractId, 9);
		zeroOutInstallment(contractId, 10);
		zeroOutInstallment(contractId, 11);
		zeroOutInstallment(contractId, 12);

		JsonNode report = data(getReport("/api/v1/reports/aging"));

		assertThat(report.get("as_of").asText()).isEqualTo("2026-10-01");
		JsonNode portfolio = report.get("portfolio");
		assertThat(decimal(portfolio, "current")).isEqualByComparingTo("100.00");
		assertThat(decimal(portfolio, "dpd_1_30")).isEqualByComparingTo("500.00");   // 200 + 300
		assertThat(decimal(portfolio, "dpd_31_60")).isEqualByComparingTo("900.00");  // 400 + 500
		assertThat(decimal(portfolio, "dpd_61_90")).isEqualByComparingTo("1300.00"); // 600 + 700
		assertThat(decimal(portfolio, "dpd_over_90")).isEqualByComparingTo("800.00");
		assertThat(decimal(portfolio, "total_outstanding")).isEqualByComparingTo("3600.00");

		// Σ bucket = outstanding at the portfolio level.
		assertThat(sumBuckets(portfolio)).isEqualByComparingTo(decimal(portfolio, "total_outstanding"));

		assertThat(report.get("contracts").get("total_elements").asLong()).isEqualTo(1L);
		JsonNode row = report.get("contracts").get("content").get(0);
		assertThat(row.get("contract_id").asText()).isEqualTo(contractId.toString());
		assertThat(decimal(row.get("buckets"), "total_outstanding")).isEqualByComparingTo("3600.00");
		assertThat(sumBuckets(row.get("buckets")))
				.isEqualByComparingTo(decimal(row.get("buckets"), "total_outstanding"));
	}

	@Test
	void penaltyAndRecognizedInterestAreIncludedInOutstanding() throws Exception {
		UUID contractId = activateContract("B2222BB", "3171012501900002", "08123456780");
		// Period 1: principal 1000.00 + recognized interest 100.00 + penalty 50.00, nothing resolved.
		jdbc.update("""
				update installment
					set due_date = ?, principal_amount = 1000.00, interest_amount = 100.00,
						recognized_interest_amount = 100.00, penalty_amount = 50.00,
						paid_amount = 0, settled_amount = 0, written_off_amount = 0, status = 'OVERDUE'
					where contract_id = ? and period_no = 1
				""", dueForDpd(10), contractId);
		for (int period = 2; period <= 12; period++) {
			zeroOutInstallment(contractId, period);
		}

		JsonNode portfolio = data(getReport("/api/v1/reports/aging")).get("portfolio");

		// Outstanding = 1000 + 100 + 50 = 1150, all in the 1-30 bucket (DPD 10).
		assertThat(decimal(portfolio, "dpd_1_30")).isEqualByComparingTo("1150.00");
		assertThat(decimal(portfolio, "total_outstanding")).isEqualByComparingTo("1150.00");
	}

	@Test
	void onlyActiveContractsAppear() throws Exception {
		UUID active = activateContract("B3333CC", "3171012501900003", "08123456781");
		setInstallment(active, 1, dueForDpd(10), "500.00");
		for (int period = 2; period <= 12; period++) {
			zeroOutInstallment(active, period);
		}

		// A DRAFT contract (never activated) must not contribute.
		UUID draft = commands.create("aging-draft-" + UUID.randomUUID(), contractRequest(
				"B4444DD", "3171012501900004", "08123456782")).id();

		// A CLOSED contract with a nonzero installment must not contribute either.
		UUID closed = activateContract("B5555EE", "3171012501900005", "08123456783");
		setInstallment(closed, 1, dueForDpd(10), "999.00");
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'SETTLEMENT' where id = ?", closed);

		JsonNode report = data(getReport("/api/v1/reports/aging"));

		assertThat(report.get("contracts").get("total_elements").asLong()).isEqualTo(1L);
		List<String> rowContractIds = new ArrayList<>();
		report.get("contracts").get("content").forEach(row -> rowContractIds.add(row.get("contract_id").asText()));
		assertThat(rowContractIds)
				.containsExactly(active.toString())
				.doesNotContain(draft.toString(), closed.toString());
		assertThat(decimal(report.get("portfolio"), "total_outstanding")).isEqualByComparingTo("500.00");
	}

	@Test
	void pagesPerContractRowsWhilePortfolioSpansEveryContract() throws Exception {
		for (int i = 1; i <= 3; i++) {
			UUID contractId = activateContract("B10" + i + "AA", "317101250190000" + i, "0812345678" + i);
			setInstallment(contractId, 1, dueForDpd(10), "100.00");
			for (int period = 2; period <= 12; period++) {
				zeroOutInstallment(contractId, period);
			}
		}

		JsonNode firstPage = data(getReport("/api/v1/reports/aging?page=0&size=2"));
		assertThat(firstPage.get("contracts").get("content").size()).isEqualTo(2);
		assertThat(firstPage.get("contracts").get("total_elements").asLong()).isEqualTo(3L);
		assertThat(firstPage.get("contracts").get("total_pages").asInt()).isEqualTo(2);
		assertThat(decimal(firstPage.get("portfolio"), "total_outstanding")).isEqualByComparingTo("300.00");

		JsonNode secondPage = data(getReport("/api/v1/reports/aging?page=1&size=2"));
		assertThat(secondPage.get("contracts").get("content").size()).isEqualTo(1);
		assertThat(decimal(secondPage.get("portfolio"), "total_outstanding")).isEqualByComparingTo("300.00");
	}

	@Test
	void aPastOrFutureAsOfIsRejectedButTodayIsAccepted() throws Exception {
		MvcResult past = getReport("/api/v1/reports/aging?as_of=2026-09-30");
		assertThat(past.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(past)).isEqualTo("INVALID_AS_OF_DATE");

		MvcResult future = getReport("/api/v1/reports/aging?as_of=2026-10-02");
		assertThat(future.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(future)).isEqualTo("INVALID_AS_OF_DATE");

		MvcResult today = getReport("/api/v1/reports/aging?as_of=2026-10-01");
		assertThat(today.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void outOfRangePagingIsAValidationError() throws Exception {
		assertThat(getReport("/api/v1/reports/aging?size=0").getResponse().getStatus()).isEqualTo(400);
		assertThat(getReport("/api/v1/reports/aging?size=101").getResponse().getStatus()).isEqualTo(400);
		assertThat(getReport("/api/v1/reports/aging?page=-1").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void allThreeReadRolesAreAllowedAndNoTokenIsUnauthorized() throws Exception {
		for (AppRole role : new AppRole[]{AppRole.ADMIN_OPERASIONAL, AppRole.FINANCE, AppRole.MANAJEMEN}) {
			MvcResult result = mockMvc.perform(get("/api/v1/reports/aging")
					.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role.name()))).andReturn();
			assertThat(result.getResponse().getStatus())
					.as("role %s", role)
					.isEqualTo(200);
		}

		MvcResult noToken = mockMvc.perform(get("/api/v1/reports/aging")).andReturn();
		assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorCode(noToken)).isEqualTo("UNAUTHORIZED");
	}

	// ---------------------------------------------------------------------------------------------

	/** due_date so an installment's DPD on TODAY equals {@code dpd} (0 means not yet past the grace window). */
	private static LocalDate dueForDpd(int dpd) {
		if (dpd == 0) {
			// Due today: within grace, so DPD = 0 and the installment is Current.
			return TODAY;
		}
		return TODAY.minusDays(GRACE_DAYS + (long) dpd);
	}

	private UUID activateContract(String plateNo, String nik, String phone) {
		ContractResponse draft = commands.create("aging-key-" + UUID.randomUUID(),
				contractRequest(plateNo, nik, phone));
		commands.activate(draft.id(), null);
		return draft.id();
	}

	private static CreateContractRequest contractRequest(String plateNo, String nik, String phone) {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", nik, phone, "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, plateNo),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31));
	}

	/** Test-only SQL: place one installment at a due date with a known principal-only outstanding. */
	private void setInstallment(UUID contractId, int periodNo, LocalDate dueDate, String principal) {
		jdbc.update("""
				update installment
					set due_date = ?, principal_amount = ?, interest_amount = 0, recognized_interest_amount = 0,
						penalty_amount = 0, paid_amount = 0, settled_amount = 0, written_off_amount = 0,
						status = 'OVERDUE'
					where contract_id = ? and period_no = ?
				""", dueDate, new BigDecimal(principal), contractId, periodNo);
	}

	/** Test-only SQL: fully resolve an installment so it has zero outstanding and never appears. */
	private void zeroOutInstallment(UUID contractId, int periodNo) {
		jdbc.update("""
				update installment
					set interest_amount = 0, recognized_interest_amount = 0, penalty_amount = 0,
						paid_amount = principal_amount, settled_amount = 0, written_off_amount = 0,
						status = 'PAID', paid_at = clock_timestamp()
					where contract_id = ? and period_no = ?
				""", contractId, periodNo);
	}

	private MvcResult getReport(String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus())
				.as("GET returned %s: %s", result.getResponse().getStatus(), result.getResponse().getContentAsString())
				.isEqualTo(200);
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return node.get(field).decimalValue();
	}

	private static BigDecimal sumBuckets(JsonNode buckets) {
		return decimal(buckets, "current")
				.add(decimal(buckets, "dpd_1_30"))
				.add(decimal(buckets, "dpd_31_60"))
				.add(decimal(buckets, "dpd_61_90"))
				.add(decimal(buckets, "dpd_over_90"));
	}

	private void truncateDomainTables() {
		jdbc.execute("""
				TRUNCATE TABLE
					document_number_counter, refresh_token, idempotency_keys, job_run,
					reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(TODAY);
		}
	}
}
