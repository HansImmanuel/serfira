package com.serfira.penalty.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Σ penalty-adjustment amount for one installment, used to build the effective-penalty snapshot
 * (invariant 9: {@code effective = penalty_amount − Σ adjustment}, ADR-019 D1).
 *
 * @param installmentId the installment
 * @param adjustment    total of its {@code penalty_adjustment.amount} rows
 */
public record InstallmentAdjustmentTotal(UUID installmentId, BigDecimal adjustment) {
}
