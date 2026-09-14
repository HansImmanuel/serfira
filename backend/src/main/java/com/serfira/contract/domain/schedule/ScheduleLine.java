package com.serfira.contract.domain.schedule;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of a generated repayment schedule (03_DOMAIN_MODEL.md §1.4). Immutable value —
 * the source for the {@code installment} rows created at contract activation.
 *
 * @param periodNo        1-based period number (1..tenor)
 * @param dueDate         due date of the period (month-end clamped)
 * @param principalAmount scheduled principal for the period (scale 2)
 * @param interestAmount  scheduled interest for the period (scale 2)
 */
public record ScheduleLine(int periodNo, LocalDate dueDate, BigDecimal principalAmount, BigDecimal interestAmount) {

	/** Scheduled total payable for the period (= principal + interest). */
	public BigDecimal totalAmount() {
		return principalAmount.add(interestAmount);
	}
}