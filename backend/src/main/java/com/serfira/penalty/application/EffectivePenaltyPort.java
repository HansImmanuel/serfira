package com.serfira.penalty.application;

import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.domain.ContractStateException;

import java.util.UUID;

/**
 * The {@code penalty} module's seam for effective penalty (ADR-019 D1, invariant 9): the one
 * application-level place that computes
 * {@code effective = max(0, grossAccrued − Σ adjustment)} per installment, so {@code contract} totals, the
 * settlement quote and reporting all agree by construction (ADR-019 D4, closes CR-14). The V3 payment-cap
 * trigger stays as the independent DB backstop computing the same subtraction.
 *
 * <p>{@code penalty_adjustment} is {@code penalty}-owned; before T15 the {@code contract} module read it
 * through a temporary native query (ADR-010). That read is retired — {@code contract} now depends on this
 * port, creating the {@code contract → penalty} edge documented in 02_TECH_SPEC.md §1.
 *
 * <p>Gross penalty is {@code contract}-owned ({@code installment.penalty_amount}); the implementation reads
 * it through the existing {@code contract → penalty} seam ({@link com.serfira.contract.application.InstallmentPenaltyPort}),
 * so this port adds no new {@code penalty → contract} edge.
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
}
