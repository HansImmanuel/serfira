package com.serfira.penalty.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D1 — {@link PenaltyCalculator}, the pure daily-penalty calculation (TS §4.3, PRD D-1/D-3, Addendum §6,
 * ADR-012).
 *
 * <p>Fixtures mirror the demo contract of Addendum §18.1: principal 16,000,000 at FLAT 1.5% for 12 months,
 * so a period is 1,333,333.33 principal + 240,000.00 interest, period 1 is due 2026-02-28, and the snapshotted
 * configuration is grace 3 days with a 0.1%/day rate ({@code 0.0010}). With the interest billed, the penalty
 * base is 1,573,333.33, hence 1,573.33 per late day.
 *
 * <p>The decisive cases are the ones about <b>what happens after the base moves</b>: eligibility is per date,
 * so nothing here compares a re-derived cumulative figure with what was already recognized — a reduced base
 * makes the following days cheaper, never stops them (ADR-012 decisions 3–4, rejected alternative 3).
 */
class PenaltyCalculatorTest {

	private static final LocalDate DUE_DATE = LocalDate.of(2026, 2, 28);
	private static final int GRACE_DAYS = 3;
	private static final BigDecimal DAILY_RATE = new BigDecimal("0.0010");
	private static final BigDecimal BASE = new BigDecimal("1573333.33");
	private static final BigDecimal DAILY_CHARGE = new BigDecimal("1573.33");

	@Test
	void theGraceDaysAreFreeAndTheDayAfterIsTheFirstChargeableDay() {
		assertThat(chargesAt(LocalDate.of(2026, 3, 3))).isEmpty();

		List<PenaltyCharge> charges = chargesAt(LocalDate.of(2026, 3, 4));

		assertThat(charges).containsExactly(new PenaltyCharge(LocalDate.of(2026, 3, 4), 1, DAILY_CHARGE));
	}

	@Test
	void everyLateDayOutsideTheGraceWindowCarriesOneDayOfPenalty() {
		List<PenaltyCharge> charges = chargesAt(LocalDate.of(2026, 3, 5));

		// Addendum §18.1 demo contract A, installment 2: "telat 5 hari, denda sudah terbayar" — 5 days late
		// with grace 3 means 2 charged days (PRD scenario 2 as amended by ADR-012 decision 9).
		assertThat(charges).extracting(PenaltyCharge::accrualDate)
				.containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5));
		assertThat(charges).extracting(PenaltyCharge::daysLate).containsExactly(1, 2);
		assertThat(charges).extracting(PenaltyCharge::amount).containsExactly(DAILY_CHARGE, DAILY_CHARGE);
		assertThat(total(charges)).isEqualByComparingTo("3146.66");
	}

	@Test
	void tenDaysLateWithGraceThreeChargesSevenDays() {
		// PRD scenario 2 read through TS §4.3: hariTelat = 10 − 3.
		assertThat(chargesAt(LocalDate.of(2026, 3, 10))).hasSize(7);
	}

	@Test
	void withoutGraceEveryLateDayIsCharged() {
		PenaltyTerms terms = new PenaltyTerms(DUE_DATE, 0, DAILY_RATE, BASE, LocalDate.of(2026, 3, 10), Set.of());

		List<PenaltyCharge> charges = PenaltyCalculator.charges(terms);

		assertThat(charges).hasSize(10);
		assertThat(charges.get(0).accrualDate()).isEqualTo(LocalDate.of(2026, 3, 1));
		assertThat(charges.get(0).daysLate()).isEqualTo(1);
	}

	@Test
	void aDayThatAlreadyHasAnAccrualRowIsNeverChargedAgain() {
		PenaltyTerms repeated = termsAt(LocalDate.of(2026, 3, 5),
				Set.of(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 5)));

		assertThat(PenaltyCalculator.charges(repeated)).isEmpty();
	}

	@Test
	void aBackfillRunChargesOnlyTheDatesThatHaveNoRowYet() {
		// The window is the set of dates without a row, not "everything after the last row": a day that was
		// never charged stays chargeable (ADR-012 decision 4).
		PenaltyTerms withAHole = termsAt(LocalDate.of(2026, 3, 6), Set.of(LocalDate.of(2026, 3, 5)));

		List<PenaltyCharge> charges = PenaltyCalculator.charges(withAHole);

		assertThat(charges).extracting(PenaltyCharge::accrualDate)
				.containsExactly(LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 6));
	}

	@Test
	void aReducedBaseChargesTheCheaperDayInsteadOfStoppingTheAccrual() {
		// After a partial payment the base is 73,333.33; the day still accrues, at 73.33 — the "cumulative
		// expected total" rule this story rejected would have produced nothing here.
		PenaltyTerms afterPartialPayment = new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE,
				new BigDecimal("73333.33"), LocalDate.of(2026, 3, 6), Set.of(LocalDate.of(2026, 3, 4)));

		List<PenaltyCharge> charges = PenaltyCalculator.charges(afterPartialPayment);

		assertThat(charges).extracting(PenaltyCharge::amount)
				.containsExactly(new BigDecimal("73.33"), new BigDecimal("73.33"));
		assertThat(charges).extracting(PenaltyCharge::daysLate).containsExactly(2, 3);
	}

	@Test
	void nothingIsChargedWithoutAnOutstandingBaseOrARate() {
		PenaltyTerms paidOff = new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE, BigDecimal.ZERO,
				LocalDate.of(2026, 3, 10), Set.of());
		PenaltyTerms noRate = new PenaltyTerms(DUE_DATE, GRACE_DAYS, BigDecimal.ZERO, BASE,
				LocalDate.of(2026, 3, 10), Set.of());

		assertThat(PenaltyCalculator.charges(paidOff)).isEmpty();
		assertThat(PenaltyCalculator.charges(noRate)).isEmpty();
	}

	@Test
	void aDayWhoseChargeRoundsAwayProducesNoRowAndNoJournalLine() {
		// 4.00 × 0.0010 = 0.004 → 0.00 at scale 2; V1 forbids a zero accrual and a zero journal line.
		PenaltyTerms tinyBase = new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE, new BigDecimal("4.00"),
				LocalDate.of(2026, 3, 10), Set.of());

		assertThat(PenaltyCalculator.charges(tinyBase)).isEmpty();
	}

	@Test
	void roundingIsHalfEvenAsTheTechnicalSpecificationRequires() {
		// 10,010.00 × 0.0005 = 5.005 exactly: HALF_EVEN keeps the even 5.00, HALF_UP would produce 5.01.
		assertThat(PenaltyCalculator.dailyCharge(new BigDecimal("10010.00"), new BigDecimal("0.0005")))
				.isEqualByComparingTo("5.00");
		assertThat(PenaltyCalculator.dailyCharge(BASE, DAILY_RATE)).isEqualByComparingTo(DAILY_CHARGE);
		assertThat(PenaltyCalculator.dailyCharge(BASE, DAILY_RATE).scale()).isEqualTo(2);
	}

	@Test
	void noDayBeforeTheGraceWindowEndsIsCharged() {
		assertThat(chargesAt(DUE_DATE)).isEmpty();
		assertThat(chargesAt(LocalDate.of(2026, 2, 27))).isEmpty();
	}

	@Test
	void monthEndAndLeapYearDueDatesCountCalendarDays() {
		PenaltyTerms leapDue = new PenaltyTerms(LocalDate.of(2024, 2, 29), GRACE_DAYS, DAILY_RATE,
				new BigDecimal("1000000.00"), LocalDate.of(2024, 3, 5), Set.of());

		List<PenaltyCharge> charges = PenaltyCalculator.charges(leapDue);

		// 2024-02-29 + 3 free days → first charged day 2024-03-04, then 03-05.
		assertThat(charges).extracting(PenaltyCharge::accrualDate)
				.containsExactly(LocalDate.of(2024, 3, 4), LocalDate.of(2024, 3, 5));
	}

	@Test
	void invalidTermsAndAmountsAreRejected() {
		LocalDate date = LocalDate.of(2026, 3, 5);
		assertThatThrownBy(() -> new PenaltyTerms(DUE_DATE, -1, DAILY_RATE, BASE, date, Set.of()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("gracePeriodDays");
		assertThatThrownBy(() -> new PenaltyTerms(DUE_DATE, GRACE_DAYS, new BigDecimal("-0.0001"), BASE, date, Set.of()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penaltyRateDaily");
		assertThatThrownBy(() -> new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE, new BigDecimal("-1.00"), date,
				Set.of()))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("penaltyBase");
		assertThatThrownBy(() -> PenaltyCalculator.dailyCharge(new BigDecimal("-1.00"), DAILY_RATE))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void missingAccruedDatesMeanNothingHasBeenChargedYet() {
		PenaltyTerms withoutSet = new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE, BASE,
				LocalDate.of(2026, 3, 4), null);

		assertThat(withoutSet.alreadyAccrued()).isEmpty();
		assertThat(withoutSet.firstChargeableDate()).isEqualTo(LocalDate.of(2026, 3, 4));
		assertThat(withoutSet.daysLate(LocalDate.of(2026, 3, 4))).isEqualTo(1);
		assertThat(PenaltyCalculator.charges(withoutSet)).hasSize(1);
	}

	private static List<PenaltyCharge> chargesAt(LocalDate businessDate) {
		return PenaltyCalculator.charges(termsAt(businessDate, Set.of()));
	}

	private static PenaltyTerms termsAt(LocalDate businessDate, Set<LocalDate> alreadyAccrued) {
		return new PenaltyTerms(DUE_DATE, GRACE_DAYS, DAILY_RATE, BASE, businessDate, alreadyAccrued);
	}

	private static BigDecimal total(List<PenaltyCharge> charges) {
		return charges.stream().map(PenaltyCharge::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
	}
}
