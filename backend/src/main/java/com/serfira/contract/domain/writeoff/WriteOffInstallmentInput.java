package com.serfira.contract.domain.writeoff;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One installment's recognized-receivable inputs for the write-off cap (F5, task T19). Pure value object
 * (no Spring/JPA): the command service maps each {@code Installment} plus its effective-penalty record
 * into this shape and hands a list to {@link WriteOffEngine}.
 *
 * <p>Amounts are scale-2 money, {@code >= 0}. {@code terminal} marks an installment already SETTLED or
 * WRITTEN_OFF, which the engine skips (its receivable is already resolved; invariant 15).
 * {@code resolvedBeyondPenalty} is the money already resolved against interest and principal
 * ({@code max(0, resolvedAmount − paidPenalty)}), computed exactly as the settlement path does, so the
 * unpaid interest/principal residual nets correctly even on a part-paid installment.
 *
 * @param installmentId         the installment id
 * @param periodNo              its period number (ordering + response)
 * @param terminal              {@code true} when already SETTLED/WRITTEN_OFF (skipped by the engine)
 * @param principalAmount       scheduled principal of the installment
 * @param recognizedInterest    interest already billed/recognized (future interest is never written off)
 * @param effectivePenalty      remaining penalty owed, net of adjustments and paid penalty (invariant 9)
 * @param resolvedBeyondPenalty money already resolved past penalty, {@code max(0, resolved − paidPenalty)}
 */
public record WriteOffInstallmentInput(UUID installmentId, int periodNo, boolean terminal,
		BigDecimal principalAmount, BigDecimal recognizedInterest, BigDecimal effectivePenalty,
		BigDecimal resolvedBeyondPenalty) {
}
