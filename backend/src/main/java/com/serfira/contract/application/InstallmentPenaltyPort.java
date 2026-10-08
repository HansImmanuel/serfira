package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
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
	 * Reads the same gross penalty state as {@link #loadPenaltySnapshot(UUID)} but for a contract in ANY
	 * status, so a read path can report the effective penalty of a closed (SETTLEMENT / MATURITY /
	 * WRITTEN_OFF) contract (ADR-019 D4). Gross {@code penalty_amount} is not zeroed on close, so a
	 * previously-waived installment would otherwise surface gross again.
	 *
	 * <p>Unlike the accrual read it does not gate on ACTIVE — a closed contract never accrues, but its
	 * schedule is still a legal thing to read net of adjustments. It still rejects an unknown id (404) and a
	 * contract with no schedule (an un-activated DRAFT has none; callers branch DRAFT out before calling).
	 *
	 * @param contractId contract whose penalty state is needed
	 * @return snapshot in period order, future installments included
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract has no schedule
	 */
	InstallmentPenaltySnapshot loadPenaltySnapshotAnyStatus(UUID contractId);

	/**
	 * Bulk form of {@link #loadPenaltySnapshotAnyStatus(UUID)} for a set of contracts, in one query, so a
	 * portfolio read (the aging report) resolves gross penalty for every contract at once instead of once
	 * per contract (F3). Contracts without a schedule are simply absent from the result.
	 *
	 * @param contractIds contracts whose penalty state is needed; an empty input yields an empty list
	 * @return one snapshot per contract that has a schedule, no particular order
	 */
	List<InstallmentPenaltySnapshot> loadPenaltySnapshots(Collection<UUID> contractIds);

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
