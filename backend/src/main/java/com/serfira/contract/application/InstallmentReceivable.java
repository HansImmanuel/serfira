package com.serfira.contract.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only receivable view of one installment, exposed to other modules (DM §1.4).
 *
 * <p>Values are the installment's own columns, unmodified: the caller turns them into the allocation
 * engine's input by adding <b>its own</b> active allocation sums (DM §1.8). Nothing here is a derived
 * figure, so there is exactly one definition of the receivable ({@code InstallmentBalance} / DM §1.4)
 * and no second copy of it.
 *
 * @param installmentId            installment the amounts belong to
 * @param periodNo                 1-based period number, used to break ties on identical due dates
 * @param dueDate                  due date; only {@code due_date <= business date} is allocatable (ADR-009)
 * @param principalAmount          scheduled principal (cap for PRINCIPAL allocations)
 * @param recognizedInterestAmount interest recognized/billed so far (cap for INTEREST allocations)
 * @param penaltyAmount            gross cumulative recognized penalty
 * @param penaltyAdjustments       Σ penalty adjustments (waive/reduce), from {@code penalty_adjustment}
 * @param settledAmount            settlement resolution (not a payment)
 * @param writtenOffAmount         write-off resolution (not a payment)
 */
public record InstallmentReceivable(UUID installmentId, int periodNo, LocalDate dueDate,
		BigDecimal principalAmount, BigDecimal recognizedInterestAmount, BigDecimal penaltyAmount,
		BigDecimal penaltyAdjustments, BigDecimal settledAmount, BigDecimal writtenOffAmount) {
}
