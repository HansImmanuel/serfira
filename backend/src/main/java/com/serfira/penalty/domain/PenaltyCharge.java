package com.serfira.penalty.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One day of recognized late-payment penalty for one installment (03_DOMAIN_MODEL.md §1.9): the day the
 * penalty is due ({@code accrual_date}), how late that day was beyond the grace period ({@code days_late}),
 * and the incremental amount of that day only — never a cumulative snapshot, so summing the rows of an
 * installment never double-counts (TS §4.3, DM §1.9).
 *
 * <p>Both invariants of the row are enforced here rather than left to V1
 * ({@code ck_penalty_accrual_amount}, {@code ck_penalty_accrual_days}): the amount is positive scale-2 money
 * and the row exists only because the day was actually chargeable, so {@code daysLate >= 1}. A day that is
 * not chargeable — still inside the grace period, or whose daily amount would round away to zero — produces
 * no row at all.
 *
 * @param accrualDate business date of the charge; the accrual happened because that day was late
 * @param daysLate    {@code hariTelat} of that date, {@code > 0} (TS §4.3)
 * @param amount      that day's penalty, scale-2 money, {@code > 0}
 */
public record PenaltyCharge(LocalDate accrualDate, int daysLate, BigDecimal amount) {

	private static final int MONEY_SCALE = 2;

	public PenaltyCharge {
		Objects.requireNonNull(accrualDate, "accrualDate");
		if (daysLate < 1) {
			throw new IllegalArgumentException("a penalty charge exists only for a late day but daysLate was "
					+ daysLate);
		}
		Objects.requireNonNull(amount, "amount");
		try {
			amount = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		} catch (ArithmeticException ex) {
			throw new IllegalArgumentException("a penalty charge must be money with at most " + MONEY_SCALE
					+ " decimal places but was " + amount);
		}
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("a penalty charge must be > 0 but was " + amount);
		}
	}
}
