package com.serfira.contract.infrastructure;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-model aggregate of a contract's installments (PRD §5A "Outstanding"): principal residual +
 * recognized interest residual + effective penalty residual.
 *
 * <p>This JPQL constructor projection mirrors {@code InstallmentBalance} — keep both in step. It still
 * sums <b>gross</b> penalty and omits {@code penalty_adjustment} rows. The waiver flow now exists (E5/T15)
 * and the schedule row and aging report net active adjustments through {@code EffectivePenaltyPort} (T30),
 * but this per-contract SQL aggregate (the contract list/detail "Outstanding") was deliberately left out of
 * T30's scope and still reports gross; netting active adjustments here — exactly like
 * {@code InstallmentBalance.of(installment, adjustments)} does — remains a follow-up (ADR-019 D4). A SQL
 * aggregate cannot call the port, so it needs its own adjustment-sum subquery or projection.
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