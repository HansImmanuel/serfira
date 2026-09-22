package com.serfira.payment;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * C3 — {@code POST /api/v1/payments} end to end (PRD P-1–P-4, TS §2.2/§2.5/§3): the standard envelope,
 * the documented allocation waterfall, installment resolution, the payment journal, retry safety and the
 * error contract, all against a real PostgreSQL 16 and the real security chain.
 *
 * <p>The suite requests are real commits (MockMvc, no test transaction), so the deferred V3 invariant
 * triggers run exactly where they do in production — including for the payment's own allocations.
 *
 * <p>Interest is <b>only receivable once billed</b> (TS §3/§5, Addendum §12) and since C4 the payment path
 * bills the contract's due installments inside its own transaction (ADR-011), so a payment received on a
 * due date resolves interest with no seeding at all — that is PRD scenario 1. Only the daily penalty
 * accrual (story D1) is still seeded with SQL, exactly as before.
 */
@Import({TestcontainersConfiguration.class, PaymentClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiIT {

	private static final byte[] TEST_SECRET = Base64.getDecoder()
			.decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final LocalDate BUSINESS_DATE = PaymentClockTestConfiguration.BUSINESS_DATE;

	/** Fixture contract: 20,000,000 asset, 4,000,000 down payment, 12 × FLAT 1.5% (B5 default terms). */
	private static final String PERIOD_PRINCIPAL = "1333333.33";
	private static final String PERIOD_INTEREST = "240000.00";
	private static final String PERIOD_TOTAL = "1573333.33";

	/** Periods 1–7 are due on the fixture business date; period 8 (2026-09-30) is the first future one. */
	private static final int LAST_DUE_PERIOD = 7;

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
	private String token;
	private UUID contractId;

	@BeforeEach
	void seedActivatedContractAndActor() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'Payment IT Actor', 'ADMIN_OPERASIONAL', TRUE,
						clock_timestamp(), clock_timestamp())
					returning id
				""", UUID.class, "payment-it-actor-" + UUID.randomUUID());
		token = jwtFor(actorId);
		fixedClock().setDate(BUSINESS_DATE);

		ContractResponse draft = contracts.create("payment-it-contract-" + UUID.randomUUID(),
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
		// Leaves nothing behind that references this suite's actor (another suite deletes app_user rows).
		truncateDomainTables();
	}

	@Test
	void aFullPaymentOfADueInstallmentResolvesItAndPostsTheJournal() throws Exception {
		// No seeding: PRD scenario 1 — the payment itself bills the installment's due interest first, so the
		// full period amount resolves interest and principal.
		MvcResult result = pay("full-period-1", PERIOD_TOTAL);

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(payment.get("status").asText()).isEqualTo("POSTED");
		assertThat(payment.get("channel").asText()).isEqualTo("CASH");
		assertThat(payment.get("contract_id").asText()).isEqualTo(contractId.toString());
		assertThat(payment.get("payment_no").asText()).matches("PAY-202609-\\d{4}");
		// paid_at comes from the application clock, never from the request (TS §2.0).
		assertThat(OffsetDateTime.parse(payment.get("paid_at").asText())).isEqualTo(clock.now());
		assertThat(decimal(payment, "amount")).isEqualByComparingTo(PERIOD_TOTAL);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");
		// Waterfall inside one installment: interest before principal, no penalty (nothing accrued yet).
		assertThat(allocationTypes(payment)).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(payment.get("allocations").get(1), "amount")).isEqualByComparingTo(PERIOD_PRINCIPAL);
		assertThat(payment.get("allocations").get(0).get("installment_id").asText())
				.isEqualTo(installmentId(1).toString());
		// Money travels as plain decimal strings, never scientific notation (wire contract).
		assertThat(result.getResponse().getContentAsString())
				.contains("\"amount\":1573333.33")
				.contains("\"excess_amount\":0.00")
				.contains("\"allocation_type\":\"INTEREST\"");

		// The installment is resolved through the contract module, not by the payment module.
		Map<String, Object> installment = installmentRow(1);
		assertThat(decimalOf(installment, "paid_amount")).isEqualByComparingTo(PERIOD_TOTAL);
		assertThat(installment.get("status")).isEqualTo("PAID");
		assertThat(installment.get("paid_at")).isNotNull();
		// Two writes touch the row: the billing step's recognition, then the resolution of this payment.
		assertThat((Long) installment.get("version")).isEqualTo(2L);
		// Nothing else was touched: a future installment keeps its balance (PRD skenario 4 / ADR-009).
		assertThat(decimalOf(installmentRow(2), "paid_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(2).get("status")).isEqualTo("PENDING");

		// Its interest was recognized by the billing step this use case runs (C4/ADR-011): one BILLING entry
		// per billed installment, with the installment's due date as the accounting date.
		UUID billingEntryId = billingEntryOf(1);
		// The accounting date is the installment's due date at the start of the business day, not the moment
		// the step ran (DM §1.13, ADR-008); comparing instants keeps the assertion independent of the zone
		// the driver renders a timestamptz in.
		assertThat(jdbc.queryForObject("select entry_date from journal_entry where id = ?",
				OffsetDateTime.class, billingEntryId).toInstant())
				.isEqualTo(LocalDate.of(2026, 2, 28).atStartOfDay(clock.zone()).toInstant());
		assertThat(debitByAccount(billingEntryId))
				.containsOnlyKeys("PIUTANG_BUNGA")
				.containsEntry("PIUTANG_BUNGA", new BigDecimal(PERIOD_INTEREST));
		assertThat(creditByAccount(billingEntryId))
				.containsOnlyKeys("PENDAPATAN_BUNGA")
				.containsEntry("PENDAPATAN_BUNGA", new BigDecimal(PERIOD_INTEREST));

		UUID entryId = onlyPaymentEntry(UUID.fromString(payment.get("id").asText()));
		assertThat(jdbc.queryForObject("select entry_date from journal_entry where id = ?",
				OffsetDateTime.class, entryId)).isEqualTo(clock.now());
		assertThat(jdbc.queryForObject("select description from journal_entry where id = ?", String.class, entryId))
				.contains(payment.get("payment_no").asText());
		assertThat(creditByAccount(entryId))
				.containsEntry("PIUTANG_BUNGA", new BigDecimal(PERIOD_INTEREST))
				.containsEntry("PIUTANG_POKOK", new BigDecimal(PERIOD_PRINCIPAL))
				.hasSize(2);
		assertThat(debitByAccount(entryId)).containsOnlyKeys("KAS");
		assertThat(debitTotal(entryId)).isEqualByComparingTo(creditTotal(entryId));
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and contract_id <> ?", Long.class, entryId, contractId)).isZero();
	}

	@Test
	void aPartialPaymentLeavesTheInstallmentPartiallyPaid() throws Exception {
		MvcResult result = pay("partial-period-1", "500000.00");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(allocationTypes(data(result))).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(data(result), "excess_amount")).isEqualByComparingTo("0.00");

		Map<String, Object> installment = installmentRow(1);
		assertThat(decimalOf(installment, "paid_amount")).isEqualByComparingTo("500000.00");
		assertThat(installment.get("status")).isEqualTo("PARTIALLY_PAID");
		assertThat(installment.get("paid_at")).isNotNull();
	}

	@Test
	void aSecondPaymentCompletesAPartiallyPaidInstallmentAndKeepsTheFirstPaidAt() throws Exception {
		pay("first-leg", "500000.00");
		Object firstPaidAt = installmentRow(1).get("paid_at");

		MvcResult second = pay("second-leg", "1073333.33");

		assertThat(second.getResponse().getStatus()).isEqualTo(201);
		assertThat(installmentRow(1).get("status")).isEqualTo("PAID");
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo(PERIOD_TOTAL);
		// paid_at is the first resolving payment's instant, not the latest one.
		assertThat(installmentRow(1).get("paid_at")).isEqualTo(firstPaidAt);
	}

	@Test
	void anOverpaymentSettlesEveryDueInstallmentAndBooksTheRestAsCustomerCredit() throws Exception {
		// Capacity of the due window once the payment's own billing step has recognized every due period
		// (C4/ADR-011): seven periods of 1,333,333.33 principal + 240,000.00 interest = 11,013,333.31. The
		// FLAT residual lands in period 12, so periods 1–7 each carry exactly 1,333,333.33 of principal.
		String dueWindowCapacity = "11013333.31";
		String excess = "986666.69";

		MvcResult result = pay("overpayment", "12000000.00");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo(excess);
		// EXCESS is one line, carries no installment, and is always last (invariant 6, ADR-009).
		JsonNode lastAllocation = payment.get("allocations").get(payment.get("allocations").size() - 1);
		assertThat(lastAllocation.get("allocation_type").asText()).isEqualTo("EXCESS");
		assertThat(lastAllocation.get("installment_id").isNull()).isTrue();
		assertThat(decimal(lastAllocation, "amount")).isEqualByComparingTo(excess);

		for (int periodNo = 1; periodNo <= LAST_DUE_PERIOD; periodNo++) {
			assertThat(installmentRow(periodNo).get("status"))
					.as("period %s must be resolved by the due window", periodNo)
					.isEqualTo("PAID");
		}
		// PRD P-4 / skenario 4: credit never rolls into an installment that is not due yet, so the
		// installment after the window keeps its full receivable.
		assertThat(installmentRow(8).get("status")).isEqualTo("PENDING");
		assertThat(decimalOf(installmentRow(8), "paid_amount")).isEqualByComparingTo("0.00");

		// The customer credit is a liability: TITIPAN_NASABAH, not revenue (TS §3, Addendum §2).
		UUID entryId = onlyPaymentEntry(UUID.fromString(payment.get("id").asText()));
		assertThat(creditByAccount(entryId)).containsEntry("TITIPAN_NASABAH", new BigDecimal(excess));
		assertThat(creditTotal(entryId)).isEqualByComparingTo("12000000.00");
		assertThat(creditTotal(entryId).subtract(new BigDecimal(excess))).isEqualByComparingTo(dueWindowCapacity);
		// E3 owns contract_credit; C3 records the EXCESS allocation only.
		assertThat(count("contract_credit")).isZero();
		// Periods 8–12 are still unresolved, so no maturity close happens (invariant 17, ADR-011).
		assertThat(jdbc.queryForObject("select status from contract where id = ?", String.class, contractId))
				.isEqualTo("ACTIVE");
	}

	@Test
	void aPaymentBeforeTheFirstDueDateIsCreditOnlyAndTouchesNoInstallment() throws Exception {
		fixedClock().setDate(LocalDate.of(2026, 1, 15));

		MvcResult result = pay("too-early", "1000000.00");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(allocationTypes(payment)).containsExactly("EXCESS");
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("1000000.00");
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(1).get("status")).isEqualTo("PENDING");
		assertThat(creditByAccount(onlyPaymentEntry(UUID.fromString(payment.get("id").asText()))))
				.containsOnlyKeys("TITIPAN_NASABAH");
		// Payment on 2026-01-15: no due date has been reached, so the billing step recognizes nothing.
		assertThat(billingEntryCount()).isZero();
	}

	@Test
	void aLatePaymentConsumesTheAccruedPenaltyBeforeInterestAndPrincipal() throws Exception {
		accruePenalty(1, "10000.00");

		MvcResult result = pay("late-period-1", "1583333.33");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(allocationTypes(payment)).containsExactly("PENALTY", "INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo("10000.00");
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(1).get("status")).isEqualTo("PAID");
		assertThat(creditByAccount(onlyPaymentEntry(UUID.fromString(payment.get("id").asText()))))
				.containsEntry("PIUTANG_DENDA", new BigDecimal("10000.00"))
				.containsEntry("PIUTANG_BUNGA", new BigDecimal(PERIOD_INTEREST))
				.containsEntry("PIUTANG_POKOK", new BigDecimal(PERIOD_PRINCIPAL))
				.hasSize(3);
	}

	@Test
	void anIdenticalRetryReplaysTheStoredResponseAndReceivesTheMoneyOnce() throws Exception {
		MvcResult first = pay("retry-key", PERIOD_TOTAL);
		MvcResult retry = pay("retry-key", PERIOD_TOTAL);

		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		assertThat(retry.getResponse().getStatus()).isEqualTo(201);
		assertThat(data(retry).get("id").asText()).isEqualTo(data(first).get("id").asText());
		assertThat(data(retry).get("payment_no").asText()).isEqualTo(data(first).get("payment_no").asText());
		assertThat(retry.getResponse().getContentAsString())
				.isEqualTo(first.getResponse().getContentAsString());

		assertThat(count("payment")).isEqualTo(1L);
		assertThat(count("payment_allocation")).isEqualTo(2L);
		// One disbursement, one payment and the billing step's one entry per due installment — the replay
		// adds none of them a second time.
		assertThat(journalEntryCount("CONTRACT_ACTIVATION")).isEqualTo(1L);
		assertThat(journalEntryCount("PAYMENT")).isEqualTo(1L);
		assertThat(journalEntryCount("BILLING")).isEqualTo(LAST_DUE_PERIOD);
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo(PERIOD_TOTAL);
		assertThat(jdbc.queryForObject("""
				select count(*) from idempotency_keys
				where endpoint = 'POST /api/v1/payments' and status = 'COMPLETED'
				""", Long.class)).isEqualTo(1L);

		// Second distinct payment under its own key is still received normally.
		MvcResult other = pay("another-key", "100.00");
		assertThat(other.getResponse().getStatus()).isEqualTo(201);
		assertThat(count("payment")).isEqualTo(2L);
	}

	@Test
	void theSameKeyWithADifferentBodyIsRejectedAsAConflict() throws Exception {
		pay("reused-key", "100000.00");

		MvcResult conflicting = pay("reused-key", "200000.00");

		assertThat(conflicting.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(conflicting)).isEqualTo("CONFLICT");
		assertThat(count("payment")).isEqualTo(1L);
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo("100000.00");
	}

	@Test
	void aMissingOrUnusableIdempotencyKeyIsRejectedBeforeAnythingIsWritten() throws Exception {
		MvcResult missing = postPayment(null, body(contractId, "100000.00", "CASH"));
		MvcResult blank = postPayment("   ", body(contractId, "100000.00", "CASH"));
		MvcResult oversized = postPayment("k".repeat(81), body(contractId, "100000.00", "CASH"));

		assertThat(missing.getResponse().getStatus()).isEqualTo(400);
		assertThat(errorCode(missing)).isEqualTo("VALIDATION_ERROR");
		assertThat(missing.getResponse().getContentAsString()).contains("Idempotency-Key");
		assertThat(blank.getResponse().getStatus()).isEqualTo(400);
		assertThat(oversized.getResponse().getStatus()).isEqualTo(400);
		assertThat(count("payment")).isZero();
		assertThat(countPaymentClaims()).isZero();
	}

	@Test
	void invalidPayloadsAreRejectedAsValidationErrorsBeforeAnythingIsWritten() throws Exception {
		MvcResult zero = pay("zero-amount", "0");
		MvcResult negative = pay("negative-amount", "-100.00");
		MvcResult subCent = pay("sub-cent-amount", "0.001");
		MvcResult unknownChannel = postPayment("unknown-channel",
				body(contractId, "100000.00", "CHEQUE"));
		MvcResult withoutChannel = postPayment("without-channel",
				"{\"contract_id\":\"" + contractId + "\",\"amount\":100000.00}");
		MvcResult withoutContract = postPayment("without-contract",
				"{\"amount\":100000.00,\"channel\":\"CASH\"}");
		MvcResult unreadable = postPayment("unreadable-amount",
				body(contractId, "\"not-a-number\"", "CASH"));

		for (MvcResult rejected : List.of(zero, negative, subCent, unknownChannel, withoutChannel,
				withoutContract, unreadable)) {
			assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
			assertThat(errorCode(rejected)).isEqualTo("VALIDATION_ERROR");
		}
		// A sub-cent amount is refused, never rounded (TS §2.1).
		assertThat(subCent.getResponse().getContentAsString()).contains("decimal places");
		assertThat(count("payment")).isZero();
		assertThat(count("payment_allocation")).isZero();
		assertThat(count("journal_entry")).isEqualTo(1L); // the fixture's disbursement only
		// Validation runs before the claim, so a corrected retry may reuse the key.
		assertThat(countPaymentClaims()).isZero();
	}

	@Test
	void anUnknownContractIsNotFoundAndADraftContractIsAStateConflict() throws Exception {
		MvcResult unknown = postPayment("unknown-contract",
				body(UUID.randomUUID(), "100000.00", "CASH"));

		ContractResponse draft = contracts.create("draft-payment-key-" + UUID.randomUUID(),
				new CreateContractRequest(
						new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456788", "Bandung"),
						new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "NMAX", null, "D5678AB"),
						new BigDecimal("30000000.00"), new BigDecimal("6000000.00"), 12, InterestScheme.FLAT,
						new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)));
		MvcResult draftPayment = postPayment("draft-contract",
				body(draft.id(), "100000.00", "CASH"));

		assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
		assertThat(errorCode(unknown)).isEqualTo("CONTRACT_NOT_FOUND");
		assertThat(draftPayment.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(draftPayment)).isEqualTo("CONTRACT_STATE_INVALID");
		assertThat(count("payment")).isZero();
		assertThat(countPaymentClaims()).isZero();
	}

	@Test
	void anUnusableReceivableSnapshotIsRejectedAndRollsTheWholePaymentBack() throws Exception {
		// Data the engine cannot allocate against: a waiver bigger than the penalty it reduces. The
		// receivable snapshot is at fault, so the request must fail loudly and write nothing at all.
		jdbc.update("""
				insert into penalty_adjustment (installment_id, adjustment_type, amount, reason, approved_by,
					created_at, updated_at)
					values (?, 'WAIVE', 999999.00, 'IT seeded corruption', ?, clock_timestamp(), clock_timestamp())
				""", installmentId(1), actorId);

		MvcResult result = pay("corrupt-snapshot", "100000.00");

		assertThat(result.getResponse().getStatus()).isEqualTo(500);
		assertThat(errorCode(result)).isEqualTo("INTERNAL_ERROR");
		// No half-written payment: the row, its allocations, the installment resolution, the journal entry
		// and the idempotency claim all rolled back together (TS §2.3) — including the interest the use
		// case's billing step recognized before the snapshot failed.
		assertThat(count("payment")).isZero();
		assertThat(count("payment_allocation")).isZero();
		assertThat(countPaymentClaims()).isZero();
		assertThat(count("journal_entry")).isEqualTo(1L);
		assertThat(journalEntryCount("BILLING")).isZero();
		assertThat(jdbc.queryForObject("select recognized_interest_amount from installment where id = ?",
				BigDecimal.class, installmentId(1))).isEqualByComparingTo("0.00");
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(1).get("status")).isEqualTo("PENDING");
	}

	@Test
	void thePaymentIsAttributedToTheAuthenticatedActor() throws Exception {
		MvcResult result = pay("audited-payment", "100000.00");

		UUID paymentId = UUID.fromString(data(result).get("id").asText());
		assertThat(jdbc.queryForObject("select created_by from payment where id = ?", UUID.class, paymentId))
				.isEqualTo(actorId);
		assertThat(jdbc.queryForObject("select count(*) from payment_allocation where payment_id = ? "
				+ "and created_by <> ?", Long.class, paymentId, actorId)).isZero();
		assertThat(jdbc.queryForObject("select updated_by from installment where id = ?", UUID.class,
				installmentId(1))).isEqualTo(actorId);
	}

	@Test
	void unauthenticatedPaymentsAreRejectedAndPersistNothing() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/payments")
				.header("Idempotency-Key", "no-auth-payment")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(contractId, "100000.00", "CASH"))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertThat(count("payment")).isZero();
		assertThat(countPaymentClaims()).isZero();
	}

	@Test
	void payingTheLastInstallmentOfASinglePeriodContractClosesItAsMaturity() throws Exception {
		// A one-period contract whose only due date (2026-08-31) has passed: the final regular payment ends
		// it, so the same use case that resolves the installment closes the contract (invariant 17, ADR-011).
		UUID singlePeriodContract = activateContract(new CreateContractRequest(
				new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Bandung"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B9999ZZ"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 1, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 7, 31)), LocalDate.of(2026, 7, 31));

		MvcResult result = postPayment("closing-payment",
				body(singlePeriodContract, "16240000.00", "CASH"));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(decimal(data(result), "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(decimal(data(result), "amount")).isEqualByComparingTo("16240000.00");

		Map<String, Object> contract = contractRow(singlePeriodContract);
		assertThat(contract.get("status")).isEqualTo("CLOSED");
		assertThat(contract.get("closed_reason")).isEqualTo("MATURITY");
		// closed_at is the resolving event's instant: the closing payment and the resolution share it.
		assertThat(closedAtOf(singlePeriodContract).toInstant()).isEqualTo(clock.now().toInstant());
		// Closing is a state rule, not a UI flag: a closed contract refuses further money (409).
		MvcResult afterClose = postPayment("payment-after-close",
				body(singlePeriodContract, "1000.00", "CASH"));
		assertThat(afterClose.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(afterClose)).isEqualTo("CONTRACT_STATE_INVALID");
		assertThat(count("payment")).isEqualTo(1L);
	}

	private MvcResult pay(String idempotencyKey, String amount) throws Exception {
		return postPayment(idempotencyKey, body(contractId, amount, "CASH"));
	}

	/** Creates and activates a contract from an explicit request, so a test can vary its terms. */
	private UUID activateContract(CreateContractRequest request, LocalDate startDate) {
		ContractResponse draft = contracts.create("payment-it-" + UUID.randomUUID(), request);
		contracts.activate(draft.id(), startDate);
		return draft.id();
	}

	private MvcResult postPayment(String idempotencyKey, String jsonBody) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/payments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(jsonBody);
		if (idempotencyKey != null) {
			request = request.header("Idempotency-Key", idempotencyKey);
		}
		return mockMvc.perform(request).andReturn();
	}

	/** Hand-built JSON so a test can send amounts (and channels) the DTO would not accept. */
	private static String body(UUID contractId, String amountJson, String channel) {
		return "{\"contract_id\":\"" + contractId + "\",\"amount\":" + amountJson
				+ ",\"channel\":\"" + channel + "\"}";
	}

	private JsonNode data(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
	}

	private String errorCode(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("code").asText();
	}

	private List<String> allocationTypes(JsonNode payment) {
		List<String> types = new ArrayList<>();
		payment.get("allocations").forEach(allocation -> types.add(allocation.get("allocation_type").asText()));
		return types;
	}

	private static BigDecimal decimal(JsonNode node, String field) {
		return node.get(field).decimalValue();
	}

	private static BigDecimal decimalOf(Map<String, Object> row, String column) {
		return (BigDecimal) row.get(column);
	}

	private UUID installmentId(int periodNo) {
		return jdbc.queryForObject("select id from installment where contract_id = ? and period_no = ?",
				UUID.class, contractId, periodNo);
	}

	private Map<String, Object> installmentRow(int periodNo) {
		return jdbc.queryForMap("select paid_amount, status, paid_at, version from installment "
				+ "where contract_id = ? and period_no = ?", contractId, periodNo);
	}

	/** The recognition entry of a period, keyed by the installment it recognizes (C4/ADR-011). */
	private UUID billingEntryOf(int periodNo) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'BILLING' and ref_id = ?",
				UUID.class, installmentId(periodNo));
	}

	private long billingEntryCount() {
		return journalEntryCount("BILLING");
	}

	private long journalEntryCount(String refType) {
		return jdbc.queryForObject("select count(*) from journal_entry where ref_type = ?", Long.class, refType);
	}

	/** Contract state after a payment: C4 auto-closes a matured contract (invariant 17, ADR-011). */
	private Map<String, Object> contractRow(UUID contractId) {
		return jdbc.queryForMap("select status, closed_reason from contract where id = ?", contractId);
	}

	private OffsetDateTime closedAtOf(UUID contractId) {
		return jdbc.queryForObject("select closed_at from contract where id = ?", OffsetDateTime.class, contractId);
	}

	/** Test-only SQL: the daily accrual job is story D1, so a late installment's penalty is seeded here. */
	private void accruePenalty(int periodNo, String amount) {
		jdbc.update("update installment set penalty_amount = ?, status = 'OVERDUE' "
				+ "where contract_id = ? and period_no = ?", new BigDecimal(amount), contractId, periodNo);
	}

	private UUID onlyPaymentEntry(UUID paymentId) {
		List<UUID> ids = jdbc.queryForList("select id from journal_entry where ref_type = 'PAYMENT' "
				+ "and ref_id = ?", UUID.class, paymentId);
		assertThat(ids).hasSize(1);
		return ids.get(0);
	}

	private Map<String, BigDecimal> creditByAccount(UUID entryId) {
		Map<String, BigDecimal> credits = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("select account_code, credit from journal_line "
				+ "where journal_entry_id = ? and credit > 0", entryId)) {
			credits.put((String) row.get("account_code"), (BigDecimal) row.get("credit"));
		}
		return credits;
	}

	private Map<String, BigDecimal> debitByAccount(UUID entryId) {
		Map<String, BigDecimal> debits = new LinkedHashMap<>();
		for (Map<String, Object> row : jdbc.queryForList("select account_code, debit from journal_line "
				+ "where journal_entry_id = ? and debit > 0", entryId)) {
			debits.put((String) row.get("account_code"), (BigDecimal) row.get("debit"));
		}
		return debits;
	}

	private BigDecimal debitTotal(UUID entryId) {
		return jdbc.queryForObject("select sum(debit) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
	}

	private BigDecimal creditTotal(UUID entryId) {
		return jdbc.queryForObject("select sum(credit) from journal_line where journal_entry_id = ?",
				BigDecimal.class, entryId);
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	/** Claims left behind by the payment endpoint; a rolled-back request must leave none. */
	private long countPaymentClaims() {
		return jdbc.queryForObject("select count(*) from idempotency_keys where endpoint = ?",
				Long.class, "POST /api/v1/payments");
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	/** Mirrors serfira.security.jwt.secret-base64 in src/test/resources/application.properties. */
	private String jwtFor(UUID subject) {
		try {
			JWTClaimsSet claims = new JWTClaimsSet.Builder()
					.subject(subject.toString())
					.claim("roles", List.of("ADMIN_OPERASIONAL"))
					.issueTime(Date.from(Instant.now()))
					.expirationTime(Date.from(Instant.now().plusSeconds(600)))
					.build();
			SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			SecretKey key = new SecretKeySpec(TEST_SECRET, "HmacSHA256");
			JWSSigner signer = new MACSigner(key);
			signedJwt.sign(signer);
			return signedJwt.serialize();
		} catch (Exception ex) {
			throw new IllegalStateException("failed to mint the IT bearer token", ex);
		}
	}

	private void truncateDomainTables() {
		// Shared Testcontainers hygiene (same recipe as the other ITs): TRUNCATE does not fire the
		// row-level/deferred triggers, and app_user keeps the seeded SYSTEM row.
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
