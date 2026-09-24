package com.serfira.penalty.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the pure penalty calculation needs about one installment and one run, so the arithmetic stays
 * deterministic and testable without Spring, JPA, or a database (TS §4.3, Addendum §6).
 *
 * <p>The grace period and the daily rate are the values <b>snapshotted on the contract at activation</b>
 * (Addendum §1.1, DM §1.3) — never a live read of {@code system_parameter}. {@code penaltyBase} is the
 * documented "pokok+bunga yang belum dibayar" of that day
 * ({@code InstallmentBalance.penaltyBase}), and {@code alreadyAccrued} is the set of dates that already have
 * a {@code penalty_accrual} row: eligibility is decided <b>per date</b>, so a date that was never charged
 * stays chargeable and a date that was charged is never charged twice (ADR-012 decisions 3–4).
 *
 * @param dueDate         the installment's due date; lateness is measured from here
 * @param gracePeriodDays free days after {@code dueDate} before any penalty may be charged, {@code >= 0}
 * @param penaltyRateDaily daily rate as a decimal fraction ({@code 0.1% = 0.0010}), {@code >= 0}
 * @param penaltyBase     unpaid principal + unpaid recognized interest, {@code >= 0}; zero means nothing is
 *                        outstanding, so no day is chargeable
 * @param businessDate    business date the step runs for; no day after it is ever charged
 * @param alreadyAccrued  dates of the installment that already have an accrual row; empty when none
 */
public record PenaltyTerms(LocalDate dueDate, int gracePeriodDays, BigDecimal penaltyRateDaily,
		BigDecimal penaltyBase, LocalDate businessDate, Set<LocalDate> alreadyAccrued) {

	public PenaltyTerms {
		Objects.requireNonNull(dueDate, "dueDate");
		Objects.requireNonNull(penaltyRateDaily, "penaltyRateDaily");
		Objects.requireNonNull(penaltyBase, "penaltyBase");
		Objects.requireNonNull(businessDate, "businessDate");
		if (gracePeriodDays < 0) {
			throw new IllegalArgumentException("gracePeriodDays must be >= 0 but was " + gracePeriodDays);
		}
		if (penaltyRateDaily.signum() < 0) {
			throw new IllegalArgumentException("penaltyRateDaily must be >= 0 but was " + penaltyRateDaily);
		}
		if (penaltyBase.signum() < 0) {
			throw new IllegalArgumentException("penaltyBase must be >= 0 but was " + penaltyBase);
		}
		alreadyAccrued = alreadyAccrued == null ? Set.of() : Set.copyOf(alreadyAccrued);
	}

	/** First date that may carry a penalty: the day after the free grace window ends. */
	public LocalDate firstChargeableDate() {
		return dueDate.plusDays(gracePeriodDays + 1L);
	}

	/**
	 * {@code hariTelat} of a date (TS §4.3): {@code max(0, date − dueDate − gracePeriodDays)}.
	 *
	 * @return the number of penalty-bearing days through {@code date}, {@code >= 1} for a chargeable date
	 */
	public int daysLate(LocalDate date) {
		Objects.requireNonNull(date, "date");
		return (int) (ChronoUnit.DAYS.between(dueDate, date) - gracePeriodDays);
	}
}
