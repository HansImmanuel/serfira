package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * C2 — allocation engine: denda → bunga → pokok on the oldest due installment first (PRD §3, P-2/P-3,
 * 03_DOMAIN_MODEL.md §1.8, ADR-009).
 *
 * <p>Golden numbers come from the demo contract of 04_GAPS_ADDENDUM.md §18.1 — principal 1.333.333,33 and
 * interest 240.000,00 per period (total 1.573.333,33). "Recognized" amounts are the ones the billing step
 * (story C4) writes; until it exists the caller seeds them, which is exactly why the engine allocates only
 * what is recognized and never the scheduled-but-unbilled interest.
 */
class PaymentAllocationEngineTest {

	private static final LocalDate PAYMENT_DATE = LocalDate.of(2026, 4, 30);
	private static final String PRINCIPAL = "1333333.33";
	private static final String INTEREST = "240000.00";

	private final PaymentAllocationEngine engine = new PaymentAllocationEngine();

	@Test
	void exactPaymentOnTheDueDateResolvesPenaltyThenInterestThenPrincipal() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");

		AllocationResult result = engine.allocate(dec("1583333.33"), PAYMENT_DATE, List.of(overdue));

		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
		assertThat(result.totalAllocated()).isEqualByComparingTo("1583333.33");
		assertThat(result.lines())
				.extracting(AllocationLine::type, AllocationLine::installmentRef, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, ref(2), dec("10000.00")),
						tuple(AllocationType.INTEREST, ref(2), dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, ref(2), dec(PRINCIPAL)));
	}

	@Test
	void paymentEqualToTheTotalDueProducesNoExcessLine() {
		InstallmentAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("1573333.33"), PAYMENT_DATE, List.of(due));

		assertThat(result.lines()).hasSize(2);
		assertThat(result.lines()).noneMatch(line -> line.type() == AllocationType.EXCESS);
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void scheduledInterestThatIsNotRecognizedYetCannotBeAllocated() {
		// The customer pays the nominal installment, but this period's interest has not been billed yet
		// (story C4), so only the principal is receivable and the interest part stays as credit (ADR-009).
		InstallmentAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, "0.00", "0.00");

		AllocationResult result = engine.allocate(dec("1573333.33"), PAYMENT_DATE, List.of(due));

		assertThat(result.lines()).extracting(AllocationLine::type)
				.containsExactly(AllocationType.PRINCIPAL, AllocationType.EXCESS);
		assertThat(result.amountFor(ref(2), AllocationType.PRINCIPAL)).isEqualByComparingTo(PRINCIPAL);
		assertThat(result.excessAmount()).isEqualByComparingTo(INTEREST);
	}

	@Test
	void partialPaymentLeavesTheRemainingReceivableOpen() {
		InstallmentAllocationInput overdue = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "10000.00");

		AllocationResult result = engine.allocate(dec("100000.00"), PAYMENT_DATE, List.of(overdue));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, dec("10000.00")),
						tuple(AllocationType.INTEREST, dec("90000.00")));
		assertThat(remainingAfter(overdue, result)).isEqualByComparingTo("1483333.33");
	}

	@Test
	void overpaymentBecomesOneExcessLineAndNeverTouchesAFutureInstallment() {
		InstallmentAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		InstallmentAllocationInput future = installment(3, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("2000000.00"), PAYMENT_DATE, List.of(due, future));

		assertThat(result.lines().stream().filter(line -> line.type() == AllocationType.EXCESS)).hasSize(1);
		assertThat(result.excessAmount()).isEqualByComparingTo("416666.67");
		assertThat(result.amountForInstallment(ref(2))).isEqualByComparingTo("1583333.33");
		assertThat(result.linesForInstallment(ref(3))).isEmpty();
		assertThat(future.allocationCapacity()).isEqualByComparingTo("1573333.33");
	}

	@Test
	void penaltyIsPaidBeforePrincipal() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", "1000.00", "0.00", "5000.00");

		AllocationResult result = engine.allocate(dec("2000.00"), PAYMENT_DATE, List.of(overdue));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, dec("2000.00")));
	}

	@Test
	void penaltyHeadroomIsGrossPenaltyMinusAdjustments() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00",
				"4000.00");

		AllocationResult result = engine.allocate(dec("6000.00"), PAYMENT_DATE, List.of(overdue));

		assertThat(overdue.effectivePenalty()).isEqualByComparingTo("6000.00");
		assertThat(overdue.penaltyHeadroom()).isEqualByComparingTo("6000.00");
		assertThat(result.amountFor(ref(2), AllocationType.PENALTY)).isEqualByComparingTo("6000.00");
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, dec("6000.00")));
	}

	@Test
	void aPaymentSmallerThanThePenaltyTouchesNothingElse() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");

		AllocationResult result = engine.allocate(dec("4000.00"), PAYMENT_DATE, List.of(overdue));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, dec("4000.00")));
		assertThat(overdue.penaltyHeadroom()).isEqualByComparingTo("10000.00");
		assertThat(overdue.penaltyHeadroom().subtract(result.amountFor(ref(2), AllocationType.PENALTY)))
				.isEqualByComparingTo("6000.00");
	}

	@Test
	void anEarlierPaymentReducesTheHeadroomOfTheNextOne() {
		// Second payment on the same installment: the first one already cleared the penalty and half the interest.
		InstallmentAllocationInput partiallyPaid = input(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00", "0.00",
				"10000.00", "120000.00", "0.00", "0.00", "0.00");

		AllocationResult result = engine.allocate(dec("200000.00"), PAYMENT_DATE, List.of(partiallyPaid));

		assertThat(partiallyPaid.penaltyHeadroom()).isEqualByComparingTo("0.00");
		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.INTEREST, dec("120000.00")),
						tuple(AllocationType.PRINCIPAL, dec("80000.00")));
	}

	@Test
	void twoOverdueInstallmentsAreResolvedOldestFirst() {
		InstallmentAllocationInput march = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		InstallmentAllocationInput april = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "20000.00");

		AllocationResult result = engine.allocate(dec("3000000.00"), PAYMENT_DATE, List.of(march, april));

		assertThat(result.lines())
				.extracting(AllocationLine::type, AllocationLine::installmentRef, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, ref(2), dec("10000.00")),
						tuple(AllocationType.INTEREST, ref(2), dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, ref(2), dec(PRINCIPAL)),
						tuple(AllocationType.PENALTY, ref(3), dec("20000.00")),
						tuple(AllocationType.INTEREST, ref(3), dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, ref(3), dec("1156666.67")));
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
		assertThat(result.totalAllocated()).isEqualByComparingTo("3000000.00");
	}

	@Test
	void aPaymentThatDoesNotCoverTheOldestObligationStopsInsideIt() {
		InstallmentAllocationInput march = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		InstallmentAllocationInput april = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "20000.00");

		AllocationResult result = engine.allocate(dec("300000.00"), PAYMENT_DATE, List.of(april, march));

		assertThat(result.lines())
				.extracting(AllocationLine::type, AllocationLine::installmentRef, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PENALTY, ref(2), dec("10000.00")),
						tuple(AllocationType.INTEREST, ref(2), dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, ref(2), dec("50000.00")));
		assertThat(result.linesForInstallment(ref(3))).isEmpty();
	}

	@Test
	void installmentsWithTheSameDueDateAreOrderedByPeriodNumber() {
		InstallmentAllocationInput later = installment(4, "2026-04-30", "100.00", "0.00", "0.00");
		InstallmentAllocationInput earlier = installment(3, "2026-04-30", "100.00", "0.00", "0.00");

		AllocationResult result = engine.allocate(dec("150.00"), PAYMENT_DATE, List.of(later, earlier));

		assertThat(result.lines()).extracting(AllocationLine::installmentRef, AllocationLine::amount)
				.containsExactly(tuple(ref(3), dec("100.00")), tuple(ref(4), dec("50.00")));
	}

	@Test
	void inputOrderDoesNotChangeTheResult() {
		List<InstallmentAllocationInput> ordered = List.of(
				installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00"),
				installment(3, "2026-04-30", PRINCIPAL, INTEREST, "0.00"),
				installment(4, "2026-05-31", PRINCIPAL, INTEREST, "0.00"));
		List<InstallmentAllocationInput> shuffled = List.of(ordered.get(2), ordered.get(0), ordered.get(1));

		AllocationResult expected = engine.allocate(dec("2000000.00"), PAYMENT_DATE, ordered);
		AllocationResult actual = engine.allocate(dec("2000000.00"), PAYMENT_DATE, shuffled);

		assertThat(actual).isEqualTo(expected);
		assertThat(actual.excessAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void anInstallmentThatIsAlreadyFullyPaidIsSkipped() {
		InstallmentAllocationInput paid = fullyPaid(2, "2026-03-31", PRINCIPAL, INTEREST);
		InstallmentAllocationInput due = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("1000000.00"), PAYMENT_DATE, List.of(paid, due));

		assertThat(paid.allocationCapacity()).isEqualByComparingTo("0.00");
		assertThat(paid.hasCapacity()).isFalse();
		assertThat(result.lines())
				.extracting(AllocationLine::type, AllocationLine::installmentRef, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.INTEREST, ref(3), dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, ref(3), dec("760000.00")));
	}

	@Test
	void aSettledInstallmentIsSkippedAndItsSettledAmountConsumesTheReceivable() {
		InstallmentAllocationInput settled = input(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00", "0.00", "0.00",
				"0.00", "0.00", "1573333.33", "0.00");
		InstallmentAllocationInput due = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("500000.00"), PAYMENT_DATE, List.of(settled, due));

		assertThat(settled.allocationCapacity()).isEqualByComparingTo("0.00");
		assertThat(result.linesForInstallment(ref(2))).isEmpty();
		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::installmentRef)
				.containsExactly(tuple(AllocationType.INTEREST, ref(3)), tuple(AllocationType.PRINCIPAL, ref(3)));
	}

	@Test
	void aWrittenOffInstallmentIsSkipped() {
		InstallmentAllocationInput writtenOff = input(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00", "0.00", "0.00",
				"0.00", "0.00", "0.00", "1573333.33");

		AllocationResult result = engine.allocate(dec("200000.00"), PAYMENT_DATE, List.of(writtenOff));

		assertThat(writtenOff.hasCapacity()).isFalse();
		assertThat(result.lines()).extracting(AllocationLine::type).containsExactly(AllocationType.EXCESS);
		assertThat(result.excessAmount()).isEqualByComparingTo("200000.00");
	}

	@Test
	void aPartialSettlementReducesTheCapacityOfTheNextPayment() {
		InstallmentAllocationInput partiallySettled = input(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00", "0.00",
				"0.00", "0.00", "0.00", "1000000.00", "0.00");

		AllocationResult result = engine.allocate(dec("700000.00"), PAYMENT_DATE, List.of(partiallySettled));

		assertThat(partiallySettled.allocationCapacity()).isEqualByComparingTo("573333.33");
		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.INTEREST, dec("240000.00")),
						tuple(AllocationType.PRINCIPAL, dec("333333.33")),
						tuple(AllocationType.EXCESS, dec("126666.67")));
	}

	@Test
	void interestIsCappedAtTheRecognizedAmountSoUnbilledInterestStaysInTheSchedule() {
		// The demo contract bills 240.000,00 of interest per period; only 100.000,00 is recognized here.
		InstallmentAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, "100000.00", "0.00");

		AllocationResult result = engine.allocate(dec("1433333.33"), PAYMENT_DATE, List.of(due));

		assertThat(result.amountFor(ref(2), AllocationType.INTEREST)).isEqualByComparingTo("100000.00");
		assertThat(result.amountFor(ref(2), AllocationType.PRINCIPAL)).isEqualByComparingTo(PRINCIPAL);
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void principalIsCappedAtTheScheduledPrincipal() {
		InstallmentAllocationInput due = installment(2, "2026-03-31", "1000.00", "0.00", "0.00");

		AllocationResult result = engine.allocate(dec("5000.00"), PAYMENT_DATE, List.of(due));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PRINCIPAL, dec("1000.00")),
						tuple(AllocationType.EXCESS, dec("4000.00")));
	}

	@Test
	void aPaymentBeforeTheFirstDueDateIsEntirelyExcess() {
		// Documented consequence of ADR-009 decision 1: an early regular payment is customer credit until the
		// installment is due (and its interest recognized), never a prepayment of the next installment.
		InstallmentAllocationInput future = installment(1, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("1573333.33"), PAYMENT_DATE, List.of(future));

		assertThat(result.lines()).extracting(AllocationLine::type).containsExactly(AllocationType.EXCESS);
		assertThat(result.excessAmount()).isEqualByComparingTo("1573333.33");
		assertThat(result.linesForInstallment(ref(1))).isEmpty();
		assertThat(future.allocationCapacity()).isEqualByComparingTo("1573333.33");
	}

	@Test
	void anInstallmentIsAllocatableOnItsDueDate() {
		InstallmentAllocationInput dueToday = installment(2, "2026-04-30", "1000.00", "0.00", "0.00");

		AllocationResult result = engine.allocate(dec("1000.00"), PAYMENT_DATE, List.of(dueToday));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.PRINCIPAL, dec("1000.00")));
	}

	@Test
	void aPaymentAgainstAFullyResolvedContractIsEntirelyExcess() {
		InstallmentAllocationInput paid = fullyPaid(2, "2026-03-31", PRINCIPAL, INTEREST);
		InstallmentAllocationInput paidLater = fullyPaid(3, "2026-04-30", PRINCIPAL, INTEREST);

		AllocationResult result = engine.allocate(dec("500000.00"), PAYMENT_DATE, List.of(paid, paidLater));

		assertThat(result.lines()).extracting(AllocationLine::type).containsExactly(AllocationType.EXCESS);
		assertThat(result.excessAmount()).isEqualByComparingTo("500000.00");
	}

	@Test
	void aContractWithoutDueInstallmentsGetsOneExcessLine() {
		// The engine only sees numbers: a DRAFT contract (no schedule) must be rejected by the caller
		// (story C3), not silently turned into credit — ADR-009 decision 8.
		AllocationResult result = engine.allocate(dec("100.00"), PAYMENT_DATE, List.of());

		assertThat(result.lines()).extracting(AllocationLine::type).containsExactly(AllocationType.EXCESS);
		assertThat(result.excessAmount()).isEqualByComparingTo("100.00");
	}

	@Test
	void aSinglePeriodContractResolvesInOnePayment() {
		InstallmentAllocationInput only = installment(1, "2026-03-31", "8000000.00", "120000.00", "0.00");

		AllocationResult result = engine.allocate(dec("8120000.00"), PAYMENT_DATE, List.of(only));

		assertThat(result.lines()).extracting(AllocationLine::type, AllocationLine::amount)
				.containsExactly(tuple(AllocationType.INTEREST, dec("120000.00")),
						tuple(AllocationType.PRINCIPAL, dec("8000000.00")));
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void theFinalInstallmentsResidualCentIsAllocatedExactly() {
		// The last period absorbs the schedule's rounding residual (DM §4), so its principal is not a round number.
		InstallmentAllocationInput last = installment(12, "2027-01-31", "1333333.37", INTEREST, "0.00");

		AllocationResult result = engine.allocate(dec("1573333.37"), LocalDate.of(2027, 1, 31), List.of(last));

		assertThat(result.amountFor(ref(12), AllocationType.PRINCIPAL)).isEqualByComparingTo("1333333.37");
		assertThat(result.amountFor(ref(12), AllocationType.INTEREST)).isEqualByComparingTo(INTEREST);
		assertThat(result.excessAmount()).isEqualByComparingTo("0.00");
	}

	@Test
	void everyLineAmountCarriesScaleTwoWithoutRoundingDrift() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");

		AllocationResult result = engine.allocate(dec("1583333.33"), PAYMENT_DATE, List.of(overdue));

		assertThat(result.lines()).allSatisfy(line -> assertThat(line.amount().scale()).isEqualTo(2));
		assertThat(result.lines()).extracting(AllocationLine::amount)
				.containsExactly(dec("10000.00"), dec(INTEREST), dec(PRINCIPAL));
	}

	@Test
	void nonPositiveAndSubCentAmountsAreRejected() {
		List<InstallmentAllocationInput> due = List.of(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));

		assertThatThrownBy(() -> engine.allocate(dec("0.00"), PAYMENT_DATE, due))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("paymentAmount");
		assertThatThrownBy(() -> engine.allocate(dec("-1.00"), PAYMENT_DATE, due))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("paymentAmount");
		assertThatThrownBy(() -> engine.allocate(dec("100.001"), PAYMENT_DATE, due))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void duplicatedInstallmentsAreRejected() {
		InstallmentAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00");
		InstallmentAllocationInput samePeriodOtherRow = withRef(2, "2026-03-31", ref(99));

		assertThatThrownBy(() -> engine.allocate(dec("100.00"), PAYMENT_DATE, List.of(due, due)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("listed twice");
		assertThatThrownBy(() -> engine.allocate(dec("100.00"), PAYMENT_DATE, List.of(due, samePeriodOtherRow)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("listed twice");
	}

	@Test
	void nullArgumentsAreRejected() {
		List<InstallmentAllocationInput> due = List.of(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));

		assertThatThrownBy(() -> engine.allocate(null, PAYMENT_DATE, due)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> engine.allocate(dec("100.00"), null, due))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> engine.allocate(dec("100.00"), PAYMENT_DATE, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aNullInstallmentIsRejected() {
		List<InstallmentAllocationInput> withNull = new ArrayList<>();
		withNull.add(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));
		withNull.add(null);

		assertThatThrownBy(() -> engine.allocate(dec("100.00"), PAYMENT_DATE, withNull))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void linesAlwaysTotalThePaymentAmountIncludingTheExcess() {
		InstallmentAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		InstallmentAllocationInput future = installment(3, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		for (String payment : List.of("100.00", "10000.00", "1583333.33", "2000000.00", "9000000.00")) {
			AllocationResult result = engine.allocate(dec(payment), PAYMENT_DATE, List.of(overdue, future));
			BigDecimal lineTotal = result.lines().stream()
					.map(AllocationLine::amount)
					.reduce(BigDecimal.ZERO, BigDecimal::add);

			assertThat(lineTotal).isEqualByComparingTo(payment);
			assertThat(result.totalAllocated()).isEqualByComparingTo(payment);
		}
	}

	private static UUID ref(int periodNo) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(periodNo));
	}

	private static BigDecimal dec(String value) {
		return new BigDecimal(value);
	}

	private static LocalDate date(String value) {
		return LocalDate.parse(value);
	}

	/** Raw fixture: every money column spelled out, used by the resolution-state scenarios. */
	private static InstallmentAllocationInput input(int periodNo, String dueDate, String principal, String interest,
			String penalty, String adjustments, String allocatedPenalty, String allocatedInterest,
			String allocatedPrincipal, String settled, String writtenOff) {
		return new InstallmentAllocationInput(ref(periodNo), periodNo, date(dueDate), dec(principal), dec(interest),
				dec(penalty), dec(adjustments), dec(allocatedPenalty), dec(allocatedInterest),
				dec(allocatedPrincipal), dec(settled), dec(writtenOff));
	}

	/** Installment with nothing allocated and no adjustment yet. */
	private static InstallmentAllocationInput installment(int periodNo, String dueDate, String principal,
			String interest, String penalty) {
		return input(periodNo, dueDate, principal, interest, penalty, "0.00", "0.00", "0.00", "0.00", "0.00", "0.00");
	}

	/** Same, with a penalty adjustment (story E5 subtracts it from the gross penalty). */
	private static InstallmentAllocationInput installment(int periodNo, String dueDate, String principal,
			String interest, String penalty, String adjustments) {
		return input(periodNo, dueDate, principal, interest, penalty, adjustments, "0.00", "0.00", "0.00", "0.00",
				"0.00");
	}

	/** Fully resolved by payment: allocations exactly at the component caps. */
	private static InstallmentAllocationInput fullyPaid(int periodNo, String dueDate, String principal,
			String interest) {
		return input(periodNo, dueDate, principal, interest, "0.00", "0.00", "0.00", interest, principal, "0.00",
				"0.00");
	}

	/** Same period as another fixture but under a distinct reference (duplicate-period guard). */
	private static InstallmentAllocationInput withRef(int periodNo, String dueDate, UUID installmentRef) {
		return new InstallmentAllocationInput(installmentRef, periodNo, date(dueDate), dec("100.00"), dec("0.00"),
				dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"), dec("0.00"));
	}

	/** Outstanding receivable left after a payment — invariant 3 in readable form. */
	private static BigDecimal remainingAfter(InstallmentAllocationInput installment, AllocationResult result) {
		return installment.allocationCapacity().subtract(result.amountForInstallment(installment.installmentRef()));
	}
}
