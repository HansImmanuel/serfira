package com.serfira.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One installment's amounts as {@link SettlementQuoteEngine} prices them (E1, task T12, ADR-018 D2/D7).
 *
 * <p>The engine derives the per-component split (unpaid principal, unpaid billed interest, future
 * interest) from these raw figures using the allocation waterfall order PENALTY → INTEREST → PRINCIPAL
 * (ADR-009): {@code resolvedBeyondPenalty} is the money already applied past penalty, so the engine
 * applies it to interest then principal. The effective (remaining) penalty is passed pre-netted via
 * {@code effectivePenalty} (invariant 9, through the penalty port, ADR-018 D8); a penalty adjustment
 * (waiver) is reflected only in {@code effectivePenalty}, never in the money-based figures.
 *
 * @param installmentId     installment the amounts belong to
 * @param periodNo          1-based period number
 * @param dueDate           due date; a due date strictly after the settlement date is a future period
 * @param settled           {@code true} if the installment is SETTLED/WRITTEN_OFF and must be excluded
 *                          from pricing (its receivable was resolved another way, ADR-018 D2)
 * @param principalAmount   scheduled principal, scale-2 {@code >= 0}
 * @param interestAmount    scheduled interest for the period, scale-2 {@code >= 0}
 * @param recognizedInterest interest billed/recognized so far, scale-2 {@code >= 0}
 * @param effectivePenalty  remaining penalty, {@code max(0, gross − adjustment − paid)}, scale-2 {@code >= 0}
 * @param resolvedBeyondPenalty money already resolved against interest+principal (i.e. the resolved amount
 *                          minus the paid penalty; penalty adjustments are not money and are carried only
 *                          by {@code effectivePenalty}), scale-2 {@code >= 0}
 */
public record SettlementInstallmentInput(UUID installmentId, int periodNo, LocalDate dueDate, boolean settled,
		BigDecimal principalAmount, BigDecimal interestAmount, BigDecimal recognizedInterest,
		BigDecimal effectivePenalty, BigDecimal resolvedBeyondPenalty) {
}
