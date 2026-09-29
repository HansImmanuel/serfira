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
 * due date resolves interest with no seeding at all — that is PRD scenario 1. T4 likewise recognizes
 * every due late-day penalty inside the idempotent payment transaction before taking the allocation
 * snapshot, so the HTTP scenarios exercise billing, accrual, allocation, audit, and rollback end to end.
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

	/** Only period 1 is due on the fixture business date; period 2 is the first future installment. */
	private static final int LAST_DUE_PERIOD = 1;

	private static final LocalDate WITHIN_GRACE_DATE = LocalDate.of(2026, 3, 3);
	private static final LocalDate FIRST_CHARGEABLE_DATE = LocalDate.of(2026, 3, 4);
	private static final LocalDate NEXT_CHARGEABLE_DATE = FIRST_CHARGEABLE_DATE.plusDays(1);
	private static final String FIRST_DAY_PENALTY = "1573.33";

	/** Period 1 is 31 days late, with 28 days outside its snapshotted three-day grace period. */
	private static final LocalDate LATE_BUSINESS_DATE = LocalDate.of(2026, 3, 31);

	/** 28 charged days at 1,573.33 per day (TS §4.3). */
	private static final String ACCRUED_PENALTY = "44053.24";

	/** Period 1 principal, interest, and all 28 recognized penalty days. */
	private static final String LATE_PAYMENT_TOTAL = "1617386.57";

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
		assertThat(payment.get("payment_no").asText()).matches("PAY-202602-\\d{4}");
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
		// Capacity of the only due installment after the payment's billing step recognizes its interest.
		String dueWindowCapacity = "1573333.33";
		String excess = "10426666.67";

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
		assertThat(installmentRow(2).get("status")).isEqualTo("PENDING");
		assertThat(decimalOf(installmentRow(2), "paid_amount")).isEqualByComparingTo("0.00");

		// The customer credit is a liability: TITIPAN_NASABAH, not revenue (TS §3, Addendum §2).
		UUID entryId = onlyPaymentEntry(UUID.fromString(payment.get("id").asText()));
		assertThat(creditByAccount(entryId)).containsEntry("TITIPAN_NASABAH", new BigDecimal(excess));
		assertThat(creditTotal(entryId)).isEqualByComparingTo("12000000.00");
		assertThat(creditTotal(entryId).subtract(new BigDecimal(excess))).isEqualByComparingTo(dueWindowCapacity);
		// E3 owns contract_credit; C3 records the EXCESS allocation only.
		assertThat(count("contract_credit")).isZero();
		// Periods 2–12 are still unresolved, so no maturity close happens (invariant 17, ADR-011).
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
	void aPaymentWithinTheGraceWindowAccruesNoPenaltyOrJournal() throws Exception {
		fixedClock().setDate(WITHIN_GRACE_DATE);

		MvcResult result = pay("within-penalty-grace", PERIOD_TOTAL);

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(allocationTypes(data(result))).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(data(result), "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(accrualCount(1)).isZero();
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isZero();
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("0.00");
		assertThat(installmentRow(1).get("status")).isEqualTo("PAID");
	}

	@Test
	void aLatePaymentConsumesTheAccruedPenaltyBeforeInterestAndPrincipal() throws Exception {
		fixedClock().setDate(LATE_BUSINESS_DATE);
		// The HTTP use case performs the daily servicing order itself: bill, accrue, snapshot, allocate.
		// Period 1 is 31 days late, so 28 days fall outside the three-day grace window.

		MvcResult result = pay("late-period-1", LATE_PAYMENT_TOTAL);

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(allocationTypes(payment)).containsExactly("PENALTY", "INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo(ACCRUED_PENALTY);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(1).get("status")).isEqualTo("PAID");
		assertThat(creditByAccount(onlyPaymentEntry(UUID.fromString(payment.get("id").asText()))))
				.containsEntry("PIUTANG_DENDA", new BigDecimal(ACCRUED_PENALTY))
				.containsEntry("PIUTANG_BUNGA", new BigDecimal(PERIOD_INTEREST))
				.containsEntry("PIUTANG_POKOK", new BigDecimal(PERIOD_PRINCIPAL))
				.hasSize(3);
		// One accrual row per late day, all of them keyed to period 1: 28 × 1,573.33 (TS §4.3, ADR-012).
		assertThat(jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Long.class, installmentId(1))).isEqualTo(28L);
		assertThat(jdbc.queryForObject("select sum(amount) from penalty_accrual where installment_id = ?",
				BigDecimal.class, installmentId(1))).isEqualByComparingTo(ACCRUED_PENALTY);
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(28L);
		// Period 2 falls due on this business date, so it is billed but not late: no denda yet.
		assertThat(installmentRow(2).get("status")).isEqualTo("PENDING");
		assertThat(jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Long.class, installmentId(2))).isZero();
		assertThat(journalEntryCount("BILLING")).isEqualTo(2L);
	}

	@Test
	void aLatePaymentSpansMultipleDueInstallmentsAndLeavesTheNextInstallmentUntouched() throws Exception {
		fixedClock().setDate(LATE_BUSINESS_DATE);
		BigDecimal paymentAmount = new BigDecimal("2000000.00");
		BigDecimal periodOneResolution = new BigDecimal(LATE_PAYMENT_TOTAL);
		BigDecimal periodTwoResolution = paymentAmount.subtract(periodOneResolution);
		BigDecimal periodTwoPrincipal = periodTwoResolution.subtract(new BigDecimal(PERIOD_INTEREST));
		BigDecimal totalInterest = new BigDecimal(PERIOD_INTEREST).multiply(BigDecimal.valueOf(2));
		BigDecimal totalPrincipal = new BigDecimal(PERIOD_PRINCIPAL).add(periodTwoPrincipal);

		assertThat(periodTwoResolution).isEqualByComparingTo("382613.43");
		assertThat(periodTwoPrincipal).isEqualByComparingTo("142613.43");
		assertThat(totalInterest).isEqualByComparingTo("480000.00");
		assertThat(totalPrincipal).isEqualByComparingTo("1475946.76");

		MvcResult result = pay("late-multiple-due-installments", paymentAmount.toPlainString());

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(result);
		assertThat(allocationTypes(payment))
				.containsExactly("PENALTY", "INTEREST", "PRINCIPAL", "INTEREST", "PRINCIPAL");
		assertThat(decimal(payment.get("allocations").get(0), "amount")).isEqualByComparingTo(ACCRUED_PENALTY);
		assertThat(decimal(payment.get("allocations").get(1), "amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(payment.get("allocations").get(2), "amount")).isEqualByComparingTo(PERIOD_PRINCIPAL);
		assertThat(decimal(payment.get("allocations").get(3), "amount")).isEqualByComparingTo(PERIOD_INTEREST);
		assertThat(decimal(payment.get("allocations").get(4), "amount")).isEqualByComparingTo(periodTwoPrincipal);
		UUID periodOneId = installmentId(1);
		UUID periodTwoId = installmentId(2);
		assertThat(payment.get("allocations").get(0).get("installment_id").asText())
				.isEqualTo(periodOneId.toString());
		assertThat(payment.get("allocations").get(1).get("installment_id").asText())
				.isEqualTo(periodOneId.toString());
		assertThat(payment.get("allocations").get(2).get("installment_id").asText())
				.isEqualTo(periodOneId.toString());
		assertThat(payment.get("allocations").get(3).get("installment_id").asText())
				.isEqualTo(periodTwoId.toString());
		assertThat(payment.get("allocations").get(4).get("installment_id").asText())
				.isEqualTo(periodTwoId.toString());
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");

		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo(periodOneResolution);
		assertThat(installmentRow(1).get("status")).isEqualTo("PAID");
		assertThat(decimalOf(installmentRow(2), "paid_amount")).isEqualByComparingTo(periodTwoResolution);
		assertThat(installmentRow(2).get("status")).isEqualTo("PARTIALLY_PAID");
		assertThat(decimalOf(installmentRow(3), "paid_amount")).isEqualByComparingTo("0.00");
		assertThat(installmentRow(3).get("status")).isEqualTo("PENDING");

		UUID paymentId = UUID.fromString(payment.get("id").asText());
		assertThat(jdbc.queryForObject("select count(*) from payment_allocation where payment_id = ?",
				Long.class, paymentId)).isEqualTo(5L);
		assertThat(jdbc.queryForObject("select sum(amount) from payment_allocation where payment_id = ?",
				BigDecimal.class, paymentId)).isEqualByComparingTo(paymentAmount);
		UUID paymentEntryId = onlyPaymentEntry(paymentId);
		assertThat(debitByAccount(paymentEntryId))
				.containsOnlyKeys("KAS")
				.containsEntry("KAS", paymentAmount);
		assertThat(creditByAccount(paymentEntryId))
				.containsOnlyKeys("PIUTANG_DENDA", "PIUTANG_BUNGA", "PIUTANG_POKOK")
				.containsEntry("PIUTANG_DENDA", new BigDecimal(ACCRUED_PENALTY))
				.containsEntry("PIUTANG_BUNGA", totalInterest)
				.containsEntry("PIUTANG_POKOK", totalPrincipal);
		assertThat(debitTotal(paymentEntryId)).isEqualByComparingTo(paymentAmount);
		assertThat(creditTotal(paymentEntryId)).isEqualByComparingTo(paymentAmount);
		assertThat(debitTotal(paymentEntryId)).isEqualByComparingTo(creditTotal(paymentEntryId));

		assertThat(accrualCount(1)).isEqualTo(28L);
		assertThat(jdbc.queryForObject("select sum(amount) from penalty_accrual where installment_id = ?",
				BigDecimal.class, periodOneId)).isEqualByComparingTo(ACCRUED_PENALTY);
		assertThat(accrualCount(2)).isZero();
		assertThat(penaltyAmountOf(2)).isEqualByComparingTo("0.00");
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(28L);
		assertThat(journalEntryCount("BILLING")).isEqualTo(2L);
	}

	@Test
	void firstChargeableDateAccruesOnceAllocatesPenaltyFirstAndReplayAddsNothing() throws Exception {
		fixedClock().setDate(FIRST_CHARGEABLE_DATE);

		MvcResult first = pay("first-chargeable-day", FIRST_DAY_PENALTY);

		assertThat(first.getResponse().getStatus()).isEqualTo(201);
		JsonNode payment = data(first);
		assertThat(allocationTypes(payment)).containsExactly("PENALTY");
		assertThat(decimal(payment.get("allocations").get(0), "amount"))
				.isEqualByComparingTo(FIRST_DAY_PENALTY);
		assertThat(decimal(payment, "excess_amount")).isEqualByComparingTo("0.00");

		BigDecimal positivePenaltyBase = new BigDecimal(PERIOD_PRINCIPAL)
				.add(new BigDecimal(PERIOD_INTEREST))
				.subtract(new BigDecimal(FIRST_DAY_PENALTY));
		assertThat(positivePenaltyBase).isEqualByComparingTo("1571760.00");
		assertThat(jdbc.queryForObject("""
				select principal_amount + recognized_interest_amount - paid_amount
					- settled_amount - written_off_amount
				from installment where id = ?
				""", BigDecimal.class, installmentId(1))).isEqualByComparingTo(positivePenaltyBase);
		// If lazy accrual escaped the completed-idempotency boundary, the next day would add 1,571.76.
		assertThat(positivePenaltyBase.multiply(new BigDecimal("0.0010")))
				.isEqualByComparingTo("1571.76");

		// T4 completed replay only: T5 conflict retries remain a separate write-path concern.
		fixedClock().setDate(NEXT_CHARGEABLE_DATE);
		MvcResult replay = pay("first-chargeable-day", FIRST_DAY_PENALTY);

		assertThat(replay.getResponse().getStatus()).isEqualTo(201);
		assertThat(replay.getResponse().getContentAsString()).isEqualTo(first.getResponse().getContentAsString());
		assertThat(accrualCount(1)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ? "
				+ "and accrual_date = ?", Long.class, installmentId(1), NEXT_CHARGEABLE_DATE)).isZero();
		assertThat(jdbc.queryForObject("select accrual_date from penalty_accrual where installment_id = ?",
				LocalDate.class, installmentId(1))).isEqualTo(FIRST_CHARGEABLE_DATE);
		assertThat(jdbc.queryForObject("select days_late from penalty_accrual where installment_id = ?",
				Integer.class, installmentId(1))).isEqualTo(1);
		assertThat(accrualAmountOf(1, FIRST_CHARGEABLE_DATE)).isEqualByComparingTo(FIRST_DAY_PENALTY);
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo(FIRST_DAY_PENALTY);
		assertThat(decimalOf(installmentRow(1), "paid_amount")).isEqualByComparingTo(FIRST_DAY_PENALTY);
		assertThat(installmentRow(1).get("status")).isEqualTo("PARTIALLY_PAID");
		assertThat(accrualCount(2)).isZero();

		UUID penaltyEntryId = accrualEntryOf(1, FIRST_CHARGEABLE_DATE);
		assertThat(jdbc.queryForObject("select entry_date from journal_entry where id = ?",
				OffsetDateTime.class, penaltyEntryId).toInstant())
				.isEqualTo(FIRST_CHARGEABLE_DATE.atStartOfDay(clock.zone()).toInstant());
		assertThat(debitByAccount(penaltyEntryId))
				.containsOnlyKeys("PIUTANG_DENDA")
				.containsEntry("PIUTANG_DENDA", new BigDecimal(FIRST_DAY_PENALTY));
		assertThat(creditByAccount(penaltyEntryId))
				.containsOnlyKeys("PENDAPATAN_DENDA")
				.containsEntry("PENDAPATAN_DENDA", new BigDecimal(FIRST_DAY_PENALTY));
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and contract_id <> ?", Long.class, penaltyEntryId, contractId)).isZero();

		// Payment-triggered financial writes retain the authenticated request actor, never SYSTEM.
		UUID paymentId = UUID.fromString(payment.get("id").asText());
		UUID accrualId = accrualIdOf(1, FIRST_CHARGEABLE_DATE);
		assertThat(jdbc.queryForObject("select created_by from payment where id = ?", UUID.class, paymentId))
				.isEqualTo(actorId);
		assertThat(jdbc.queryForObject("select created_by from penalty_accrual where id = ?", UUID.class, accrualId))
				.isEqualTo(actorId);
		assertThat(jdbc.queryForObject("select created_by from journal_entry where id = ?", UUID.class,
				penaltyEntryId)).isEqualTo(actorId);
		assertThat(jdbc.queryForObject("select count(*) from journal_line where journal_entry_id = ? "
				+ "and created_by <> ?", Long.class, penaltyEntryId, actorId)).isZero();

		assertThat(count("payment")).isEqualTo(1L);
		assertThat(count("payment_allocation")).isEqualTo(1L);
		assertThat(journalEntryCount("CONTRACT_ACTIVATION")).isEqualTo(1L);
		assertThat(journalEntryCount("PAYMENT")).isEqualTo(1L);
		assertThat(journalEntryCount("BILLING")).isEqualTo(1L);
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				select count(*) from idempotency_keys
				where endpoint = 'POST /api/v1/payments' and status = 'COMPLETED'
				""", Long.class)).isEqualTo(1L);
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
		fixedClock().setDate(FIRST_CHARGEABLE_DATE);
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
		// and the idempotency claim all rolled back together (TS §2.3) — including billing and the one
		// penalty day recognized before the corrupt snapshot failed.
		assertThat(count("payment")).isZero();
		assertThat(count("payment_allocation")).isZero();
		assertThat(countPaymentClaims()).isZero();
		assertThat(count("journal_entry")).isEqualTo(1L);
		assertThat(journalEntryCount("BILLING")).isZero();
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isZero();
		assertThat(count("penalty_accrual")).isZero();
		assertThat(jdbc.queryForObject("select recognized_interest_amount from installment where id = ?",
				BigDecimal.class, installmentId(1))).isEqualByComparingTo("0.00");
		assertThat(penaltyAmountOf(1)).isEqualByComparingTo("0.00");
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
	void aSinglePeriodContractClosesOnlyAfterItsFirstChargeablePenaltyIsPaid() throws Exception {
		UUID singlePeriodContract = activateContract(new CreateContractRequest(
				new CreateCustomerRequest("Siti Aminah", "3171012501900002", "08123456780", "Bandung"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Yamaha", "Mio", null, "B9999ZZ"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 1, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31)), LocalDate.of(2026, 1, 31));
		fixedClock().setDate(FIRST_CHARGEABLE_DATE);

		MvcResult penaltyPayment = postPayment("single-period-penalty",
				body(singlePeriodContract, "16240.00", "CASH"));

		assertThat(penaltyPayment.getResponse().getStatus()).isEqualTo(201);
		assertThat(allocationTypes(data(penaltyPayment))).containsExactly("PENALTY");
		assertThat(decimal(data(penaltyPayment).get("allocations").get(0), "amount"))
				.isEqualByComparingTo("16240.00");
		assertThat(contractRow(singlePeriodContract).get("status")).isEqualTo("ACTIVE");
		assertThat(count("penalty_accrual")).isEqualTo(1L);
		assertThat(journalEntryCount("PENALTY_ACCRUAL")).isEqualTo(1L);

		MvcResult closingPayment = postPayment("single-period-closing",
				body(singlePeriodContract, "16240000.00", "CASH"));

		assertThat(closingPayment.getResponse().getStatus()).isEqualTo(201);
		assertThat(decimal(data(closingPayment), "excess_amount")).isEqualByComparingTo("0.00");
		assertThat(allocationTypes(data(closingPayment))).containsExactly("INTEREST", "PRINCIPAL");
		assertThat(decimal(data(closingPayment).get("allocations").get(0), "amount"))
				.isEqualByComparingTo("240000.00");
		assertThat(decimal(data(closingPayment).get("allocations").get(1), "amount"))
				.isEqualByComparingTo("16000000.00");

		Map<String, Object> contract = contractRow(singlePeriodContract);
		assertThat(contract.get("status")).isEqualTo("CLOSED");
		assertThat(contract.get("closed_reason")).isEqualTo("MATURITY");
		assertThat(closedAtOf(singlePeriodContract).toInstant()).isEqualTo(clock.now().toInstant());
		// Closing is a state rule, not a UI flag: a closed contract refuses further money (409).
		MvcResult afterClose = postPayment("payment-after-close",
				body(singlePeriodContract, "1000.00", "CASH"));
		assertThat(afterClose.getResponse().getStatus()).isEqualTo(409);
		assertThat(errorCode(afterClose)).isEqualTo("CONTRACT_STATE_INVALID");
		assertThat(count("payment")).isEqualTo(2L);
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

	private long accrualCount(int periodNo) {
		return jdbc.queryForObject("select count(*) from penalty_accrual where installment_id = ?",
				Long.class, installmentId(periodNo));
	}

	private BigDecimal accrualAmountOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select amount from penalty_accrual where installment_id = ? and accrual_date = ?",
				BigDecimal.class, installmentId(periodNo), accrualDate);
	}

	private UUID accrualIdOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select id from penalty_accrual where installment_id = ? and accrual_date = ?",
				UUID.class, installmentId(periodNo), accrualDate);
	}

	private BigDecimal penaltyAmountOf(int periodNo) {
		return jdbc.queryForObject("select penalty_amount from installment where id = ?", BigDecimal.class,
				installmentId(periodNo));
	}

	private UUID accrualEntryOf(int periodNo, LocalDate accrualDate) {
		return jdbc.queryForObject("select id from journal_entry where ref_type = 'PENALTY_ACCRUAL' and ref_id = ?",
				UUID.class, accrualIdOf(periodNo, accrualDate));
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
