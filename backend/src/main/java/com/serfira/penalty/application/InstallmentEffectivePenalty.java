package com.serfira.penalty.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Effective penalty of one installment (invariant 9, ADR-019 D1): what the customer actually still owes
 * in denda after waivers/reductions.
 *
 * <p>{@code effective = max(0, grossAccrued − adjustment)}. The clamp at zero is the invariant-9 guard:
 * a {@code penalty_adjustment} may never drive the effective value negative (the V3 and V15 caps enforce
 * the same bound at the database level). {@code grossAccrued} is {@code installment.penalty_amount} and
 * {@code adjustment} is {@code Σ penalty_adjustment.amount} for the installment.
 *
 * @param installmentId installment the values belong to
 * @param grossAccrued  gross cumulative recognized penalty, scale-2, {@code >= 0}
 * @param adjustment    Σ waive/reduce for the installment, scale-2, {@code >= 0}
 * @param effective     {@code max(0, grossAccrued − adjustment)}, scale-2, {@code >= 0}
 */
public record InstallmentEffectivePenalty(UUID installmentId, BigDecimal grossAccrued, BigDecimal adjustment,
		BigDecimal effective) {
}
