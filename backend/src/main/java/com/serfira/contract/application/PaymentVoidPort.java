package com.serfira.contract.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code contract} module's seam for a payment void (E4, task T16, ADR-020) — 02_TECH_SPEC.md §1
 * "module berkomunikasi via interface (application service) milik module lain", ADR-010.
 *
 * <p>Why a port and not the entities: a void recomputes the {@code contract}-owned {@code installment}
 * rows and may reopen the {@code contract} row, but the void use case lives in {@code payment}, which may
 * not touch another module's aggregate. The {@code payment} module owns {@code payment_allocation}, so it
 * computes <b>what the remaining POSTED payments resolve</b>; this port owns <b>applying that to the
 * installments and the contract</b>. This mirrors {@link InstallmentReceivablePort}: value-only interface,
 * both the read and the write stay inside {@code contract}.
 *
 * <p>Both methods join the caller's transaction (they never start one): the eligibility read and the
 * recomputation must observe exactly the state the void use case is committing.
 */
public interface PaymentVoidPort {

	/**
	 * Reads the void-eligibility snapshot for the installments a payment's allocations reference (ADR-020
	 * D5): the contract's status plus each installment's current resolution status. The caller rejects the
	 * void (409 {@code PAYMENT_NOT_VOIDABLE}) when the contract is not {@code ACTIVE} or any installment is
	 * {@code SETTLED}/{@code WRITTEN_OFF}; T16 never reopens a closed contract or reverses a
	 * settlement/write-off.
	 *
	 * @param contractId     the payment's contract
	 * @param installmentIds the installments the payment's allocations reference (never empty for a payment
	 *                       that resolved any installment)
	 * @return the contract status and the status of each requested installment
	 * @throws ContractNotFoundException if no contract has that id
	 */
	VoidEligibility checkVoidEligibility(UUID contractId, Set<UUID> installmentIds);

	/**
	 * Applies a void's installment recomputation (ADR-020 D1.3): sets each affected installment's
	 * {@code paid_amount} to the absolute total the <b>remaining POSTED payments</b> resolve against it
	 * (the caller computes this from {@code payment_allocation}, excluding the voided payment), re-derives
	 * the resolution status (demoting {@code PAID → PARTIALLY_PAID → PENDING} as the total drops), and
	 * reopens the contract from a {@code MATURITY} auto-close back to {@code ACTIVE} when the recomputation
	 * leaves an installment no longer {@code PAID}.
	 *
	 * <p>The amounts are absolute, not deltas: an installment missing from the map means the remaining
	 * POSTED payments resolve nothing against it, so it is reset to {@code paid_amount = 0}. The versioned
	 * installment and contract writes commit with the caller's reversal journal and payment status flip.
	 *
	 * @param contractId                   the payment's contract
	 * @param recomputedPaidByInstallment  absolute paid total per affected installment id from the remaining
	 *                                     POSTED payments, each {@code >= 0}
	 * @param voidedAt                     business instant of the void (unused by the recomputation itself,
	 *                                     kept for symmetry with the resolution mutators and future audit)
	 * @throws IllegalStateException if an installment of the map is not part of the contract's schedule
	 */
	void applyVoidRecomputation(UUID contractId, Map<UUID, BigDecimal> recomputedPaidByInstallment,
			OffsetDateTime voidedAt);
}
