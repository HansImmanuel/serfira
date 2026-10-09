package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.util.UUID;

/**
 * The {@code contract} module's read seam for the settlement quote (E1, task T12) — 02_TECH_SPEC.md §1
 * "module berkomunikasi via interface (application service) milik module lain", ADR-010, ADR-018.
 *
 * <p>Why a port of its own and not a method on {@link InstallmentReceivablePort}: that port exposes only
 * the <b>recognized</b> receivable (what the allocation waterfall may touch), deliberately hiding future
 * scheduled interest. A settlement quote must also see the <b>scheduled</b> interest of future periods to
 * price the rebate (ADR-018 D2), plus the contract's optimistic-lock version to snapshot into the quote
 * (ADR-018 D9). Keeping that on a separate seam keeps the payment view free of a future-interest figure it
 * must never allocate against.
 *
 * <p>Read-only: it never mutates. T12 persists the quote through its own module; this port only supplies
 * the priced inputs. The settlement module nets the per-component receivable (unpaid principal, unpaid
 * billed interest, future interest) itself, so the receivable rules stay defined once.
 */
public interface SettlementReceivablePort {

	/**
	 * Loads the contract and its installments for settlement pricing.
	 *
	 * @param contractId contract to price
	 * @return the contract's full schedule with its scheduled/recognized component amounts and the
	 *         contract's current version
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE, or is ACTIVE without a schedule
	 *                                   (409 {@code CONTRACT_STATE_INVALID}) — only a serviced contract
	 *                                   can be settled (ADR-018 D10)
	 */
	SettlementContractSnapshot loadSettlementSnapshot(UUID contractId);
}
