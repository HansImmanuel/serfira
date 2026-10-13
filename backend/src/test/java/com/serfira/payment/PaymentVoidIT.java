package com.serfira.payment;

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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E4 — {@code POST /api/v1/payments/{id}/void} end to end (task T16, ADR-020): a POSTED payment is reversed
 * by posting a mirrored reversal journal, flipping the payment to VOIDED, recomputing the affected
 * installments from the remaining POSTED payments, and reversing the payment's own AVAILABLE credit — all
 * atomically and idempotently.
 *
 * <p>Real commits (MockMvc, no test transaction), so the V3/V12 deferred invariant triggers and the V18
 * all-status {@code uq_payment_idempotency} fire exactly where they do in production.
 *
 * <p>Fixture: 20,000,000 asset, 4,000,000 down payment, 12 × FLAT 1.5% (the B5 default terms shared with
 * {@code PaymentApiIT}); period total 1,573,333.33 (interest 240,000.00 + principal 1,333,333.33). The
 * business date pins period 1 due (2026-02-28).
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class PaymentVoidIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = PaymentClockTestConfiguration.BUSINESS_DATE;

	private static final String PERIOD_TOTAL = "1573333.33";
	private static final String PERIOD_INTEREST = "240000.00";
	private static final String PERIOD_PRINCIPAL = "1333333.33";

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String FINANCE = AppRole.FINANCE.name();

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
					values (?, 'test-hash', 'Void IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "void-it-actor-" + UUID.randomUUID());
		adminToken = TestJwts.forRoles(actorId, ADMIN);
		fixedClock().setDate(BUSINESS_DATE);

		ContractResponse draft = contracts.create("void-it-contract-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
						new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		contracts.activate(draft.id(), null);
		contractId = draft.id();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void voidingAPaymentReversesTheJournalDemotesTheInstallmentAndKeepsHistory() throws Exception {
		UUID paymentId = postedPayment("full-period-1", PERIOD_TOTAL);
		assertThat(installmentStatusOf(1)).isEqualTo("PAID");

		MvcResult result = voidPayment(paymentId, "void-full-period-1", "{\"reason\":\"operator error\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		JsonNode voided = data(result);
		assertThat(voided.get("status").asText()).isEqualTo("VOIDED");

		// The payment row flips to VOIDED but keeps its amount and all allocations (a reversal, not a delete).
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("VOIDED");
		assertThat(jdbc.queryForObject("select amount from payment where id = ?", BigDecimal.class, paymentId))
				.isEqualByComparingTo(PERIOD_TOTAL);
		assertThat(jdbc.queryForObject("select count(*) from payment_allocation where payment_id = ?",
				Long.class, paymentId)).isGreaterThan(0);
		assertThat(jdbc.queryForObject("select void_reason from payment where id = ?", String.class, paymentId))
				.isEqualTo("operator error");

		// Exactly one reversal journal entry: same (ref_type, ref_id) as the original PAYMENT, reversal_of_id
		// set, and the lines mirrored (Dr/Cr swapped) so the two entries net to zero per account.
		UUID originalEntry = jdbc.queryForObject(
				"select id from journal_entry where ref_type = 'PAYMENT' and ref_id = ? and reversal_of_id is null",
				UUID.class, paymentId);
		UUID reversalEntry = jdbc.queryForObject(
				"select id from journal_entry where ref_type = 'PAYMENT' and ref_id = ? and reversal_of_id is not null",
				UUID.class, paymentId);
		assertThat(jdbc.queryForObject("select reversal_of_id from journal_entry where id = ?", UUID.class,
				reversalEntry)).isEqualTo(originalEntry);
		assertBalanced(reversalEntry);
		// Each account nets to zero across the original + reversal (the two entries cancel).
		assertThat(jdbc.queryForObject("""
				select coalesce(sum(debit - credit), 0) from journal_line
				where journal_entry_id in (?, ?)
				""", BigDecimal.class, originalEntry, reversalEntry)).isEqualByComparingTo("0.00");
		assertThat(jdbc.queryForObject("""
				select count(*) from (
					select account_code, sum(debit - credit) net from journal_line
					where journal_entry_id in (?, ?)
					group by account_code having sum(debit - credit) <> 0
				) t
				""", Long.class, originalEntry, reversalEntry)).isZero();

		// The installment is recomputed from the remaining POSTED payments (now none): back to PENDING, 0 paid.
		assertThat(installmentStatusOf(1)).isEqualTo("PENDING");
		assertThat(paidAmountOf(1)).isEqualByComparingTo("0.00");
	}

	@Test
	void voidingTheFinalPaymentUndoesTheMaturityCloseAndReopensTheContract() throws Exception {
		// Pay every installment so the contract matures. Move the clock forward and pay each due period.
		payEveryInstallmentToMaturity();
		assertThat(contractStatus()).isEqualTo("CLOSED");
		assertThat(closedReason()).isEqualTo("MATURITY");

		// Void the last payment: the final installment demotes, so the MATURITY close must be undone.
		UUID lastPaymentId = jdbc.queryForObject(
				"select id from payment order by paid_at desc, id desc limit 1", UUID.class);
		MvcResult result = voidPayment(lastPaymentId, "void-last", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(contractStatus()).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("select closed_at from contract where id = ?", Object.class, contractId))
				.isNull();
		assertThat(jdbc.queryForObject("select closed_reason from contract where id = ?", Object.class, contractId))
				.isNull();
		assertThat(installmentStatusOf(12)).isNotEqualTo("PAID");
	}

	@Test
	void voidingAPaymentWithStillAvailableCreditReversesTheCredit() throws Exception {
		// An overpayment on an early date is pure excess: it books an AVAILABLE contract_credit and resolves
		// nothing, so the credit is entirely available and the void can reverse it.
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		UUID paymentId = postedPayment("overpay-credit", "1000000.00");
		assertThat(count("contract_credit")).isEqualTo(1L);
		assertThat(creditStatuses()).containsOnly("AVAILABLE");

		MvcResult result = voidPayment(paymentId, "void-overpay", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		// The AVAILABLE sub-ledger credit is removed; the money is reversed by the reversal journal's
		// TITIPAN_NASABAH debit.
		assertThat(count("contract_credit")).isZero();
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("VOIDED");
	}

	@Test
	void voidingAPaymentWhoseCreditWasAppliedIsRejectedAndWritesNothing() throws Exception {
		// Build a payment whose EXCESS credit is later applied, then try to void that payment.
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		UUID overpaymentId = postedPayment("make-credit", "2000000.00");
		assertThat(count("contract_credit")).isEqualTo(1L);

		// Move to a date where periods are due and bill them, then apply the credit to recognized receivable.
		fixedClock().setDate(LocalDate.of(2026, 3, 31));
		postedPayment("bill-periods", "50000.00");
		MvcResult apply = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1000000.00}"))
				.andReturn();
		assertThat(apply.getResponse().getStatus()).isEqualTo(200);
		long creditAppsBefore = count("contract_credit_application");
		long journalBefore = count("journal_entry");

		MvcResult result = voidPayment(overpaymentId, "void-consumed-credit", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("PAYMENT_CREDIT_CONSUMED");
		// Nothing was written: the payment stays POSTED, no reversal entry, the application is untouched.
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, overpaymentId))
				.isEqualTo("POSTED");
		assertThat(count("contract_credit_application")).isEqualTo(creditAppsBefore);
		assertThat(count("journal_entry")).isEqualTo(journalBefore);
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_id = ? and reversal_of_id is not null",
				Long.class, overpaymentId)).isZero();
	}

	@Test
	void voidingAPaymentKeepsResolutionFundedByAppliedCreditFromAnotherPayment() throws Exception {
		// 1. Book pure excess early (2026-01-15): the payment resolves nothing (no period due yet) and leaves
		//    an AVAILABLE credit of 2,000,000.
		fixedClock().setDate(LocalDate.of(2026, 1, 15));
		postedPayment("make-credit", "2000000.00");
		assertThat(count("contract_credit")).isEqualTo(1L);

		// 2. On a date where period 1 is due, make a partial payment that resolves part of installment 1.
		fixedClock().setDate(LocalDate.of(2026, 2, 28));
		UUID partialPaymentId = postedPayment("partial-period-1", "500000.00");
		assertThat(installmentStatusOf(1)).isEqualTo("PARTIALLY_PAID");
		BigDecimal paidAfterPartial = paidAmountOf(1);

		// 3. Apply 300,000 of the credit to installment 1's remaining recognized receivable.
		MvcResult apply = mockMvc.perform(post("/api/v1/contracts/" + contractId + "/credit/apply")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":300000.00}"))
				.andReturn();
		assertThat(apply.getResponse().getStatus()).isEqualTo(200);
		BigDecimal paidAfterCredit = paidAmountOf(1);
		assertThat(paidAfterCredit).isGreaterThan(paidAfterPartial);
		BigDecimal creditFunded = paidAfterCredit.subtract(paidAfterPartial);

		// 4. Void the partial payment. Installment 1 must keep the credit-funded portion — it is not dropped
		//    to zero — because the surviving contract_credit_application still resolves it (ADR-020 D1.3).
		MvcResult result = voidPayment(partialPaymentId, "void-partial", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(paidAmountOf(1)).isEqualByComparingTo(creditFunded);
		assertThat(installmentStatusOf(1)).isNotEqualTo("PENDING");
		// The applied credit and its row are untouched by the void.
		assertThat(count("contract_credit_application")).isEqualTo(1L);
	}

	@Test
	void voidingAPaymentOfASettledInstallmentIsRejectedAsNotVoidable() throws Exception {
		UUID paymentId = postedPayment("to-be-settled", "500000.00");
		// Simulate a settlement having since settled the installment the payment resolved: mark it SETTLED
		// directly (the real settlement path is E2; here we only need the terminal state the guard rejects).
		UUID installmentId = installmentId(1);
		jdbc.update("""
				update installment set status = 'SETTLED', settled_amount = paid_amount, settled_at = clock_timestamp(),
					updated_at = clock_timestamp()
				where id = ?""", installmentId);

		MvcResult result = voidPayment(paymentId, "void-settled", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("PAYMENT_NOT_VOIDABLE");
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("POSTED");
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_id = ? and reversal_of_id is not null",
				Long.class, paymentId)).isZero();
	}

	@Test
	void voidingAPaymentOnANonActiveContractIsRejectedAsNotVoidable() throws Exception {
		UUID paymentId = postedPayment("on-closed-contract", "500000.00");
		// Close the contract (as a settlement would). The void guard must reject because it is not ACTIVE.
		jdbc.update("""
				update contract set status = 'CLOSED', closed_reason = 'SETTLEMENT', closed_at = clock_timestamp(),
					updated_at = clock_timestamp()
				where id = ?""", contractId);

		MvcResult result = voidPayment(paymentId, "void-on-closed", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("PAYMENT_NOT_VOIDABLE");
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("POSTED");
	}

	@Test
	void anIdenticalVoidRetryReplaysTheStoredResponseAndDoesNotReverseTwice() throws Exception {
		UUID paymentId = postedPayment("full-period-1", PERIOD_TOTAL);

		MvcResult first = voidPayment(paymentId, "void-key-replay", "{}");
		MvcResult replay = voidPayment(paymentId, "void-key-replay", "{}");

		assertThat(first.getResponse().getStatus()).isEqualTo(200);
		assertThat(replay.getResponse().getStatus()).isEqualTo(200);
		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		// Exactly one reversal entry — the replay did not post a second one.
		assertThat(jdbc.queryForObject(
				"select count(*) from journal_entry where ref_id = ? and reversal_of_id is not null",
				Long.class, paymentId)).isEqualTo(1L);
	}

	@Test
	void aReusedVoidKeyWithADifferentPaymentIsAConflict() throws Exception {
		UUID firstPaymentId = postedPayment("p1", "500000.00");
		UUID secondPaymentId = postedPayment("p2", "500000.00");

		MvcResult first = voidPayment(firstPaymentId, "shared-void-key", "{}");
		MvcResult conflicting = voidPayment(secondPaymentId, "shared-void-key", "{}");

		assertThat(first.getResponse().getStatus()).isEqualTo(200);
		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("CONFLICT");
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, secondPaymentId))
				.isEqualTo("POSTED");
	}

	@Test
	void aMissingIdempotencyKeyIsRejectedBeforeAnythingIsWritten() throws Exception {
		UUID paymentId = postedPayment("full-period-1", PERIOD_TOTAL);

		MvcResult result = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(result)).isEqualTo("VALIDATION_ERROR");
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("POSTED");
	}

	@Test
	void voidingAnUnknownPaymentIsNotFound() throws Exception {
		MvcResult result = voidPayment(UUID.randomUUID(), "void-unknown", "{}");

		assertThat(result.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(result)).isEqualTo("PAYMENT_NOT_FOUND");
	}

	@Test
	void theAllStatusIdempotencyIndexRejectsASecondPaymentReusingAVoidedPaymentsKeyViaRawSql() throws Exception {
		// The V18 index is unique across ALL statuses, so a VOIDED payment's key stays spent even by raw SQL.
		UUID paymentId = postedPayment("stays-spent", PERIOD_TOTAL);
		voidPayment(paymentId, "void-stays-spent", "{}");
		String spentKey = jdbc.queryForObject("select idempotency_key from payment where id = ?", String.class,
				paymentId);

		assertThatThrownBy(() -> jdbc.update("""
				insert into payment (payment_no, contract_id, amount, channel, paid_at, status, idempotency_key,
					created_at, updated_at)
					values (?, ?, 1.00, 'CASH', clock_timestamp(), 'POSTED', ?, clock_timestamp(), clock_timestamp())
				""", "PAY-RAW-0001", contractId, spentKey))
				.isInstanceOf(DataAccessException.class);
	}

	@Test
	void voidIsForbiddenForFinance() throws Exception {
		UUID paymentId = postedPayment("rbac-void", PERIOD_TOTAL);
		String financeToken = TestJwts.forRoles(actorId, FINANCE);

		MvcResult result = mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + financeToken)
						.header("Idempotency-Key", "finance-void")
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(403);
		assertThat(errorCode(result)).isEqualTo("FORBIDDEN");
		assertThat(jdbc.queryForObject("select status from payment where id = ?", String.class, paymentId))
				.isEqualTo("POSTED");
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private UUID postedPayment(String idempotencyKey, String amount) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/payments")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"contract_id\":\"" + contractId + "\",\"amount\":" + amount
								+ ",\"channel\":\"CASH\"}"))
				.andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(data(result).get("id").asText());
	}

	private MvcResult voidPayment(UUID paymentId, String idempotencyKey, String body) throws Exception {
		return mockMvc.perform(post("/api/v1/payments/" + paymentId + "/void")
						.header("Authorization", "Bearer " + adminToken)
						.header("Idempotency-Key", idempotencyKey)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn();
	}

	/** Pays each installment in full, walking the clock to each due date, until the contract matures. */
	private void payEveryInstallmentToMaturity() throws Exception {
		List<LocalDate> dueDates = jdbc.queryForList(
				"select due_date from installment where contract_id = ? order by period_no", LocalDate.class,
				contractId);
		int period = 1;
		for (LocalDate dueDate : dueDates) {
			fixedClock().setDate(dueDate);
			// Pay the full recognized total for the period (billed interest + principal). Each payment bills
			// its own due interest first, so the full period amount resolves.
			postedPayment("maturity-pay-" + period, PERIOD_TOTAL);
			period++;
		}
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("error").get("code").asText();
	}

	private UUID installmentId(int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private String installmentStatusOf(int periodNo) {
		return jdbc.queryForObject("select status from installment where contract_id = ? and period_no = ?",
				String.class, contractId, periodNo);
	}

	private BigDecimal paidAmountOf(int periodNo) {
		return jdbc.queryForObject("select paid_amount from installment where contract_id = ? and period_no = ?",
				BigDecimal.class, contractId, periodNo);
	}

	private String contractStatus() {
		return jdbc.queryForObject("select status from contract where id = ?", String.class, contractId);
	}

	private String closedReason() {
		return jdbc.queryForObject("select closed_reason from contract where id = ?", String.class, contractId);
	}

	private List<String> creditStatuses() {
		return jdbc.queryForList("select status from contract_credit where contract_id = ?", String.class,
				contractId);
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private void assertBalanced(UUID entryId) {
		BigDecimal debit = jdbc.queryForObject(
				"select coalesce(sum(debit), 0) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
		BigDecimal credit = jdbc.queryForObject(
				"select coalesce(sum(credit), 0) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
		assertThat(debit).isEqualByComparingTo(credit);
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
