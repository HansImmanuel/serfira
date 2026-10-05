package com.serfira;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phase-1 exit-criteria proof (PRD §6 roadmap: "Skenario 1–4 & 8–9 lolos test"). Every behaviour under
 * test is driven over the real HTTP surface (POST /api/v1/contracts, /activate, /payments; GET
 * /installments) through the real security chain, against PostgreSQL 16 (Testcontainers) with real commits
 * so the deferred V3/V12 invariant triggers fire exactly as in production. No SQL seeds the behaviour under
 * test; only the business clock (FixedClock, Asia/Jakarta) is moved explicitly.
 *
 * <p>The money golden values reuse the derivations already proven by {@code PaymentApiIT} for the fixture
 * contract (20,000,000 asset − 4,000,000 down payment = 16,000,000 principal, 12 × FLAT 1.5% from
 * 2026-01-31): per period principal 1,333,333.33, interest 240,000.00, total 1,573,333.33; and for period 1
 * being 31 days late (28 charged days outside the 3-day grace) the accrued penalty 44,053.24. See PaymentApiIT
 * for the per-engine derivation of each constant (ScheduleEngine, PenaltyCalculator, TS §4.3 / §5).
 *
 * <p>Scenarios covered:
 * <ul>
 *   <li>1 — angsuran normal tepat waktu (PRD §5.1): full on-time payment resolves the installment.</li>
 *   <li>2 — telat, denda duluan (PRD §5.2): a late payment allocates PENALTY before INTEREST and PRINCIPAL.</li>
 *   <li>3 — bayar sebagian (PRD §5.3): a partial payment leaves the installment PARTIALLY_PAID.</li>
 *   <li>4 — bayar lebih (PRD §5.4): overpayment books customer credit and never auto-applies to the next.</li>
 *   <li>8 — jatuh tempo 31 di bulan pendek (PRD §5.8): a 31st start day clamps to each month's last day.</li>
 *   <li>9 — Februari / leap year (PRD §5.9): a leap-February due date is 02-29, a non-leap one clamps to 02-28.</li>
 * </ul>
 */
@Import({TestcontainersConfiguration.class, Phase1ExitScenariosIT.FixedClockConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class Phase1ExitScenariosIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	/** First installment due date of the fixture contract; only period 1 is due on this date. */
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);

	private static final String PERIOD_PRINCIPAL = "1333333.33";
	private static final String PERIOD_INTEREST = "240000.00";
	private static final String PERIOD_TOTAL = "1573333.33";

	/** Period 1 is 31 days late on this date: 28 days fall outside the snapshotted 3-day grace. */
	private static final LocalDate LATE_BUSINESS_DATE = LocalDate.of(2026, 3, 31);
	/** 28 charged days × 1,573.33 per day (TS §4.3), as PaymentApiIT derives. */
	private static final String ACCRUED_PENALTY = "44053.24";
	/** Period 1 principal + interest + the 28 recognized penalty days. */
	private static final String LATE_PAYMENT_TOTAL = "1617386.57";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	Clock clock;

	private UUID actorId;
	private String token;

	@BeforeEach
	void seedActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Phase1 IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "phase1-it-actor-" + UUID.randomUUID());
		token = TestJwts.forRoles(actorId, AppRole.ADMIN_OPERASIONAL.name());
		fixedClock().setDate(BUSINESS_DATE);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	// -------------------------------------------------------------------------------------
	// Scenario 1 — angsuran normal tepat waktu (PRD §5.1)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario1_aFullOnTimePaymentResolvesTheInstallmentAndPostsABalancedJournal() throws Exception {
		UUID contractId = activateFixtureContract("3171012501900001", "08123456789", "B1234XY",
				LocalDate.of(2026, 1, 31));

		// Before any payment: 12 PENDING installments, first due on the short-month clamp, no interest
		// recognized yet (future scheduled interest is never receivable early, PRD §5A).
		JsonNode schedule = installments(contractId);
		assertThat(schedule.size()).isEqualTo(12);
		assertThat(schedule.get(0).get("due_date").asText()).isEqualTo("2026-02-28");
		assertThat(decimal(schedule.get(0), "principal_amount")).isEqualByComparingTo(PERIOD_PRINCIPAL);
		assertThat(decimal(schedule.get(0), "interest_amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(schedule.get(0), "recognized_interest_amount")).isEqualByComparingTo("0.00");
		for (JsonNode line : schedule) {
			assertThat(line.get("status").asText()).isEqualTo("PENDING");
		}

		MvcResult paid = pay(contractId, "scenario-1", PERIOD_TOTAL);

		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(paid);
		// Waterfall inside the only due installment: interest before principal, no penalty yet.
		assertThat(allocationTypes(payment)).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(payment.get("allocations").get(1), "amount")).isEqualByComparingTo(PERIOD_PRINCIPAL);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");

		assertThat(installmentStatus(contractId, 1)).isEqualTo("PAID");
		assertThat(installmentStatus(contractId, 2)).isEqualTo("PENDING");

		// Invariant 3: Σ allocations = payment amount. Invariant 1: the payment journal balances.
		UUID paymentId = UUID.fromString(payment.get("id").asText());
		assertAllocationsSumToAmount(paymentId, PERIOD_TOTAL);
		assertEveryJournalEntryBalances(contractId);
	}

	// -------------------------------------------------------------------------------------
	// Scenario 2 — telat, denda duluan (PRD §5.2)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario2_aLatePaymentAllocatesPenaltyBeforeInterestAndPrincipal() throws Exception {
		UUID contractId = activateFixtureContract("3171012501900001", "08123456789", "B1234XY",
				LocalDate.of(2026, 1, 31));
		// Move the clock 31 days past the first due date; the payment path bills + accrues lazily, so the
		// 28 charged penalty days are recognized inside the one payment transaction (no job seeding).
		fixedClock().setDate(LATE_BUSINESS_DATE);

		MvcResult paid = pay(contractId, "scenario-2", LATE_PAYMENT_TOTAL);

		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(paid);
		// PENALTY first, nonzero, then INTEREST then PRINCIPAL (oldest-due-first waterfall).
		assertThat(allocationTypes(payment)).containsExactly("PENALTY", "INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo(ACCRUED_PENALTY);
		assertThat(decimal(payment.get("allocations").get(1), "amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(payment.get("allocations").get(2), "amount")).isEqualByComparingTo(PERIOD_PRINCIPAL);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentStatus(contractId, 1)).isEqualTo("PAID");

		// 28 append-only accrual rows summing to the PENALTY allocation (invariant 8: never edited).
		UUID installment1 = installmentId(contractId, 1);
		assertThat(jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Long.class, installment1)).isEqualTo(28L);
		assertThat(jdbc.queryForObject("select sum(amount) from penalty_accrual where installment_id = ?",
				BigDecimal.class, installment1)).isEqualByComparingTo(ACCRUED_PENALTY);

		UUID paymentId = UUID.fromString(payment.get("id").asText());
		assertAllocationsSumToAmount(paymentId, LATE_PAYMENT_TOTAL);
		assertEveryJournalEntryBalances(contractId);
	}

	// -------------------------------------------------------------------------------------
	// Scenario 3 — bayar sebagian (PRD §5.3)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario3_aPartialPaymentLeavesTheInstallmentPartiallyPaidWithResidualOwed() throws Exception {
		UUID contractId = activateFixtureContract("3171012501900001", "08123456789", "B1234XY",
				LocalDate.of(2026, 1, 31));

		MvcResult paid = pay(contractId, "scenario-3", "500000.00");

		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(paid);
		assertThat(allocationTypes(payment)).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");

		Map<String, Object> installment = installmentRow(contractId, 1);
		assertThat((BigDecimal) installment.get("paid_amount")).isEqualByComparingTo("500000.00");
		assertThat(installment.get("status")).isEqualTo("PARTIALLY_PAID");
		// Residual still owed: paid < the recognized period total.
		assertThat(new BigDecimal(PERIOD_TOTAL).subtract((BigDecimal) installment.get("paid_amount")))
				.isEqualByComparingTo("1073333.33");

		assertAllocationsSumToAmount(UUID.fromString(payment.get("id").asText()), "500000.00");
		assertEveryJournalEntryBalances(contractId);
	}

	// -------------------------------------------------------------------------------------
	// Scenario 4 — bayar lebih, prepayment not auto-applied (PRD §5.4, invariant 6, ADR-009)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario4_anOverpaymentBooksCustomerCreditAndNeverAutoAppliesToTheNextInstallment() throws Exception {
		UUID contractId = activateFixtureContract("3171012501900001", "08123456789", "B1234XY",
				LocalDate.of(2026, 1, 31));
		String excess = "10426666.67"; // 12,000,000 − the only due installment's 1,573,333.33 capacity.

		MvcResult paid = pay(contractId, "scenario-4", "12000000.00");

		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(paid);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo(excess);
		// EXCESS is the last allocation and carries no installment (invariant 6).
		JsonNode lastAllocation = payment.get("allocations").get(payment.get("allocations").size() - 1);
		assertThat(lastAllocation.get("allocation_type").asText()).isEqualTo("EXCESS");
		assertThat(lastAllocation.get("installment_id").isNull()).isTrue();
		assertThat(decimal(lastAllocation, "amount")).isEqualByComparingTo(excess);

		assertThat(installmentStatus(contractId, 1)).isEqualTo("PAID");
		// The next installment is untouched: credit is never auto-applied to a future period (PRD §5.4).
		assertThat(installmentStatus(contractId, 2)).isEqualTo("PENDING");
		assertThat((BigDecimal) installmentRow(contractId, 2).get("paid_amount")).isEqualByComparingTo("0.00");

		// The excess is booked as a customer-credit liability (TITIPAN_NASABAH), not as revenue.
		UUID paymentId = UUID.fromString(payment.get("id").asText());
		UUID paymentEntry = paymentEntryOf(paymentId);
		assertThat(creditByAccount(paymentEntry)).containsEntry("TITIPAN_NASABAH", new BigDecimal(excess));

		assertAllocationsSumToAmount(paymentId, "12000000.00");
		assertEveryJournalEntryBalances(contractId);
	}

	// -------------------------------------------------------------------------------------
	// Scenario 8 — due date 31 in a short month clamps to the month's last day (PRD §5.8)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario8_aDueDayOf31ClampsToTheLastDayOfEachShorterMonth() throws Exception {
		// Start on the 31st: period 1 (Feb) clamps to 28, period 3 (Apr), 5 (Jun), 8 (Sep), 10 (Nov) clamp
		// to 30; the 31-day months keep the 31st. The due date is startDate.plusMonths(n) (DM §1.4).
		UUID contractId = activateFixtureContract("3171012501900001", "08123456789", "B1234XY",
				LocalDate.of(2026, 1, 31));

		List<String> dueDates = dueDates(contractId);
		assertThat(dueDates).containsExactly(
				"2026-02-28", // Feb (short month clamp)
				"2026-03-31",
				"2026-04-30", // Apr (30-day clamp)
				"2026-05-31",
				"2026-06-30", // Jun (30-day clamp)
				"2026-07-31",
				"2026-08-31",
				"2026-09-30", // Sep (30-day clamp)
				"2026-10-31",
				"2026-11-30", // Nov (30-day clamp)
				"2026-12-31",
				"2027-01-31");
	}

	// -------------------------------------------------------------------------------------
	// Scenario 9 — February / leap year keeps a valid due date (PRD §5.9)
	// -------------------------------------------------------------------------------------

	@Test
	void scenario9_aLeapFebruaryDueDateIs0229WhileANonLeapFebruaryClampsTo0228() throws Exception {
		// A future start on 2027-12-31 (allowed: future dates have no backfill, ADR-016). 2028 is a leap
		// year, so period 2 lands on 2028-02-29 (valid, not clamped); contrast with a 2026-12-31 start whose
		// period 2 lands on the non-leap 2027-02-28.
		UUID leap = activateFixtureContract("3171012501900003", "08123456781", "B2028LY",
				LocalDate.of(2027, 12, 31));
		List<String> leapDueDates = dueDates(leap);
		assertThat(leapDueDates.get(0)).isEqualTo("2028-01-31");
		assertThat(leapDueDates.get(1)).isEqualTo("2028-02-29"); // leap February — the 29th is valid.
		assertThat(leapDueDates.get(2)).isEqualTo("2028-03-31");

		UUID nonLeap = activateFixtureContract("3171012501900004", "08123456782", "B2027NL",
				LocalDate.of(2026, 12, 31));
		List<String> nonLeapDueDates = dueDates(nonLeap);
		assertThat(nonLeapDueDates.get(0)).isEqualTo("2027-01-31");
		assertThat(nonLeapDueDates.get(1)).isEqualTo("2027-02-28"); // non-leap February clamps to the 28th.
		assertThat(nonLeapDueDates.get(2)).isEqualTo("2027-03-31");
	}

	// -------------------------------------------------------------------------------------
	// HTTP harness
	// -------------------------------------------------------------------------------------

	/** Create and activate the fixture contract over HTTP, returning its id. */
	private UUID activateFixtureContract(String nik, String phone, String plateNo, LocalDate plannedStartDate)
			throws Exception {
		String createBody = "{"
				+ "\"customer\":{\"full_name\":\"Budi Santoso\",\"nik\":\"" + nik + "\",\"phone\":\"" + phone
				+ "\",\"address\":\"Jakarta\"},"
				+ "\"asset\":{\"asset_type\":\"MOTORCYCLE\",\"brand\":\"Honda\",\"model\":\"Beat\",\"plate_no\":\""
				+ plateNo + "\"},"
				+ "\"asset_price\":20000000.00,\"down_payment\":4000000.00,\"tenor_months\":12,"
				+ "\"interest_scheme\":\"FLAT\",\"interest_rate\":0.0150,"
				+ "\"planned_start_date\":\"" + plannedStartDate + "\"}";
		MvcResult created = mockMvc.perform(post("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", "create-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(createBody)).andReturn();
		assertThat(created.getResponse().getStatus())
				.as("create returned %s", created.getResponse().getContentAsString())
				.isEqualTo(201);
		UUID contractId = UUID.fromString(data(created).get("id").asText());

		MvcResult activated = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/activate")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(activated.getResponse().getStatus())
				.as("activate returned %s", activated.getResponse().getContentAsString())
				.isEqualTo(200);
		return contractId;
	}

	private MvcResult pay(UUID contractId, String idempotencyKey, String amount) throws Exception {
		String body = "{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount + ",\"channel\":\"CASH\"}";
		return mockMvc.perform(post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn();
	}

	private JsonNode installments(UUID contractId) throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/contracts/" + contractId + "/installments")
				.header("Authorization", "Bearer " + token)).andReturn();
		assertThat(result.getResponse().getStatus())
				.as("GET installments returned %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		return data(result);
	}

	private List<String> dueDates(UUID contractId) throws Exception {
		List<String> dates = new ArrayList<>();
		installments(contractId).forEach(line -> dates.add(line.get("due_date").asText()));
		return dates;
	}

	// -------------------------------------------------------------------------------------
	// Invariant assertions and jdbc reads
	// -------------------------------------------------------------------------------------

	/** Invariant 3: a posted payment's allocations sum exactly to its amount. */
	private void assertAllocationsSumToAmount(UUID paymentId, String amount) {
		assertThat(jdbc.queryForObject("select sum(amount) from payment_allocation where payment_id = ?",
				BigDecimal.class, paymentId)).isEqualByComparingTo(amount);
	}

	/** Invariant 1: every journal entry touching this contract balances (Σ debit = Σ credit). */
	private void assertEveryJournalEntryBalances(UUID contractId) {
		List<UUID> entryIds = jdbc.queryForList("""
				select distinct je.id from journal_entry je
				join journal_line jl on jl.journal_entry_id = je.id
				where jl.contract_id = ?
				""", UUID.class, contractId);
		assertThat(entryIds).isNotEmpty();
		for (UUID entryId : entryIds) {
			BigDecimal debit = jdbc.queryForObject(
					"select coalesce(sum(debit), 0) from journal_line where journal_entry_id = ?",
					BigDecimal.class, entryId);
			BigDecimal credit = jdbc.queryForObject(
					"select coalesce(sum(credit), 0) from journal_line where journal_entry_id = ?",
					BigDecimal.class, entryId);
			assertThat(debit).as("entry %s must balance", entryId).isEqualByComparingTo(credit);
		}
	}

	private UUID paymentEntryOf(UUID paymentId) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'PAYMENT' and ref_id = ?",
				UUID.class, paymentId);
	}

	private Map<String, BigDecimal> creditByAccount(UUID entryId) {
		Map<String, BigDecimal> credits = new java.util.LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("select account_code, credit from journal_line "
				+ "where journal_entry_id = ? and credit > 0", entryId)) {
			credits.put((String) row.get("account_code"), (BigDecimal) row.get("credit"));
		}
		return credits;
	}

	private UUID installmentId(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private String installmentStatus(UUID contractId, int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private Map<String, Object> installmentRow(UUID contractId, int periodNo) {
		return jdbc.queryForMap("select paid_amount, status from installment where contract_id = ? "
				+ "and period_no = ?", contractId, periodNo);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private List<String> allocationTypes(JsonNode payment) {
		List<String> types = new ArrayList<>();
		payment.get("allocations").forEach(allocation -> types.add(allocation.get("allocation_type").asText()));
		return types;
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return node.get(field).decimalValue();
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
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
