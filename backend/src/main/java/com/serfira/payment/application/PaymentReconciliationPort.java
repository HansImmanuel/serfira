package com.serfira.payment.application;

import java.util.UUID;

/**
 * The {@code payment} module's read seam for the daily consistency check (F1, task T17, Addendum §7.1).
 *
 * <p>{@code payment} and {@code payment_allocation} are {@code payment}-owned, so the consistency step
 * (hosted in {@code penalty}) must read the per-contract POSTED payment total and allocation total through
 * this port rather than querying those tables — the strict module boundary (02_TECH_SPEC.md §1). This is a
 * one-way {@code penalty → payment} edge, mirroring the existing {@link PenaltyAllocationPort}.
 *
 * <p>Read-only and side-effect free. The consistency step reconciles the returned figures against the
 * ledger (check A, {@code PAYMENT_CASH}) and against each other (check B, {@code ALLOCATION_PAYMENT}); it
 * never writes through this port.
 */
public interface PaymentReconciliationPort {

	/**
	 * The contract's POSTED payment total and the matching allocation total, in one read.
	 *
	 * @param contractId contract whose payment figures are needed
	 * @return the snapshot; both totals are zero for a contract with no POSTED payment
	 */
	PaymentReconciliationSnapshot loadReconciliationSnapshot(UUID contractId);
}
