package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code contract} module's receivable seam for modules that must resolve money against a
 * contract's installments (payment C3, settlement E2) — 02_TECH_SPEC.md §1 &quot;module berkomunikasi
 * via interface (application service) milik module lain&quot;, ADR-010.
 *
 * <p>Why a port and not the entities: the {@code installment} table is part of {@code contract}'s
 * domain, so both the read and the write stay inside the module. A caller may not query installment
 * rows or decide an {@code InstallmentStatus} itself (DM §1.4); it reads a snapshot, computes amounts,
 * and hands the resolved amounts back.
 *
 * <p>Both methods must be called inside the caller's transaction: they join it, they never start one.
 * Locking and the actual row update are the module's business, not the caller's.
 */
public interface InstallmentReceivablePort {

	/**
	 * Loads the contract's installments as receivables.
	 *
	 * @param contractId contract to load
	 * @return the contract's full schedule with its component amounts and penalty adjustments
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE or is active without a schedule
	 *                                   (409 {@code CONTRACT_STATE_INVALID}) — money may only be
	 *                                   resolved against a serviced contract
	 */
	ContractReceivableSnapshot loadReceivableSnapshot(UUID contractId);

	/**
	 * Applies resolved payment amounts to installments: raises {@code paid_amount}, stamps
	 * {@code paid_at} the first time an installment is resolved, and derives
	 * {@code PARTIALLY_PAID}/{@code PAID} (DM §1.4, PRD P-3). Amounts are the allocation engine's output
	 * (ADR-009); EXCESS is not an installment resolution and must not be passed here.
	 *
	 * <p>When the resolution leaves every installment of the contract {@code PAID}, the contract is
	 * closed as {@code MATURITY} with {@code paidAt} as its {@code closed_at} (DM §3 invariant 17,
	 * ADR-011). {@code SETTLED}/{@code WRITTEN_OFF} installments deliberately do not trigger that: those
	 * flows own their closing reason, so a settlement must close the contract through its own path
	 * ({@code SETTLEMENT}, E2) rather than relying on this one.
	 *
	 * @param contractId            contract the installments belong to
	 * @param resolvedByInstallment amount resolved by this payment per installment id, each {@code > 0}
	 * @param paidAt                business instant of the payment; becomes the installment's {@code paid_at}
	 *                              the first time it is resolved, and the contract's {@code closed_at} when
	 *                              this resolution closes it
	 * @throws IllegalStateException if an installment of the map is not part of the contract's schedule
	 */
	void applyPaymentResolution(UUID contractId, Map<UUID, BigDecimal> resolvedByInstallment,
			OffsetDateTime paidAt);
}
