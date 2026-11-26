package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * One installment of the collection-rate cohort (T36, ADR-026 D5): an installment due in the report period on a
 * non-DRAFT contract, with the receivable values the denominator needs. The {@code contract} module owns the
 * recognized-total formula ({@link com.serfira.contract.domain.InstallmentBalance}), so the report consumes it
 * here and never restates it (02_TECH_SPEC.md §1, ADR-026 D8).
 *
 * @param installmentId   the installment; the {@code payment} module's collected read is keyed by it
 * @param recognizedTotal principal + recognized interest + penalty net of active adjustments (ADR-019),
 *                        scale-2, {@code >= 0}
 * @param settledAmount   the part resolved by an early settlement, scale-2, {@code >= 0}; the report takes it
 *                        out of both sides of the collection rate
 */
public record CohortReceivableSnapshot(UUID installmentId, BigDecimal recognizedTotal, BigDecimal settledAmount) {

	public CohortReceivableSnapshot {
		Objects.requireNonNull(installmentId, "installmentId");
		Objects.requireNonNull(recognizedTotal, "recognizedTotal");
		Objects.requireNonNull(settledAmount, "settledAmount");
		if (recognizedTotal.signum() < 0) {
			throw new IllegalArgumentException("recognizedTotal must be >= 0 but was " + recognizedTotal);
		}
		if (settledAmount.signum() < 0) {
			throw new IllegalArgumentException("settledAmount must be >= 0 but was " + settledAmount);
		}
	}
}
