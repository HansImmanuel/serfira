package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

/**
 * The {@code contract} module's seam for closing a contract by early settlement (E2, task T13, ADR-018
 * D10) — 02_TECH_SPEC.md §1 "module berkomunikasi via interface (application service) milik module lain",
 * ADR-010.
 *
 * <p>Why a dedicated port rather than reusing {@link InstallmentReceivablePort#applyPaymentResolution}:
 * that path closes a contract only for {@code MATURITY} (the auto-close after the final installment is
 * paid) and is explicitly documented not to touch {@code SETTLED}. A settlement closes the contract for a
 * different reason and moves every open installment to the terminal {@code SETTLED} state with
 * {@code settled_amount}/{@code settled_at} — a transition the payment path must never make. Keeping it on
 * its own seam preserves the payment path's single meaning and keeps the {@code SETTLED}-close logic inside
 * the {@code contract} module, which owns {@code installment} and {@code contract}.
 *
 * <p>Called inside the settlement use case's transaction (it joins it, {@code Propagation.MANDATORY}),
 * never starts one: the installment settlement, the contract closure and the settlement's own journal
 * entry commit or roll back together. It posts <b>no</b> journal — the settlement module owns the single
 * balanced SETTLEMENT entry (ADR-018 D1/D4).
 */
public interface SettlementClosePort {

	/**
	 * Settles every still-open installment of an ACTIVE contract and closes the contract {@code SETTLEMENT}.
	 *
	 * @param command the contract, the quote's snapshotted version, the receivable to settle per
	 *                installment, and the settlement instant
	 * @return the receivable actually written to each installment's {@code settled_amount}
	 * @throws ContractNotFoundException     if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException        if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID})
	 * @throws com.serfira.shared.error.StaleSettlementQuoteException if the live contract version differs
	 *                                       from the quote's snapshot (409 {@code STALE_SETTLEMENT_QUOTE})
	 */
	SettlementCloseResult closeBySettlement(SettlementCloseCommand command);
}
