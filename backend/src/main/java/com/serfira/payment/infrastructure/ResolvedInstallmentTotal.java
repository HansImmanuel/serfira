package com.serfira.payment.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The total amount POSTED payments resolve against one installment (penalty + interest + principal, EXCESS
 * excluded), summed across all their allocations (E4 void, task T16).
 *
 * <p>Projection, not an entity: the void path reads it to learn the absolute paid amount an installment
 * should carry once the voided payment's allocations stop counting (ADR-020 D1.3).
 *
 * @param installmentId installment the rows resolved (never {@code null} — EXCESS is excluded)
 * @param amount        total resolved amount, scale-2 money {@code > 0}
 */
public record ResolvedInstallmentTotal(UUID installmentId, BigDecimal amount) {
}
