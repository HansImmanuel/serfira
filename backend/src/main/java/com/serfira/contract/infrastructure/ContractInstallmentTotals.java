package com.serfira.contract.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model aggregate of a contract's installments (PRD §5A "Outstanding"): principal residual +
 * recognized interest residual + effective penalty residual.
 *
 * <p>This JPQL constructor projection mirrors {@code InstallmentBalance} — keep both in step. It
 * deliberately omits {@code penalty_adjustment} rows, because no waiver flow exists yet (story E5);
 * when E5 lands, this aggregate must subtract active adjustments exactly like
 * {@code InstallmentBalance.of(installment, adjustments)} does.
 *
 * @param contractId      contract the amounts belong to
 * @param recognizedTotal Σ principal + recognized interest + penalty
 * @param resolvedAmount  Σ paid + settled + written-off
 */
public record ContractInstallmentTotals(UUID contractId, BigDecimal recognizedTotal, BigDecimal resolvedAmount) {

	/** Recognized receivable not yet resolved (never negative — V3 deferred trigger). */
	public BigDecimal outstanding() {
		return recognizedTotal.subtract(resolvedAmount);
	}
}