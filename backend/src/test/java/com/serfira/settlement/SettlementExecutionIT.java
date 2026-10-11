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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E2 — settlement execution end to end (task T13, ADR-018). {@code POST /api/v1/settlements} executes a
 * QUOTED quote: it re-prices and revalidates against the snapshot, consumes all available credit, posts the
 * one balanced SETTLEMENT journal entry, resolves installments, and closes the contract SETTLEMENT. Real
 * commits (MockMvc, no test transaction) so the V3/V12/V14/V17 deferred triggers fire exactly where they do
 * in production.
 *
 * <p>Fixture: Demo Contract B — EFFECTIVE, principal 150,000,000, 36 months, 0.75%/month, start 2026-01-15
 * (Addendum §18.2). Periods 1..5 are paid on time, then the contract is quoted and settled on period 6's
 * due date (2026-07-15): period 6 is billed and unpaid, periods 7..36 are future with a 50% rebate,
 * {@code accrued_interest = 0} on a due date, and there is no penalty.
 */
@Import({TestcontainersConfiguration.class, SettlementQuoteClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class SettlementExecutionIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = "ADMIN_OPERASIONAL";
	private static final LocalDate SETTLEMENT_DATE = LocalDate.of(2026, 7, 15); // period 6 due date

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
					values (?, 'test-hash', 'Settlement Exec IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "settlement-exec-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(SettlementQuoteClockTestConfiguration.BUSINESS_DATE);

		ContractResponse draft = contracts.create("settlement-exec-it-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.CAR, "Toyota", "Avanza", null, "B9151XY"),
						new BigDecimal("150000000.00"), new BigDecimal("0.00"), 36, InterestScheme.EFFECTIVE,
						new BigDecimal("0.0075"), LocalDate.of(2026, 1, 15)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	// ---------------------------------------------------------------------------------------------------
	// (a) Happy path: one balanced SETTLEMENT entry, contract closed, installments SETTLED.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void executingTheQuotePostsOneBalancedEntryClosesTheContractAndSettlesInstallments() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		MvcResult result = execute(quoteId, "settle-happy");
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode data = data(result);

		UUID settlementId = UUID.fromString(data.get("settlement_id").asText());
		assertThat(data.get("credit_used").decimalValue()).isEqualByComparingTo("0.00");
		BigDecimal cashReceived = data.get("cash_received").decimalValue();
		assertThat(cashReceived).isGreaterThan(BigDecimal.ZERO);

		// Exactly one non-reversal SETTLEMENT journal entry for this settlement.
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_type = 'SETTLEMENT' and ref_id = ? and reversal_of_id is null",
				Long.class, settlementId)).isEqualTo(1L);

		// Σ debit = Σ credit on the posted entry (invariant 1).
		Map<String, Object> totals = jdbc.queryForMap("""
				select coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
				from journal_line l join journal_entry e on e.id = l.journal_entry_id
				where e.ref_type = 'SETTLEMENT' and e.ref_id = ?""", settlementId);
		assertThat((BigDecimal) totals.get("debit")).isEqualByComparingTo((BigDecimal) totals.get("credit"));

		// D4 lines on the normal path: Dr KAS; Cr PIUTANG_POKOK, PIUTANG_BUNGA, PENDAPATAN_BUNGA,
		// PENDAPATAN_ADMIN. No TITIPAN_NASABAH (no credit), no PIUTANG_DENDA (no penalty), no DISKON_PELUNASAN.
		BigDecimal kasDebit = lineAmount(settlementId, "KAS", "debit");
		assertThat(kasDebit).isEqualByComparingTo(cashReceived);
		assertThat(lineAmount(settlementId, "PENDAPATAN_ADMIN", "credit")).isEqualByComparingTo("150000.00");
		assertThat(lineAmount(settlementId, "PENDAPATAN_BUNGA", "credit")).isGreaterThan(BigDecimal.ZERO);
		assertThat(lineAmount(settlementId, "PIUTANG_POKOK", "credit")).isGreaterThan(BigDecimal.ZERO);
		assertThat(lineCount(settlementId, "DISKON_PELUNASAN")).isZero();
		assertThat(lineCount(settlementId, "TITIPAN_NASABAH")).isZero();
		assertThat(lineCount(settlementId, "PIUTANG_DENDA")).isZero();

		// The settlement_allocation receivable credits agree with the journal receivable credits (D5).
		assertThat(allocationTotal(settlementId, "PRINCIPAL"))
				.isEqualByComparingTo(lineAmount(settlementId, "PIUTANG_POKOK", "credit"));
		assertThat(allocationTotal(settlementId, "INTEREST"))
				.isEqualByComparingTo(lineAmount(settlementId, "PIUTANG_BUNGA", "credit"));

		// Contract closed SETTLEMENT, open installments SETTLED with settled_amount/settled_at.
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, contractId))
				.isEqualTo("CLOSED");
		assertThat(jdbc.queryForObject("select closed_reason from contract where id = ?", String.class, contractId))
				.isEqualTo("SETTLEMENT");
		assertThat(jdbc.queryForObject(
				"select count(*) from installment where contract_id = ? and status = 'SETTLED' and settled_at is not null",
				Long.class, contractId)).isGreaterThan(0L);
		assertThat(jdbc.queryForObject(
				"select count(*) from installment where contract_id = ? and status = 'SETTLED' and settled_at is null",
				Long.class, contractId)).isZero();

		// The quote is now EXECUTED.
		assertThat(jdbc.queryForObject("select status from settlement_quote where id = ?", String.class, quoteId))
				.isEqualTo("EXECUTED");
	}

	// ---------------------------------------------------------------------------------------------------
	// (b) Stale quote: a payment between quote and execute changes a recomputed component.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void aStaleQuoteIsRejectedAndWritesNothing() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		// Pay period 6 after quoting: outstanding principal / billed interest now differ from the snapshot.
		pay(period6Total(), "stale-interleaving-pay");

		MvcResult result = execute(quoteId, "settle-stale");
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("STALE_SETTLEMENT_QUOTE");
		assertNothingSettled(quoteId);
	}

	// ---------------------------------------------------------------------------------------------------
	// (c) Expired quote: clock.now() > valid_until.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void anExpiredQuoteIsRejectedAndWritesNothing() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		// The TTL is 15 minutes; move 16 minutes past quoted_at (same business date, so re-pricing matches).
		fixedClock().advanceBy(Duration.ofMinutes(16));

		MvcResult result = execute(quoteId, "settle-expired");
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("SETTLEMENT_QUOTE_EXPIRED");
		assertNothingSettled(quoteId);
	}

	// ---------------------------------------------------------------------------------------------------
	// (d) Already-executed quote re-executed with a different key.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void reExecutingAnExecutedQuoteWithADifferentKeyIsRejected() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		assertThat(execute(quoteId, "settle-first").getResponse().getStatus()).isEqualTo(201);

		MvcResult second = execute(quoteId, "settle-second-different-key");
		assertThat(second.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(second)).isEqualTo("SETTLEMENT_QUOTE_ALREADY_EXECUTED");
		// Still exactly one settlement for the quote.
		assertThat(jdbc.queryForObject("select count(*) from settlement where quote_id = ?", Long.class, quoteId))
				.isEqualTo(1L);
	}

	// ---------------------------------------------------------------------------------------------------
	// (e) Idempotent identical-key replay returns the stored settlement, no second journal.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void anIdenticalKeyReplayReturnsTheStoredSettlementWithoutASecondJournal() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		MvcResult first = execute(quoteId, "settle-idem");
		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		String firstSettlementId = data(first).get("settlement_id").asText();

		MvcResult replay = execute(quoteId, "settle-idem");
		assertThat(replay.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(replay).get("settlement_id").asText()).isEqualTo(firstSettlementId);

		assertThat(jdbc.queryForObject("select count(*) from settlement where quote_id = ?", Long.class, quoteId))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_type = 'SETTLEMENT' and ref_id = ?",
				Long.class, UUID.fromString(firstSettlementId))).isEqualTo(1L);
	}

	// ---------------------------------------------------------------------------------------------------
	// (f) Expired idempotency key -> IDEMPOTENCY_KEY_EXPIRED.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void aSpentIdempotencyKeyPastRetentionIsRejectedAsExpired() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());

		assertThat(execute(quoteId, "settle-key-retention").getResponse().getStatus()).isEqualTo(201);

		// Force the claim past its retention window, then reuse the same key: option A rejects it as spent
		// before the operation runs (ADR-017), independent of the quote already being EXECUTED.
		jdbc.update("update idempotency_keys set expires_at = clock_timestamp() - interval '1 day' "
				+ "where endpoint = ? and key = ?", "POST /api/v1/settlements", "settle-key-retention");

		MvcResult reused = execute(quoteId, "settle-key-retention");
		assertThat(reused.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(reused)).isEqualTo("IDEMPOTENCY_KEY_EXPIRED");
	}

	// ---------------------------------------------------------------------------------------------------
	// (g) Credit fully consumed: a seeded AVAILABLE credit <= gross is consumed and recorded.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void availableCreditIsFullyConsumedAndRecorded() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		BigDecimal creditAmount = new BigDecimal("5000000.00"); // well under the gross
		UUID creditId = seedAvailableCredit(creditAmount);

		UUID quoteId = quoteId(quote());
		MvcResult result = execute(quoteId, "settle-credit");
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode data = data(result);

		UUID settlementId = UUID.fromString(data.get("settlement_id").asText());
		assertThat(data.get("credit_used").decimalValue()).isEqualByComparingTo(creditAmount);

		// One settlement_credit_application for the source; the TITIPAN_NASABAH debit equals credit_used.
		assertThat(jdbc.queryForObject(
				"select count(*) from settlement_credit_application where settlement_id = ? and contract_credit_id = ?",
				Long.class, settlementId, creditId)).isEqualTo(1L);
		assertThat(lineAmount(settlementId, "TITIPAN_NASABAH", "debit")).isEqualByComparingTo(creditAmount);

		// The credit flipped AVAILABLE -> APPLIED (V17 counts settlement_credit_application in the balance).
		assertThat(jdbc.queryForObject("select status from contract_credit where id = ?", String.class, creditId))
				.isEqualTo("APPLIED");

		// Entry still balances with the credit leg present.
		Map<String, Object> totals = jdbc.queryForMap("""
				select coalesce(sum(l.debit),0) as debit, coalesce(sum(l.credit),0) as credit
				from journal_line l join journal_entry e on e.id = l.journal_entry_id
				where e.ref_type = 'SETTLEMENT' and e.ref_id = ?""", settlementId);
		assertThat((BigDecimal) totals.get("debit")).isEqualByComparingTo((BigDecimal) totals.get("credit"));
	}

	// ---------------------------------------------------------------------------------------------------
	// (h) Credit greater than gross -> CREDIT_EXCEEDS_SETTLEMENT, nothing written.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void creditLargerThanGrossIsRejectedAndWritesNothing() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		// A credit larger than any plausible gross for this contract.
		UUID creditId = seedAvailableCredit(new BigDecimal("999000000.00"));

		UUID quoteId = quoteId(quote());
		MvcResult result = execute(quoteId, "settle-credit-exceeds");
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("CREDIT_EXCEEDS_SETTLEMENT");

		assertNothingSettled(quoteId);
		// The credit stays AVAILABLE and unconsumed.
		assertThat(jdbc.queryForObject("select status from contract_credit where id = ?", String.class, creditId))
				.isEqualTo("AVAILABLE");
		assertThat(jdbc.queryForObject("select count(*) from settlement_credit_application", Long.class)).isZero();
	}

	// ---------------------------------------------------------------------------------------------------
	// (i) A duplicate non-reversal SETTLEMENT entry for the same settlement is rejected by the V17 backstop.
	// ---------------------------------------------------------------------------------------------------
	@Test
	void aSecondNonReversalSettlementEntryForTheSameSettlementIsRejectedByTheIndex() throws Exception {
		payPeriodsOnTime(1, 5);
		fixedClock().setDate(SETTLEMENT_DATE);
		UUID quoteId = quoteId(quote());
		UUID settlementId = UUID.fromString(data(execute(quoteId, "settle-dupe")).get("settlement_id").asText());

		// A raw second non-reversal SETTLEMENT journal_entry for the same ref_id violates uq_journal_entry_event
		// (extended to include SETTLEMENT by V17, ADR-018 D1).
		UUID duplicateEntryId = UUID.randomUUID();
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
				insert into journal_entry (id, entry_date, ref_type, ref_id, posted_at, created_at)
					values (?, clock_timestamp(), 'SETTLEMENT', ?, clock_timestamp(), clock_timestamp())""",
				duplicateEntryId, settlementId))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
		assertThat(jdbc.queryForObject("select count(*) from journal_entry where id = ?", Long.class,
				duplicateEntryId)).isZero();
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private void payPeriodsOnTime(int from, int to) throws Exception {
		for (int period = from; period <= to; period++) {
			LocalDate dueDate = LocalDate.of(2026, 1, 15).plusMonths(period);
			fixedClock().setDate(dueDate);
			BigDecimal total = jdbc.queryForObject(
					"select principal_amount + interest_amount from installment where contract_id = ? and period_no = ?",
					BigDecimal.class, contractId, period);
			MvcResult paid = mockMvc.perform(post("/api/v1/payments")
							.header("Authorization", "Bearer " + adminToken)
							.header("Idempotency-Key", "settle-exec-pay-" + period)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + total.toPlainString()
									+ ",\"channel\":\"CASH\"}"))
					.andReturn();
			assertThat(paid.getResponse().getStatus()).isEqualTo(201);
		}
		fixedClock().setDate(SETTLEMENT_DATE);
	}

	private void pay(BigDecimal amount, String key) throws Exception {
		MvcResult paid = mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount.toPlainString()
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
		assertThat(paid.getResponse().getStatus()).isEqualTo(201);
	}

	private BigDecimal period6Total() {
		return jdbc.queryForObject(
				"select principal_amount + interest_amount from installment where contract_id = ? and period_no = 6",
				BigDecimal.class, contractId);
	}

	private UUID seedAvailableCredit(BigDecimal amount) {
		// A contract_credit needs a source_payment_allocation_id (NOT NULL FK). Seed an EXCESS payment
		// allocation via a payment on a far-past period so the fixture is self-contained; the credit then
		// cites it. The payment itself is immaterial to the settlement math beyond being resolvable.
		UUID paymentId = UUID.randomUUID();
		jdbc.update("""
				insert into payment (id, payment_no, contract_id, amount, channel, paid_at, idempotency_key,
					created_at, updated_at)
					values (?, ?, ?, ?, 'CASH', clock_timestamp(), ?, clock_timestamp(), clock_timestamp())""",
				paymentId, "PAY-CR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 18), contractId,
				amount, "credit-seed-" + UUID.randomUUID());
		UUID allocationId = jdbc.queryForObject("""
				insert into payment_allocation (payment_id, installment_id, allocation_type, amount,
					created_at, updated_at)
					values (?, (select id from installment where contract_id = ? and period_no = 1), 'EXCESS', ?,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, paymentId, contractId, amount);
		return jdbc.queryForObject("""
				insert into contract_credit (contract_id, source_payment_allocation_id, amount, status,
					created_at, updated_at)
					values (?, ?, ?, 'AVAILABLE', clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, contractId, allocationId, amount);
	}

	private MvcResult quote() throws Exception {
		return mockMvc.perform(post("/api/v1/settlements/quote")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\"}"))
				.andReturn();
	}

	private MvcResult execute(UUID quoteId, String idempotencyKey) throws Exception {
		return mockMvc.perform(post("/api/v1/settlements")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"quote_id\":\"" + quoteId + "\"}"))
				.andReturn();
	}

	private UUID quoteId(MvcResult quoteResult) throws Exception {
		assertThat(quoteResult.getResponse().getStatus()).isEqualTo(200);
		return UUID.fromString(data(quoteResult).get("quote_id").asText());
	}

	private void assertNothingSettled(UUID quoteId) {
		assertThat(jdbc.queryForObject("select count(*) from settlement where quote_id = ?", Long.class, quoteId))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from journal_entry where ref_type = 'SETTLEMENT'",
				Long.class)).isZero();
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, contractId))
				.isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("select status from settlement_quote where id = ?", String.class, quoteId))
				.isEqualTo("QUOTED");
	}

	private BigDecimal lineAmount(UUID settlementId, String account, String side) {
		String sql = "select coalesce(sum(l." + side + "),0)"
				+ " from journal_line l join journal_entry e on e.id = l.journal_entry_id"
				+ " where e.ref_type = 'SETTLEMENT' and e.ref_id = ? and l.account_code = ?";
		return jdbc.queryForObject(sql, BigDecimal.class, settlementId, account);
	}

	private long lineCount(UUID settlementId, String account) {
		String sql = "select count(*)"
				+ " from journal_line l join journal_entry e on e.id = l.journal_entry_id"
				+ " where e.ref_type = 'SETTLEMENT' and e.ref_id = ? and l.account_code = ?";
		return jdbc.queryForObject(sql, Long.class, settlementId, account);
	}

	private BigDecimal allocationTotal(UUID settlementId, String type) {
		return jdbc.queryForObject(
				"select coalesce(sum(amount),0) from settlement_allocation where settlement_id = ? and allocation_type = ?",
				BigDecimal.class, settlementId, type);
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("code").asText();
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
