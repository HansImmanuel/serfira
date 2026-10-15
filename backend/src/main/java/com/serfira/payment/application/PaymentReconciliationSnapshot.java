package com.serfira.payment.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The {@code payment}-owned figures the daily consistency check (F1, task T17, Addendum §7.1) reconciles
 * for one contract.
 *
 * <ul>
 *   <li>{@code postedPaymentTotal} — {@code Σ} POSTED {@link com.serfira.payment.domain.Payment#getAmount()
 *       Payment.amount}; the cash the module recorded as received for the contract.</li>
 *   <li>{@code postedAllocationTotal} — {@code Σ} every allocation row (PENALTY/INTEREST/PRINCIPAL/EXCESS)
 *       of those POSTED payments; by invariant 6 it must equal {@code postedPaymentTotal} (Addendum §7.1
 *       check B, {@code ALLOCATION_PAYMENT}).</li>
 * </ul>
 *
 * <p>A voided payment contributes to neither total: its row and allocations stay as history but no longer
 * count as active (DM §1.7/§1.8), which is the same active-only view the ledger reversal of a void produced
 * (ADR-020), so both sides of every check move together.
 *
 * @param postedPaymentTotal    {@code Σ} POSTED payment amount for the contract, scale-2, {@code >= 0}
 * @param postedAllocationTotal {@code Σ} allocation amount of those POSTED payments, scale-2, {@code >= 0}
 */
public record PaymentReconciliationSnapshot(BigDecimal postedPaymentTotal, BigDecimal postedAllocationTotal) {

	public PaymentReconciliationSnapshot {
		if (postedPaymentTotal == null || postedAllocationTotal == null) {
			throw new IllegalArgumentException("reconciliation totals must not be null");
		}
	}
}
