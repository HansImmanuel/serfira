package com.serfira.payment.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.UUID;

/**
 * The {@code payment} module's read seam for cash collected on installments (T36, ADR-026 D5/D8): the
 * collection-rate numerator of the outstanding report. {@code payment_allocation} is {@code payment}-owned,
 * so {@code reporting} reads it only through this port.
 */
public interface CollectedAllocationPort {

	/**
	 * {@code Σ} PENALTY + INTEREST + PRINCIPAL allocation of POSTED payments on the given installments,
	 * whenever the payment was made (cash paid early for an installment counts).
	 *
	 * <p>Excluded: EXCESS allocations (they carry no installment and become credit), the allocations of
	 * VOIDED payments (ADR-020), and anything that is not a payment — credit applications, settlements,
	 * write-offs and penalty adjustments write no {@code payment_allocation} row.
	 *
	 * <p>Read-only, in its own read-only transaction (or the caller's, if one is active).
	 *
	 * @param installmentIds the cohort; an empty input yields zero, and a repeated id counts once
	 * @return scale-2 money, {@code >= 0}
	 */
	BigDecimal sumCollected(Collection<UUID> installmentIds);
}
