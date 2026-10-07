package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.util.UUID;

/**
 * The {@code contract} module's seam for re-deriving an installment's resolution status after a penalty
 * waiver/reduce (E5, task T15, ADR-019 D3; the {@code penalty → contract} edge in 02_TECH_SPEC.md §1).
 *
 * <p>A waiver lowers the recognized penalty but is owned by the {@code penalty} module, which may not touch
 * the {@code contract}-owned {@code installment} table (02_TECH_SPEC.md §1, ADR-001). Without a status
 * recompute an installment whose last amount owed was a now-waived penalty stays {@code OVERDUE} /
 * {@code PARTIALLY_PAID} and blocks the maturity close (DM §3 invariant 17). This port lets the waiver
 * service ask {@code contract} to re-derive the status from the adjustment-aware balance (reusing the F2
 * remaining-penalty definition) and, when that leaves every installment {@code PAID}, close the contract as
 * {@code MATURITY} — the same rule the payment path applies.
 *
 * <p>The implementation joins the caller's transaction (it is deliberately not {@code @Transactional},
 * exactly like {@link InstallmentReceivableService}): the versioned installment write and any contract
 * close must commit or roll back with the waiver's adjustment row and journal. Writing the versioned
 * {@code installment} row also serializes concurrent waivers on the same installment via optimistic locking
 * (invariant 9, F4).
 */
public interface InstallmentStatusRecomputePort {

	/**
	 * Re-derives the resolution status of {@code installmentId} from its adjustment-aware balance and closes
	 * the contract as {@code MATURITY} when every installment is {@code PAID}.
	 *
	 * @param contractId    contract the installment belongs to
	 * @param installmentId the installment whose status to recompute after a waiver
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws IllegalStateException     if the installment is not part of the contract's schedule
	 */
	void recomputeAfterPenaltyAdjustment(UUID contractId, UUID installmentId);
}
