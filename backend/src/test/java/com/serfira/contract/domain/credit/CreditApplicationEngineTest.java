package com.serfira.contract.domain.credit;

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
 * E3 — credit-apply engine: the same waterfall as a payment (PENALTY → INTEREST → PRINCIPAL, oldest due
 * installment first, ADR-009), but credit that cannot be applied is left unapplied rather than turned
 * into an excess (task T14, 04_GAPS_ADDENDUM.md §2.4).
 *
 * <p>Golden numbers come from the demo contract of 04_GAPS_ADDENDUM.md §18.1 — principal 1.333.333,33 and
 * interest 240.000,00 per period. The engine reduces only recognized receivable; the per-component
 * residual is reconstructed from the installment's lumped {@code resolvedAmount} (see
 * {@link CreditAllocationInput}).
 */
class CreditApplicationEngineTest {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 4, 30);
	private static final String PRINCIPAL = "1333333.33";
	private static final String INTEREST = "240000.00";

	@Test
	void appliesPenaltyThenInterestThenPrincipalOnTheOldestDueInstallment() {
		CreditAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("1583333.33"), BUSINESS_DATE,
				List.of(overdue));

		assertThat(result.appliedTotal()).isEqualByComparingTo("1583333.33");
		assertThat(result.unapplied()).isEqualByComparingTo("0.00");
		assertThat(result.lines())
				.extracting(CreditAllocationLine::component, CreditAllocationLine::installmentRef,
						CreditAllocationLine::amount)
				.containsExactly(tuple(CreditComponent.PENALTY, ref(2), dec("10000.00")),
						tuple(CreditComponent.INTEREST, ref(2), dec(INTEREST)),
						tuple(CreditComponent.PRINCIPAL, ref(2), dec(PRINCIPAL)));
	}

	@Test
	void appliesTwoDueInstallmentsOldestFirst() {
		CreditAllocationInput march = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		CreditAllocationInput april = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "20000.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("3000000.00"), BUSINESS_DATE,
				List.of(april, march));

		assertThat(result.lines())
				.extracting(CreditAllocationLine::component, CreditAllocationLine::installmentRef,
						CreditAllocationLine::amount)
				.containsExactly(tuple(CreditComponent.PENALTY, ref(2), dec("10000.00")),
						tuple(CreditComponent.INTEREST, ref(2), dec(INTEREST)),
						tuple(CreditComponent.PRINCIPAL, ref(2), dec(PRINCIPAL)),
						tuple(CreditComponent.PENALTY, ref(3), dec("20000.00")),
						tuple(CreditComponent.INTEREST, ref(3), dec(INTEREST)),
						tuple(CreditComponent.PRINCIPAL, ref(3), dec("1156666.67")));
		assertThat(result.appliedTotal()).isEqualByComparingTo("3000000.00");
		assertThat(result.unapplied()).isEqualByComparingTo("0.00");
	}

	@Test
	void sameDueDateIsOrderedByPeriodNumber() {
		CreditAllocationInput later = installment(4, "2026-04-30", "100.00", "0.00", "0.00");
		CreditAllocationInput earlier = installment(3, "2026-04-30", "100.00", "0.00", "0.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("150.00"), BUSINESS_DATE,
				List.of(later, earlier));

		assertThat(result.lines())
				.extracting(CreditAllocationLine::installmentRef, CreditAllocationLine::amount)
				.containsExactly(tuple(ref(3), dec("100.00")), tuple(ref(4), dec("50.00")));
	}

	@Test
	void onlyRecognizedInterestIsApplicableSoUnbilledInterestStaysInTheSchedule() {
		// Only 100.000,00 of the 240.000,00 scheduled interest is recognized here.
		CreditAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, "100000.00", "0.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("1433333.33"), BUSINESS_DATE,
				List.of(due));

		assertThat(result.amountForComponent(CreditComponent.INTEREST)).isEqualByComparingTo("100000.00");
		assertThat(result.amountForComponent(CreditComponent.PRINCIPAL)).isEqualByComparingTo(PRINCIPAL);
		assertThat(result.appliedTotal()).isEqualByComparingTo("1433333.33");
		assertThat(result.unapplied()).isEqualByComparingTo("0.00");
	}

	@Test
	void partialCreditSmallerThanOneInstallmentLeavesTheRestOpen() {
		CreditAllocationInput overdue = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "10000.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("100000.00"), BUSINESS_DATE,
				List.of(overdue));

		assertThat(result.lines())
				.extracting(CreditAllocationLine::component, CreditAllocationLine::amount)
				.containsExactly(tuple(CreditComponent.PENALTY, dec("10000.00")),
						tuple(CreditComponent.INTEREST, dec("90000.00")));
		assertThat(result.appliedTotal()).isEqualByComparingTo("100000.00");
		assertThat(result.unapplied()).isEqualByComparingTo("0.00");
	}

	@Test
	void creditLargerThanTotalRecognizedReceivableLeavesAnUnappliedRemainder() {
		CreditAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		CreditAllocationInput future = installment(3, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("2000000.00"), BUSINESS_DATE,
				List.of(due, future));

		// Only the due installment (recognized receivable 1.583.333,33) can absorb; the rest is unapplied,
		// never pushed onto the future installment and never turned into a new excess.
		assertThat(result.amountForInstallment(ref(2))).isEqualByComparingTo("1583333.33");
		assertThat(result.amountForInstallment(ref(3))).isEqualByComparingTo("0.00");
		assertThat(result.appliedTotal()).isEqualByComparingTo("1583333.33");
		assertThat(result.unapplied()).isEqualByComparingTo("416666.67");
	}

	@Test
	void creditFacingOnlyNotYetDueInstallmentsAppliesNothing() {
		CreditAllocationInput future = installment(1, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("1573333.33"), BUSINESS_DATE,
				List.of(future));

		assertThat(result.lines()).isEmpty();
		assertThat(result.appliedTotal()).isEqualByComparingTo("0.00");
		assertThat(result.unapplied()).isEqualByComparingTo("1573333.33");
	}

	@Test
	void aFullyResolvedInstallmentIsSkipped() {
		CreditAllocationInput paid = fullyResolved(2, "2026-03-31", PRINCIPAL, INTEREST);
		CreditAllocationInput due = installment(3, "2026-04-30", PRINCIPAL, INTEREST, "0.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("1000000.00"), BUSINESS_DATE,
				List.of(paid, due));

		assertThat(paid.outstanding()).isEqualByComparingTo("0.00");
		assertThat(result.lines())
				.extracting(CreditAllocationLine::component, CreditAllocationLine::installmentRef)
				.containsExactly(tuple(CreditComponent.INTEREST, ref(3)), tuple(CreditComponent.PRINCIPAL, ref(3)));
	}

	@Test
	void priorPartialPaymentReducesTheResidualTheCreditSees() {
		// 260.000,00 already resolved on this installment: replaying the waterfall consumes the full penalty
		// (10.000) and interest (240.000) and 10.000 of principal, so penalty/interest residual is 0.
		CreditAllocationInput partiallyPaid = input(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00", "0.00",
				"260000.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("500000.00"), BUSINESS_DATE,
				List.of(partiallyPaid));

		assertThat(partiallyPaid.residualFor(CreditComponent.PENALTY)).isEqualByComparingTo("0.00");
		assertThat(partiallyPaid.residualFor(CreditComponent.INTEREST)).isEqualByComparingTo("0.00");
		assertThat(partiallyPaid.residualFor(CreditComponent.PRINCIPAL)).isEqualByComparingTo("1323333.33");
		assertThat(result.lines())
				.extracting(CreditAllocationLine::component, CreditAllocationLine::amount)
				.containsExactly(tuple(CreditComponent.PRINCIPAL, dec("500000.00")));
	}

	@Test
	void penaltyNetOfAdjustmentsIsTheApplicablePenalty() {
		CreditAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00", "4000.00");

		CreditAllocationResult result = CreditApplicationEngine.allocate(dec("6000.00"), BUSINESS_DATE,
				List.of(overdue));

		assertThat(overdue.effectivePenalty()).isEqualByComparingTo("6000.00");
		assertThat(result.amountForComponent(CreditComponent.PENALTY)).isEqualByComparingTo("6000.00");
		assertThat(result.unapplied()).isEqualByComparingTo("0.00");
	}

	@Test
	void inputOrderDoesNotChangeTheResult() {
		List<CreditAllocationInput> ordered = List.of(
				installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00"),
				installment(3, "2026-04-30", PRINCIPAL, INTEREST, "0.00"),
				installment(4, "2026-05-31", PRINCIPAL, INTEREST, "0.00"));
		List<CreditAllocationInput> shuffled = List.of(ordered.get(2), ordered.get(0), ordered.get(1));

		CreditAllocationResult expected = CreditApplicationEngine.allocate(dec("2000000.00"), BUSINESS_DATE, ordered);
		CreditAllocationResult actual = CreditApplicationEngine.allocate(dec("2000000.00"), BUSINESS_DATE, shuffled);

		assertThat(actual).isEqualTo(expected);
	}

	@Test
	void nonPositiveAndSubCentAmountsAreRejected() {
		List<CreditAllocationInput> due = List.of(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));

		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("0.00"), BUSINESS_DATE, due))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("creditAmount");
		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("-1.00"), BUSINESS_DATE, due))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("creditAmount");
		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("100.001"), BUSINESS_DATE, due))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void duplicatedInstallmentsAreRejected() {
		CreditAllocationInput due = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00");

		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("100.00"), BUSINESS_DATE, List.of(due, due)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("listed twice");
	}

	@Test
	void nullArgumentsAreRejected() {
		List<CreditAllocationInput> due = List.of(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));

		assertThatThrownBy(() -> CreditApplicationEngine.allocate(null, BUSINESS_DATE, due))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("100.00"), null, due))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("100.00"), BUSINESS_DATE, null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aNullInstallmentIsRejected() {
		List<CreditAllocationInput> withNull = new ArrayList<>();
		withNull.add(installment(2, "2026-03-31", PRINCIPAL, INTEREST, "0.00"));
		withNull.add(null);

		assertThatThrownBy(() -> CreditApplicationEngine.allocate(dec("100.00"), BUSINESS_DATE, withNull))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void linesAlwaysTotalTheAppliedAmountAndAppliedPlusUnappliedEqualsTheCredit() {
		CreditAllocationInput overdue = installment(2, "2026-03-31", PRINCIPAL, INTEREST, "10000.00");
		CreditAllocationInput future = installment(3, "2026-05-31", PRINCIPAL, INTEREST, "0.00");

		for (String credit : List.of("100.00", "10000.00", "1583333.33", "2000000.00", "9000000.00")) {
			CreditAllocationResult result = CreditApplicationEngine.allocate(dec(credit), BUSINESS_DATE,
					List.of(overdue, future));
			BigDecimal lineTotal = result.lines().stream()
					.map(CreditAllocationLine::amount)
					.reduce(BigDecimal.ZERO, BigDecimal::add);

			assertThat(lineTotal).isEqualByComparingTo(result.appliedTotal());
			assertThat(result.appliedTotal().add(result.unapplied())).isEqualByComparingTo(credit);
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

	/** Raw fixture: principal, recognized interest, gross penalty, adjustments, lumped resolved amount. */
	private static CreditAllocationInput input(int periodNo, String dueDate, String principal, String interest,
			String penalty, String adjustments, String resolved) {
		return new CreditAllocationInput(ref(periodNo), periodNo, date(dueDate), dec(principal), dec(interest),
				dec(penalty), dec(adjustments), dec(resolved));
	}

	/** Nothing resolved and no adjustment yet. */
	private static CreditAllocationInput installment(int periodNo, String dueDate, String principal,
			String interest, String penalty) {
		return input(periodNo, dueDate, principal, interest, penalty, "0.00", "0.00");
	}

	/** With a penalty adjustment (invariant 9 subtracts it from the gross penalty). */
	private static CreditAllocationInput installment(int periodNo, String dueDate, String principal,
			String interest, String penalty, String adjustments) {
		return input(periodNo, dueDate, principal, interest, penalty, adjustments, "0.00");
	}

	/** Fully resolved: lumped resolved equals the recognized total (principal + interest). */
	private static CreditAllocationInput fullyResolved(int periodNo, String dueDate, String principal,
			String interest) {
		BigDecimal resolved = dec(principal).add(dec(interest));
		return input(periodNo, dueDate, principal, interest, "0.00", "0.00", resolved.toPlainString());
	}
}
