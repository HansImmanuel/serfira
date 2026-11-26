package com.serfira.reporting;

import com.serfira.TestcontainersConfiguration;
import com.serfira.penalty.application.LockedDailyServicingJob;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * T36 — {@code GET /api/v1/reports/outstanding} end to end against PostgreSQL (PRD R-1, ADR-026): the totals
 * reconcile with the aging report, the collection rate counts only POSTED cash on the month's due-date cohort,
 * settlement is its own metric, the role matrix, and the today-only {@code as_of}.
 *
 * <p>Contracts are the Addendum §18 motorcycle fixture (FLAT, principal 16,000,000, 12 × 1,573,333.33, 1.5%,
 * 3-day grace) created and activated over HTTP. Money moves through the real API — payment, void, credit apply,
 * settlement, penalty reduce — with the clock walked between due dates; only the reconciliation and cohort-edge
 * tests place due dates and amounts with test-only SQL, as {@code AgingReportIT} does.
 */
@Import({TestcontainersConfiguration.class, OutstandingReportIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class OutstandingReportIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** Period 1 of a contract starting 2026-01-31 is due on this date (February clamps to the 28th). */
	private static final LocalDate FIRST_DUE = LocalDate.of(2026, 2, 28);
	/** Period 2's due date, and the report date: the March cohort is period 2 of every fixture contract. */
	private static final LocalDate TODAY = LocalDate.of(2026, 3, 31);
	private static final LocalDate START = LocalDate.of(2026, 1, 31);

	private static final String PERIOD_TOTAL = "1573333.33";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	@Autowired
	LockedDailyServicingJob job;

	private UUID actorId;
	private String token;
	private int fixtureNo;

	@BeforeEach
	void resetAndSeedActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Outstanding IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "outstanding-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
		fixedClock().setDate(FIRST_DUE);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	@Test
	void collectionCountsPostedCashOnTheCohortAndSettlementIsSeparate() throws Exception {
		// 2026-02-28: four contracts, each pays period 1 on time (outside the March cohort). C overpays by
		// 2,000,000, which becomes credit.
		UUID a = activateContract(START);
		UUID b = activateContract(START);
		UUID c = activateContract(START);
		UUID d = activateContract(START);
		assertCreated(pay(a, "a-p1", PERIOD_TOTAL));
		assertCreated(pay(b, "b-p1", PERIOD_TOTAL));
		assertCreated(pay(c, "c-p1", "3573333.33"));
		assertCreated(pay(d, "d-p1", PERIOD_TOTAL));

		// 2026-03-31: period 2 falls due and the nightly job bills it.
		fixedClock().setDate(TODAY);
		job.run(TODAY);

		// A pays period 2 in full: counted.
		assertCreated(pay(a, "a-p2", PERIOD_TOTAL));
		// B pays in full, the payment is voided (excluded), then B pays 500,000 (counted).
		UUID voided = UUID.fromString(data(pay(b, "b-p2-voided", PERIOD_TOTAL)).get("id").asText());
		MvcResult voidResult = mockMvc.perform(post("/api/v1/payments/" + voided + "/void")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "b-void")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"wrong contract\"}")).andReturn();
		assertThat(voidResult.getResponse().getStatus()).as(body(voidResult)).isBetween(200, 299);
		assertCreated(pay(b, "b-p2-partial", "500000.00"));
		// C resolves period 2 with its credit: non-cash, so excluded from collected but kept in due.
		MvcResult applied = mockMvc.perform(post("/api/v1/contracts/" + c + "/credit/apply")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "c-credit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andReturn();
		assertThat(applied.getResponse().getStatus()).as(body(applied)).isBetween(200, 299);
		assertThat(installmentStatus(c, 2)).isEqualTo("PAID");
		// D settles early: period 2 leaves both sides, and the cash is its own metric.
		BigDecimal settlementCash = settle(d);

		JsonNode report = data(getReport("/api/v1/reports/outstanding"));

		JsonNode collection = report.get("collection");
		assertThat(collection.get("period_from").asText()).isEqualTo("2026-03-01");
		assertThat(collection.get("period_to").asText()).isEqualTo("2026-03-31");
		assertThat(decimal(collection, "collected")).isEqualByComparingTo("2073333.33"); // A + B's 500,000
		assertThat(decimal(collection, "due")).isEqualByComparingTo("4719999.99");       // A + B + C period 2
		assertThat(collection.get("rate").asText()).isEqualTo("0.4393");
		assertThat(installmentSettled(d, 2)).isPositive();

		JsonNode cash = report.get("settlement_cash");
		assertThat(decimal(cash, "amount")).isEqualByComparingTo(settlementCash).isPositive();
		assertThat(cash.get("period_from").asText()).isEqualTo("2026-03-01");

		// Stock metrics: A, B and C stay ACTIVE (D closed). Only B's unpaid 1,073,333.33 is already due.
		assertThat(decimal(report, "accounts_receivable")).isEqualByComparingTo("1073333.33");
		assertThat(report.get("active_contract_count").asLong()).isEqualTo(3L);
		assertThat(report.get("npl").get("contract_count").asLong()).isZero();
		assertThat(decimal(report.get("npl"), "outstanding")).isEqualByComparingTo("0.00");
		assertThat(report.get("npl").get("ratio").asText()).isEqualTo("0.0000");

		JsonNode aging = data(getReport("/api/v1/reports/aging?size=100"));
		assertThat(decimal(report, "total_outstanding"))
				.isEqualByComparingTo(decimal(aging.get("portfolio"), "total_outstanding"));
		assertThat(report.get("active_contract_count").asLong())
				.isEqualTo(aging.get("contracts").get("total_elements").asLong());
	}

	@Test
	void totalsAndNplReconcileWithTheAgingReport() throws Exception {
		fixedClock().setDate(TODAY);
		// X: one installment at DPD 91 plus undue principal -> the whole contract is NPL.
		UUID x = activateContract(START);
		setInstallment(x, 1, TODAY.minusDays(3 + 91), "100.00");
		setInstallment(x, 2, TODAY.plusDays(30), "200.00");
		zeroOutFrom(x, 3);
		// Y: DPD 90 is not NPL; its period 2 is due today, inside grace (DPD 0) but receivable.
		UUID y = activateContract(START);
		setInstallment(y, 1, TODAY.minusDays(3 + 90), "500.00");
		setInstallment(y, 2, TODAY, "300.00");
		zeroOutFrom(y, 3);
		// Z: DPD 10.
		UUID z = activateContract(START);
		setInstallment(z, 1, TODAY.minusDays(3 + 10), "50.00");
		zeroOutFrom(z, 2);

		JsonNode report = data(getReport("/api/v1/reports/outstanding"));
		JsonNode aging = data(getReport("/api/v1/reports/aging?size=100"));

		assertThat(decimal(report, "total_outstanding")).isEqualByComparingTo("1150.00")
				.isEqualByComparingTo(decimal(aging.get("portfolio"), "total_outstanding"));
		assertThat(decimal(report, "accounts_receivable")).isEqualByComparingTo("950.00"); // all but X's 200
		assertThat(decimal(report, "accounts_receivable"))
				.isLessThanOrEqualTo(decimal(report, "total_outstanding"));

		// The ADR-026 D4 identity: NPL is exactly the aging rows with dpd_over_90 > 0.
		BigDecimal agingNpl = BigDecimal.ZERO;
		long agingNplRows = 0;
		for (JsonNode row : aging.get("contracts").get("content")) {
			if (decimal(row.get("buckets"), "dpd_over_90").signum() > 0) {
				agingNpl = agingNpl.add(decimal(row.get("buckets"), "total_outstanding"));
				agingNplRows++;
			}
		}
		JsonNode npl = report.get("npl");
		assertThat(npl.get("contract_count").asLong()).isEqualTo(agingNplRows).isEqualTo(1L);
		assertThat(decimal(npl, "outstanding")).isEqualByComparingTo(agingNpl).isEqualByComparingTo("300.00");
		assertThat(npl.get("ratio").asText()).isEqualTo("0.2609"); // 300 / 1150 = 0.26086...
		assertThat(report.get("active_contract_count").asLong()).isEqualTo(3L);
	}

	@Test
	void theCohortIsTheMonthsDueDatesOnNonDraftContracts() throws Exception {
		fixedClock().setDate(TODAY);
		UUID active = activateContract(START);
		setInstallment(active, 1, LocalDate.of(2026, 2, 28), "1000.00"); // last day of the previous month: out
		setInstallment(active, 2, LocalDate.of(2026, 3, 1), "2000.00");  // first of the month: in
		setInstallment(active, 3, TODAY, "3000.00");                     // as_of: in
		setInstallment(active, 4, LocalDate.of(2026, 4, 1), "4000.00");  // tomorrow: out
		// A contract closed during the period still counts for the period metrics (ADR-026 D1).
		UUID closed = activateContract(START);
		setInstallment(closed, 2, LocalDate.of(2026, 3, 15), "500.00");
		jdbc.update("update contract set status = 'CLOSED', closed_at = clock_timestamp(), "
				+ "closed_reason = 'SETTLEMENT' where id = ?", closed);
		// A DRAFT contract has no schedule and contributes nothing.
		createDraft();

		JsonNode collection = data(getReport("/api/v1/reports/outstanding")).get("collection");

		assertThat(decimal(collection, "due")).isEqualByComparingTo("5500.00"); // 2000 + 3000 + 500
		assertThat(decimal(collection, "collected")).isEqualByComparingTo("0.00");
		assertThat(collection.get("rate").asText()).isEqualTo("0.0000");
	}

	@Test
	void cohortPenaltyIsCountedNetOfActiveAdjustments() throws Exception {
		// Period 1 of a contract starting 2026-02-05 is due 2026-03-05; by 2026-03-31 it is late and accrues.
		UUID contract = activateContract(LocalDate.of(2026, 2, 5));
		fixedClock().setDate(TODAY);
		job.run(TODAY);
		BigDecimal penalty = jdbc.queryForObject(
				"select penalty_amount from installment where contract_id = ? and period_no = 1", BigDecimal.class,
				contract);
		assertThat(penalty).isPositive();
		// A 10,000 payment first (penalty-first waterfall, so it is all PENALTY allocation and counts as
		// collected). Reducing the penalty of a never-paid OVERDUE installment is avoided here: its status
		// recompute currently trips ck_installment_paid_coherence (a separate bug, outside T36).
		assertCreated(pay(contract, "penalty-partial", "10000.00"));
		UUID installment = installmentId(contract, 1);
		MvcResult reduced = mockMvc.perform(post("/api/v1/penalty-adjustments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contract_id\":\"" + contract + "\",\"installment_id\":\"" + installment
						+ "\",\"adjustment_type\":\"REDUCE\",\"amount\":1000.00,\"reason\":\"goodwill\"}"))
				.andReturn();
		assertThat(reduced.getResponse().getStatus()).as(body(reduced)).isBetween(200, 299);

		JsonNode collection = data(getReport("/api/v1/reports/outstanding")).get("collection");

		BigDecimal expectedDue = new BigDecimal(PERIOD_TOTAL).add(penalty).subtract(new BigDecimal("1000.00"));
		assertThat(decimal(collection, "due")).isEqualByComparingTo(expectedDue);
		assertThat(decimal(collection, "collected")).isEqualByComparingTo("10000.00");
		assertThat(collection.get("rate").asText())
				.isEqualTo(new BigDecimal("10000.00").divide(expectedDue, 4, RoundingMode.HALF_UP).toPlainString());
	}

	@Test
	void anEmptyBookHasZeroMoneyAndNullRatios() throws Exception {
		fixedClock().setDate(TODAY);

		MvcResult result = getReport("/api/v1/reports/outstanding");
		JsonNode report = data(result);

		assertThat(report.get("as_of").asText()).isEqualTo("2026-03-31");
		assertThat(report.get("total_outstanding").asText()).isEqualTo("0.00");
		assertThat(report.get("npl").get("ratio").isNull()).isTrue();
		assertThat(report.get("collection").get("rate").isNull()).isTrue();
		assertThat(report.get("settlement_cash").get("amount").asText()).isEqualTo("0.00");
	}

	@Test
	void aPastOrFutureAsOfIsRejectedButTodayIsAccepted() throws Exception {
		fixedClock().setDate(TODAY);

		MvcResult past = getReport("/api/v1/reports/outstanding?as_of=2026-03-30");
		assertThat(past.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(past)).isEqualTo("INVALID_AS_OF_DATE");

		MvcResult future = getReport("/api/v1/reports/outstanding?as_of=2026-04-01");
		assertThat(future.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(future)).isEqualTo("INVALID_AS_OF_DATE");

		assertThat(getReport("/api/v1/reports/outstanding?as_of=2026-03-31").getResponse().getStatus())
				.isEqualTo(200);
	}

	@Test
	void allThreeReadRolesAreAllowedAndNoTokenIsUnauthorized() throws Exception {
		for (AppRole role : new AppRole[]{AppRole.ADMIN_OPERASIONAL, AppRole.FINANCE, AppRole.MANAJEMEN}) {
			MvcResult result = mockMvc.perform(get("/api/v1/reports/outstanding")
					.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role.name()))).andReturn();
			assertThat(result.getResponse().getStatus()).as("role %s", role).isEqualTo(200);
		}

		MvcResult noToken = mockMvc.perform(get("/api/v1/reports/outstanding")).andReturn();
		assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
		assertThat(errorCode(noToken)).isEqualTo("UNAUTHORIZED");
	}

	@Test
	void theCohortRangeReadUsesTheDueDateIndex() {
		String plan = jdbc.execute((ConnectionCallback<String>) connection -> {
			try (Statement statement = connection.createStatement()) {
				statement.execute("set enable_seqscan = off");
				StringBuilder lines = new StringBuilder();
				try (ResultSet rows = statement.executeQuery("explain select id from installment "
						+ "where due_date between date '2026-03-01' and date '2026-03-31'")) {
					while (rows.next()) {
						lines.append(rows.getString(1)).append('\n');
					}
				} finally {
					statement.execute("reset enable_seqscan");
				}
				return lines.toString();
			}
		});
		assertThat(plan).contains("idx_installment_due_date");
	}

	// ---------------------------------------------------------------------------------------------

	private UUID activateContract(LocalDate plannedStart) throws Exception {
		UUID contractId = createDraft(plannedStart);
		MvcResult activated = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(activated.getResponse().getStatus()).as(body(activated)).isEqualTo(200);
		return contractId;
	}

	private UUID createDraft() throws Exception {
		return createDraft(START);
	}

	private UUID createDraft(LocalDate plannedStart) throws Exception {
		fixtureNo++;
		String suffix = String.format("%02d", fixtureNo);
		String createBody = "{"
				+ "\"customer\":{\"full_name\":\"Budi Santoso\",\"nik\":\"31710125019000" + suffix
				+ "\",\"phone\":\"081234567" + suffix + "\",\"address\":\"Jakarta\"},"
				+ "\"asset\":{\"asset_type\":\"MOTORCYCLE\",\"brand\":\"Honda\",\"model\":\"Beat\","
				+ "\"plate_no\":\"B12" + suffix + "XY\"},"
				+ "\"asset_price\":20000000.00,\"down_payment\":4000000.00,\"tenor_months\":12,"
				+ "\"interest_scheme\":\"FLAT\",\"interest_rate\":0.0150,"
				+ "\"planned_start_date\":\"" + plannedStart + "\"}";
		MvcResult created = mockMvc.perform(post("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody)).andReturn();
		assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
		return UUID.fromString(data(created).get("id").asText());
	}

	private MvcResult pay(UUID contractId, String idempotencyKey, String amount) throws Exception {
		return mockMvc.perform(post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount + ",\"channel\":\"CASH\"}"))
				.andReturn();
	}

	/** Quotes and executes an early settlement today; returns its {@code cash_received}. */
	private BigDecimal settle(UUID contractId) throws Exception {
		MvcResult quote = mockMvc.perform(post("/api/v1/settlements/quote")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contract_id\":\"" + contractId + "\"}")).andReturn();
		assertThat(quote.getResponse().getStatus()).as(body(quote)).isEqualTo(200);
		String quoteId = data(quote).get("quote_id").asText();
		MvcResult executed = mockMvc.perform(post("/api/v1/settlements")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "settle-" + contractId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"quote_id\":\"" + quoteId + "\"}")).andReturn();
		assertThat(executed.getResponse().getStatus()).as(body(executed)).isEqualTo(201);
		return decimal(data(executed), "cash_received");
	}

	/**
	 * Test-only SQL: one installment at a due date with a known principal-only outstanding. A past-due
	 * installment is OVERDUE; one due today or later stays PENDING.
	 */
	private void setInstallment(UUID contractId, int periodNo, LocalDate dueDate, String principal) {
		String status = dueDate.isBefore(clock.today()) ? "OVERDUE" : "PENDING";
		jdbc.update("""
				update installment
					set due_date = ?, principal_amount = ?, interest_amount = 0, recognized_interest_amount = 0,
						penalty_amount = 0, paid_amount = 0, settled_amount = 0, written_off_amount = 0,
						status = ?
					where contract_id = ? and period_no = ?
				""", dueDate, new BigDecimal(principal), status, contractId, periodNo);
	}

	/** Test-only SQL: fully resolve every installment from {@code fromPeriod} on, so it owes nothing. */
	private void zeroOutFrom(UUID contractId, int fromPeriod) {
		jdbc.update("""
				update installment
					set interest_amount = 0, recognized_interest_amount = 0,
						penalty_amount = 0, paid_amount = principal_amount, settled_amount = 0,
						written_off_amount = 0, status = 'PAID', paid_at = clock_timestamp()
					where contract_id = ? and period_no >= ?
				""", contractId, fromPeriod);
	}

	private UUID installmentId(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private String installmentStatus(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private BigDecimal installmentSettled(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select settled_amount from installment where contract_id = ? and period_no = ?",
				BigDecimal.class, contractId, periodNo);
	}

	private MvcResult getReport(String path) throws Exception {
		return mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn();
	}

	private void assertCreated(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
	}

	private JsonNode data(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).as(body(result)).isBetween(200, 299);
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private static String body(MvcResult result) throws Exception {
		return result.getResponse().getStatus() + ": " + result.getResponse().getContentAsString();
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return new BigDecimal(node.get(field).asText());
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
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
			return new FixedClock(FIRST_DUE);
		}
	}
}
