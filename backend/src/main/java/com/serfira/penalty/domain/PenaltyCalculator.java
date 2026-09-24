package com.serfira.penalty.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The pure daily-penalty calculation (02_TECH_SPEC.md §4.3, PRD D-1/D-3, Addendum §6): which days of an
 * installment are chargeable and what each of those days costs.
 *
 * <pre>
 * hariTelat(x) = max(0, x − dueDate − gracePeriodDays)
 * denda_harian = round(penaltyBase × penaltyRateDaily, HALF_EVEN, 2)   // TS §2.1
 * recognized_penalty = Σ denda_harian yang sudah diakui
 * </pre>
 *
 * <p><b>One charge per chargeable day, and eligibility is per date.</b> Everything the step has already
 * charged is in {@link PenaltyTerms#alreadyAccrued()}, so the calculation is idempotent without any state:
 * a repeated run for the same business date produces nothing, a later run produces only the new days, and a
 * day that was never chargeable (still in grace, base zero, or amount rounding away) is re-evaluated from
 * scratch instead of being sealed off by an earlier run.
 *
 * <p>Deliberately <b>not</b> a comparison against a cumulative expected total: a partial payment lowers
 * {@code penaltyBase}, and netting the already-recognized penalty against a re-derived cumulative figure
 * would silently stop charging the days that are still owed (ADR-012 decisions 3–4 and alternative 3).
 * A lower base simply makes the following days cheaper. Reductions belong to the append-only
 * {@code penalty_adjustment} flow (E5), never to this calculation.
 */
public final class PenaltyCalculator {

	/** Money is scale-2 ({@code NUMERIC(19,2)}); TS §2.1 rounding is applied when the charge is produced. */
	private static final int MONEY_SCALE = 2;

	private PenaltyCalculator() {
	}

	/**
	 * The charges a run for {@link PenaltyTerms#businessDate()} still owes: every date in
	 * {@code [dueDate + grace + 1, businessDate]} that has no accrual row yet, each carrying one day's
	 * amount, in date order.
	 *
	 * <p>Empty when there is nothing to charge — the grace window has not passed, the base is zero (nothing
	 * outstanding), the rate is zero, or every chargeable day already has its row.
	 *
	 * @param terms the installment's penalty terms and the run's business date
	 * @return immutable charges, oldest first; never a zero or negative amount
	 */
	public static List<PenaltyCharge> charges(PenaltyTerms terms) {
		Objects.requireNonNull(terms, "terms");
		BigDecimal daily = dailyCharge(terms.penaltyBase(), terms.penaltyRateDaily());
		if (daily.signum() <= 0) {
			return List.of();
		}
		List<PenaltyCharge> charges = new ArrayList<>();
		for (LocalDate date = terms.firstChargeableDate(); !date.isAfter(terms.businessDate());
				date = date.plusDays(1)) {
			if (!terms.alreadyAccrued().contains(date)) {
				charges.add(new PenaltyCharge(date, terms.daysLate(date), daily));
			}
		}
		return List.copyOf(charges);
	}

	/**
	 * One day's penalty on the base in force: {@code base × rate}, rounded to scale 2 with
	 * {@link RoundingMode#HALF_EVEN} as TS §2.1 requires. Zero means the day costs nothing — a base that is
	 * zero, or an amount that rounds away — and therefore produces no accrual row and no journal line
	 * (V1 {@code ck_penalty_accrual_amount}, {@code ck_journal_line_not_zero}).
	 *
	 * @param penaltyBase     unpaid principal + unpaid recognized interest, {@code >= 0}
	 * @param penaltyRateDaily daily rate as a decimal fraction, {@code >= 0}
	 * @return scale-2 money, {@code >= 0}
	 */
	public static BigDecimal dailyCharge(BigDecimal penaltyBase, BigDecimal penaltyRateDaily) {
		Objects.requireNonNull(penaltyBase, "penaltyBase");
		Objects.requireNonNull(penaltyRateDaily, "penaltyRateDaily");
		if (penaltyBase.signum() < 0) {
			throw new IllegalArgumentException("penaltyBase must be >= 0 but was " + penaltyBase);
		}
		if (penaltyRateDaily.signum() < 0) {
			throw new IllegalArgumentException("penaltyRateDaily must be >= 0 but was " + penaltyRateDaily);
		}
		return penaltyBase.multiply(penaltyRateDaily).setScale(MONEY_SCALE, RoundingMode.HALF_EVEN);
	}
}
