package com.serfira.contract.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Aging calendar of one installment (ADR-013 A-4, 03_DOMAIN_MODEL.md §1.4): when it becomes overdue and how
 * many days past due it is on a business date.
 *
 * <pre>
 * firstOverdueDate = due_date + grace_period_days + 1
 * DPD(D)           = max(0, D − due_date − grace_period_days)
 * </pre>
 *
 * <p>ADR-013 deliberately equates DPD-start with the first chargeable penalty day (TS §4.3), so aging and
 * penalty never disagree about lateness. {@code penalty.domain.PenaltyTerms} holds the same formula for the
 * penalty calculation; it is kept separate because {@code contract} must not depend on {@code penalty}, and
 * the two are pinned together by {@code AgingPenaltyParityTest}. Changing one without the other breaks that
 * test by design.
 */
public final class InstallmentAging {

	private InstallmentAging() {
	}

	/**
	 * First business date on which the installment counts as overdue: the day after the grace window ends.
	 *
	 * @param dueDate         installment due date
	 * @param gracePeriodDays the contract's activation-snapshot grace period, {@code >= 0}
	 */
	public static LocalDate firstOverdueDate(LocalDate dueDate, int gracePeriodDays) {
		Objects.requireNonNull(dueDate, "dueDate");
		requireNonNegativeGrace(gracePeriodDays);
		return dueDate.plusDays(gracePeriodDays + 1L);
	}

	/**
	 * Days past due on {@code date}: zero until {@link #firstOverdueDate}, then 1, 2, …
	 *
	 * @param dueDate         installment due date
	 * @param gracePeriodDays the contract's activation-snapshot grace period, {@code >= 0}
	 * @param date            business date to evaluate
	 * @return {@code max(0, date − dueDate − gracePeriodDays)}
	 */
	public static int daysPastDue(LocalDate dueDate, int gracePeriodDays, LocalDate date) {
		Objects.requireNonNull(dueDate, "dueDate");
		Objects.requireNonNull(date, "date");
		requireNonNegativeGrace(gracePeriodDays);
		long days = ChronoUnit.DAYS.between(dueDate, date) - gracePeriodDays;
		return days > 0 ? Math.toIntExact(days) : 0;
	}

	private static void requireNonNegativeGrace(int gracePeriodDays) {
		if (gracePeriodDays < 0) {
			throw new IllegalArgumentException("gracePeriodDays must be >= 0 but was " + gracePeriodDays);
		}
	}
}
