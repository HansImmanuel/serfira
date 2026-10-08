package com.serfira.penalty.application;

import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.domain.ContractStateException;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code penalty} module's seam for effective penalty (ADR-019 D1, invariant 9): the one
 * application-level place that computes
 * {@code effective = max(0, grossAccrued − Σ adjustment − Σ activePenaltyAllocation)} per installment, so
 * {@code contract} totals, the settlement quote and reporting all agree by construction (ADR-019 D4, closes
 * CR-14). The V3 payment-cap and V16 adjustment-cap triggers stay as the independent DB backstops computing
 * the same subtraction.
 *
 * <p>{@code penalty_adjustment} is {@code penalty}-owned; before T15 the {@code contract} module read it
 * through a temporary native query (ADR-010). That read is retired — {@code contract} now depends on this
 * port, creating the {@code contract → penalty} edge documented in 02_TECH_SPEC.md §1.
 *
 * <p>Gross penalty is {@code contract}-owned ({@code installment.penalty_amount}); the implementation reads
 * it through the existing {@code contract → penalty} seam ({@link com.serfira.contract.application.InstallmentPenaltyPort}).
 * The paid-penalty term ({@code Σ} active PENALTY {@code payment_allocation}) is {@code payment}-owned; the
 * implementation reads it through {@link com.serfira.payment.application.PenaltyAllocationPort}, the one-way
 * {@code penalty → payment} edge documented in 02_TECH_SPEC.md §1 (ADR-019 Context).
 */
public interface EffectivePenaltyPort {

	/**
	 * Effective penalty for every installment of the contract, in period order.
	 *
	 * @param contractId contract whose effective penalty is needed
	 * @return the per-installment snapshot
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID}) or
	 *                                   is ACTIVE without a schedule
	 */
	EffectivePenaltySnapshot loadEffectivePenalty(UUID contractId);

	/**
	 * Effective penalty for every installment of the contract regardless of its status, so a read path can
	 * report the net penalty of a closed (SETTLEMENT / MATURITY / WRITTEN_OFF) contract whose gross
	 * {@code penalty_amount} is not zeroed on close (ADR-019 D4). Unlike {@link #loadEffectivePenalty(UUID)}
	 * it does not reject a non-ACTIVE contract; {@code penalty_adjustment} and the paid-penalty allocation
	 * are append-only and still readable after close.
	 *
	 * @param contractId contract whose effective penalty is needed
	 * @return the per-installment snapshot
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract has no schedule
	 */
	EffectivePenaltySnapshot loadEffectivePenaltyAnyStatus(UUID contractId);

	/**
	 * Effective penalty for every installment of a set of contracts, in one batch, so a portfolio read (the
	 * aging report) resolves it for all contracts at once instead of once per contract (ADR-019 F3). The
	 * single formula and the three underlying reads (gross snapshot, Σ adjustment, Σ paid penalty) are each
	 * done once across the union of installments.
	 *
	 * @param contractIds contracts whose effective penalty is needed; an empty input yields an empty map
	 * @return a map from installment id to its effective-penalty record, across all input contracts that
	 *         have a schedule
	 */
	Map<UUID, InstallmentEffectivePenalty> loadEffectivePenalty(Collection<UUID> contractIds);
}
