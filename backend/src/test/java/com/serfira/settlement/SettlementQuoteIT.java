package com.serfira.settlement;

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
import com.serfira.support.TestJwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E1 — settlement quote end to end (task T12, ADR-018). {@code POST /api/v1/settlements/quote} bills and
 * accrues through today (accrue-before-resolve) and persists an immutable {@code settlement_quote} with the
 * priced components, a TTL and the contract version. No journal is posted and no money moves (execution is
 * T13). Real commits (MockMvc, no test transaction) so the V5 immutability trigger fires exactly where it
 * does in production.
 *
 * <p>Fixture: Demo Contract B — EFFECTIVE, principal 150,000,000, 36 months, 0.75%/month, start 2026-01-15
 * (Addendum §18.2).
 */
@Import({TestcontainersConfiguration.class, SettlementQuoteClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class SettlementQuoteIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = "ADMIN_OPERASIONAL";
	private static final String FINANCE = "FINANCE";
	private static final String MANAJEMEN = "MANAJEMEN";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	@Autowired
	ContractCommandService contracts;

	private UUID actorId;
	private String adminToken;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractAndActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Settlement IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "settlement-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(SettlementQuoteClockTestConfiguration.BUSINESS_DATE);

		ContractResponse draft = contracts.create("settlement-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.CAR, "Toyota", "Avanza", null, "B9150XY"),
						new BigDecimal("150000000.00"), new BigDecimal("0.00"), 36, InterestScheme.EFFECTIVE,
						new BigDecimal("0.0075"), LocalDate.of(2026, 1, 15)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void aQuoteOnTheDueDateIsPricedPersistedAndBalances() throws Exception {
		// Pay installments 1..5 on time, then settle on period 6's due date (2026-07-15): period 6 is billed
		// that day, periods 7..36 are future with a 50% rebate, accrued_interest = 0 (on a due date).
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(LocalDate.of(2026, 7, 15));

		MvcResult result = quote();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode data = data(result);

		UUID quoteId = UUID.fromString(data.get("quote_id").asText());
		assertThat(data.get("status").asText()).isEqualTo("QUOTED");
		assertThat(data.get("accrued_interest").decimalValue()).isEqualByComparingTo("0.00");
		assertThat(data.get("penalty_outstanding").decimalValue()).isEqualByComparingTo("0.00");
		assertThat(data.get("credit_used").decimalValue()).isEqualByComparingTo("0.00");

		// D6 identities hold on the persisted row.
		Map<String, Object> row = jdbc.queryForMap("select * from settlement_quote where id = ?", quoteId);
		BigDecimal principal = (BigDecimal) row.get("outstanding_principal");
		BigDecimal billedInterest = (BigDecimal) row.get("unpaid_billed_interest");
		BigDecimal accrued = (BigDecimal) row.get("accrued_interest");
		BigDecimal penalty = (BigDecimal) row.get("penalty_outstanding");
		BigDecimal rebate = (BigDecimal) row.get("rebate_amount");
		BigDecimal adminFee = (BigDecimal) row.get("admin_fee");
		BigDecimal gross = (BigDecimal) row.get("gross_amount");
		BigDecimal cashDue = (BigDecimal) row.get("cash_due");

		assertThat(adminFee).isEqualByComparingTo("150000.00"); // SETTLEMENT_ADMIN_FEE
		// futureInterestCharged = gross − (principal + billed + accrued + penalty + admin); its gross half
		// = charged + rebate, so (charged + rebate) is the full future interest. Charged must be positive.
		BigDecimal futureCharged = gross
				.subtract(principal).subtract(billedInterest).subtract(accrued).subtract(penalty).subtract(adminFee);
		assertThat(futureCharged).isGreaterThan(BigDecimal.ZERO);
		assertThat(rebate).isGreaterThan(BigDecimal.ZERO);
		// 50% rebate: the rebate equals the charged future half (both are half of the gross future interest),
		// to within one cent of HALF_EVEN rounding.
		assertThat(rebate.subtract(futureCharged).abs()).isLessThanOrEqualTo(new BigDecimal("0.01"));
		assertThat(cashDue).isEqualByComparingTo(gross); // credit_used = 0

		// The contract version was snapshotted and the TTL is 15 minutes after quoted_at.
		long contractVersion = jdbc.queryForObject("select version from contract where id = ?", Long.class, contractId);
		assertThat(((Number) row.get("contract_version")).longValue()).isEqualTo(contractVersion);
		OffsetDateTime quotedAt = jdbc.queryForObject(
				"select quoted_at from settlement_quote where id = ?", OffsetDateTime.class, quoteId);
		OffsetDateTime validUntil = jdbc.queryForObject(
				"select valid_until from settlement_quote where id = ?", OffsetDateTime.class, quoteId);
		assertThat(validUntil).isEqualTo(quotedAt.plusMinutes(15));

		// A quote posts NO journal and resolves NO money (execution is T13).
		assertThat(journalEntryCount("SETTLEMENT")).isZero();
		assertThat(count("settlement")).isZero();
	}

	@Test
	void accrueBeforeResolveBillsDueInterestIntoTheQuote() throws Exception {
		// No payments. Move to a date where periods 1..6 are due but have never been billed, then quote.
		// The quote must bill them first (accrue-before-resolve), so unpaid_billed_interest reflects the
		// freshly recognized interest of the six due periods — not zero.
		fixedClock().setDate(LocalDate.of(2026, 7, 15));

		// Precondition: nothing billed yet.
		assertThat(jdbc.queryForObject(
				"select coalesce(sum(recognized_interest_amount),0) from installment where contract_id = ?",
				BigDecimal.class, contractId)).isEqualByComparingTo("0.00");

		MvcResult result = quote();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);

		// Billing recognized the six due periods' interest in the same transaction.
		BigDecimal recognized = jdbc.queryForObject(
				"select coalesce(sum(recognized_interest_amount),0) from installment where contract_id = ?",
				BigDecimal.class, contractId);
		assertThat(recognized).isGreaterThan(BigDecimal.ZERO);

		// The quote's unpaid_billed_interest equals that recognized, unpaid interest.
		BigDecimal billedInterest = jdbc.queryForObject(
				"select unpaid_billed_interest from settlement_quote where contract_id = ?", BigDecimal.class,
				contractId);
		assertThat(billedInterest).isEqualByComparingTo(recognized);
	}

	@Test
	void quoteComponentColumnsAreImmutableEvenViaRawSql() throws Exception {
		MvcResult result = quote();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		UUID quoteId = UUID.fromString(data(result).get("quote_id").asText());

		// The V5 trg_settlement_quote_mutability trigger freezes every component column.
		assertThatThrownBy(() -> jdbc.update(
				"update settlement_quote set gross_amount = gross_amount + 1 where id = ?", quoteId))
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("immutable");
	}

	@Test
	void quotingANonActiveContractIsConflict() throws Exception {
		ContractResponse draft = contracts.create("settlement-draft-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Draft Customer", "3171012501900002", "08120000000", "Jakarta"),
						new CreateAssetRequest(AssetType.CAR, "Toyota", "Avanza", null, "B0001ZZ"),
						new BigDecimal("150000000.00"), new BigDecimal("0.00"), 36, InterestScheme.EFFECTIVE,
						new BigDecimal("0.0075"), LocalDate.of(2026, 1, 15)));

		MvcResult result = quoteContract(draft.id());

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_STATE_INVALID");
		assertThat(count("settlement_quote")).isZero();
	}

	@Test
	void quotingAnUnknownContractIsNotFound() throws Exception {
		MvcResult result = quoteContract(UUID.randomUUID());

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("CONTRACT_NOT_FOUND");
		assertThat(count("settlement_quote")).isZero();
	}

	@Test
	void quotingIsForbiddenForFinanceAndManajemen() throws Exception {
		for (String role : new String[] {FINANCE, MANAJEMEN}) {
			MvcResult result = mockMvc.perform(post("/api/v1/settlements/quote")
							.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, role))
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"contract_id\":\"" + contractId + "\"}"))
					.andReturn();
			assertThat(result.getResponse().getStatus()).isEqualTo(403);
			assertThat(errorCode(result)).isEqualTo("FORBIDDEN");
		}
		assertThat(count("settlement_quote")).isZero();
	}

	// --- helpers ---------------------------------------------------------------------------------------

	/** Pays each of periods {@code from..to} its full installment total on its due date, in order. */
	private void payPeriodsOnTime(int from, int to) throws Exception {
		for (int period = from; period <= to; period++) {
			LocalDate dueDate = LocalDate.of(2026, 1, 15).plusMonths(period);
			fixedClock().setDate(dueDate);
			BigDecimal total = jdbc.queryForObject(
					"select principal_amount + interest_amount from installment where contract_id = ? and period_no = ?",
					BigDecimal.class, contractId, period);
			MvcResult paid = mockMvc.perform(post("/api/v1/payments")
							.header("Authorization", "Bearer " + adminToken)
							.header("Idempotency-Key", "settle-pay-" + period)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + total.toPlainString()
									+ ",\"channel\":\"CASH\"}"))
					.andReturn();
			assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		}
	}

	private MvcResult quote() throws Exception {
		return quoteContract(contractId);
	}

	private MvcResult quoteContract(UUID targetContractId) throws Exception {
		return mockMvc.perform(post("/api/v1/settlements/quote")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + targetContractId + "\"}"))
				.andReturn();
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("code").asText();
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
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
}
