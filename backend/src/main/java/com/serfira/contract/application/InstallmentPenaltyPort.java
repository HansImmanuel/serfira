package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code contract} module's seam for late-payment penalty accrual (DM §1.9, TS §4.3, PRD D-1/D-3):
 * lets the {@code penalty} module — which owns {@code penalty_accrual} — read what an installment still owes
 * in pokok+bunga and raise its gross {@code penalty_amount}, without ever touching the {@code installment}
 * table itself (02_TECH_SPEC.md §1, ADR-012).
 *
 * <p>Why a port of its own rather than a method on {@link InstallmentReceivablePort} or
 * {@link InstallmentBillingPort}: those seams resolve money against the schedule and recognize interest,
 * while this one carries the penalty aggregate's own inputs (base, grace, rate) — and a single
 * implementation per interface keeps injection unambiguous (ADR-010/ADR-011).
 *
 * <p>Both methods join the caller's transaction; the implementation is deliberately not
 * {@code @Transactional}, exactly like {@link InstallmentReceivableService}, so accrual rows and the
 * installment write commit or roll back together (the caller — the daily job or the void flow — owns the
 * boundary).
 */
public interface InstallmentPenaltyPort {

	/**
	 * Reads the contract's grace period, daily rate and every installment's due date, penalty base and status.
	 *
	 * @param contractId contract whose penalty state is needed
	 * @return snapshot in period order, future installments included
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID}) or
	 *                                   is ACTIVE without a schedule
	 */
	InstallmentPenaltySnapshot loadPenaltySnapshot(UUID contractId);

	/**
	 * Raises the gross recognized penalty of the given installments by their amounts.
	 *
	 * <p>The caller accumulates one run's charged days per installment; this method writes
	 * {@code penalty_amount} and flushes inside the caller's transaction, so a constraint or invariant
	 * failure surfaces in the caller's use case rather than at commit.
	 *
	 * @param contractId           contract the installments belong to
	 * @param accruedByInstallment amount accrued by this run per installment id, each {@code > 0}
	 * @throws IllegalStateException if an installment of the map is not part of the contract's schedule
	 */
	void applyPenaltyAccrual(UUID contractId, Map<UUID, BigDecimal> accruedByInstallment);
}
