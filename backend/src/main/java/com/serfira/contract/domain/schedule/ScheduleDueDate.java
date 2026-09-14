package com.serfira.contract.domain.schedule;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Due-date rule (03_DOMAIN_MODEL.md §1.4, story B4): the due date of period {@code n} is the contract
 * start date shifted by {@code n} calendar months with month-end clamping — a start day of 29/30/31
 * maps to the last valid day of the shorter target month (31 Jan → 28/29 Feb, 31 Mar → 30 Apr, …).
 *
 * <p>{@link LocalDate#plusMonths(long)} already implements this clamping; this class pins the rule under
 * the B4 date matrix so it cannot drift and validates the 1-based period numbering.
 */
public final class ScheduleDueDate {

	private ScheduleDueDate() {
	}

	/**
	 * Computes the due date for 1-based {@code periodNo}.
	 *
	 * @throws NullPointerException     if {@code startDate} is {@code null}
	 * @throws IllegalArgumentException if {@code periodNo} is not {@code >= 1}
	 */
	public static LocalDate of(LocalDate startDate, int periodNo) {
		Objects.requireNonNull(startDate, "startDate");
		if (periodNo < 1) {
			throw new IllegalArgumentException("periodNo must be >= 1 but was " + periodNo);
		}
		return startDate.plusMonths(periodNo);
	}
}