package com.serfira.payment.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code payment} module's seam for the penalty already resolved by payment (ADR-001, ADR-019,
 * 02_TECH_SPEC.md §1): lets the {@code penalty} module read {@code Σ} active PENALTY
 * {@code payment_allocation} per installment so the effective-penalty formula can subtract the paid term
 * (invariant 9, {@code effective = max(0, grossAccrued − adjustment − activePenaltyAllocation)}).
 *
 * <p>{@code payment_allocation} is {@code payment}-owned, so this is a one-way {@code penalty → payment}
 * edge: {@code penalty} never reads the {@code payment_allocation} table or entity directly, only through
 * this port.
 *
 * <p><b>POSTED vs all rows.</b> This port counts only POSTED payments' allocations (an active, forward-
 * looking view: a voided payment's PENALTY allocation would no longer be owed), whereas the V3/V16 DB cap
 * triggers count every {@code payment_allocation} row regardless of payment status (ADR-009 risk note).
 * Payment void does not exist yet (T16), so POSTED == all rows today and the application pre-check and the
 * DB caps are numerically identical. When void lands this divergence becomes intentional and must be
 * reconciled with the trigger.
 *
 * <p>The implementation method joins the caller's transaction ({@code Propagation.MANDATORY}), like the
 * other cross-module ports.
 */
public interface PenaltyAllocationPort {

	/**
	 * {@code Σ} active (POSTED) PENALTY allocation per installment, scale-2 money.
	 *
	 * @param installmentIds installments whose paid penalty is needed
	 * @return a map from installment id to its {@code Σ} PENALTY allocation; installments with no PENALTY
	 *         allocation are omitted (treat an absent key as zero). Empty for an empty input.
	 */
	Map<UUID, BigDecimal> penaltyAllocationsByInstallment(Collection<UUID> installmentIds);
}
