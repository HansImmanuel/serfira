package com.serfira.payment.infrastructure;

import com.serfira.payment.domain.AllocationType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One grouped row of the "active allocations per installment and component" query: the sum of the
 * allocations of <b>POSTED</b> payments for one installment and one {@link AllocationType}.
 *
 * <p>This is the {@code allocated*} input of {@code InstallmentAllocationInput} — the amount already
 * resolved by payment, which the allocation engine subtracts from each component's cap (DM §1.4,
 * ADR-009). It is a projection, not an entity: nothing is written through it.
 *
 * @param installmentId installment the rows belong to (never {@code null} — EXCESS is excluded)
 * @param type          component the rows resolved
 * @param amount        total active amount, scale-2 money {@code > 0}
 */
public record ActiveAllocationTotal(UUID installmentId, AllocationType type, BigDecimal amount) {
}
