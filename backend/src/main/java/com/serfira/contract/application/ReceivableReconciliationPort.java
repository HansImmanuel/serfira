package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The {@code contract} module's read seam for the daily consistency check's receivable reconciliation
 * (F1, task T17, Addendum §7.1 check C, {@code RECEIVABLE_INSTALLMENT}).
 *
 * <p>Check C compares {@code Σ (principal residual + recognized interest residual + effective penalty
 * residual)} against the contract's {@code PIUTANG_*} ledger balance. The receivable residual is a
 * {@code contract} domain figure ({@code InstallmentBalance} outstanding, DM §1.4), netted against active
 * penalty adjustments through the {@code penalty}-owned {@code EffectivePenaltyPort} — exactly the same
 * single formula the receivable snapshot (T15) and the aging report (T30) use, so no second definition of
 * "outstanding" is introduced (ADR-019 D4). The consistency step (hosted in {@code penalty}) reads it here
 * rather than touching {@code installment}, keeping the strict module boundary (02_TECH_SPEC.md §1).
 *
 * <p>Read-only. This is the same {@code penalty → contract} direction as the other contract seams the job
 * already uses ({@code ActiveContractListingPort}, {@code InstallmentPenaltyPort}).
 */
public interface ReceivableReconciliationPort {

	/**
	 * The contract's recognized receivable residual: {@code Σ} over the schedule of
	 * {@code max/raw outstanding = principal + recognized interest + (penalty − Σ adjustment) − resolved},
	 * netting active waivers (invariant 9) but never future unrecognized interest.
	 *
	 * <p>The sum is <b>not</b> clamped: a per-installment negative outstanding (over-resolved, corrupted
	 * data a DB invariant should have blocked, DM §1.4) is left in the sum so the consistency check still
	 * sees the drift rather than hiding it ("report, do not fix", Addendum §7.3).
	 *
	 * @param contractId contract whose receivable residual is needed
	 * @return the residual, scale-2 (sign preserved); zero when every installment is fully resolved
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE or is ACTIVE without a schedule
	 *                                   (409 {@code CONTRACT_STATE_INVALID})
	 */
	BigDecimal receivableResidual(UUID contractId);
}
