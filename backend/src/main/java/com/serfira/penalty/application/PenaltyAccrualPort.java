package com.serfira.penalty.application;

import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.domain.ContractStateException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code penalty} module's entry point for penalty recognition (story D1/T4, DM §1.9, TS §4.3,
 * Addendum §12, ADR-012/ADR-013): makes the penalty of every late day that has not been charged yet a
 * receivable.
 *
 * <p><b>One transaction per call.</b> {@code REQUIRED} propagation is deliberate: called by the daily job
 * (D2) it opens the transaction itself; called by a financial flow that already has one, including payment
 * receipt (T4) and the void catch-up of E4, it joins that transaction. The accrual rows, installment
 * {@code penalty_amount}, and journal entries therefore commit or roll back with the calling use case.
 * Posting on the ledger side is {@code MANDATORY}, so this entry point guarantees a transaction exists.
 *
 * <p>Idempotent by state: a day that already has an accrual row is never charged twice, so repeating the
 * call for the same business date posts nothing (V1 {@code uk_penalty_accrual}), while a later business date
 * charges only the days that are still missing — including days that a previous run could not charge because
 * nothing was outstanding. Endpoint idempotency remains the caller's responsibility.
 *
 * <p>The payment path calls this port after billing and before reading its receivable snapshot, with the
 * same captured business date. That ordering makes due interest part of the penalty base and makes the new
 * penalty visible to the allocation waterfall in one transaction (ADR-013).
 */
public interface PenaltyAccrualPort {

	/**
	 * Charges every late day of the contract's installments that has no accrual row yet, for the given
	 * business date.
	 *
	 * <p>A day is chargeable when it lies in {@code [due_date + grace + 1, businessDate]}, the installment is
	 * not {@code SETTLED}/{@code WRITTEN_OFF}, and its unpaid pokok+bunga is {@code > 0}; each chargeable day
	 * becomes one {@code penalty_accrual} row, one {@code PENALTY_ACCRUAL} journal entry
	 * ({@code PIUTANG_DENDA} debit / {@code PENDAPATAN_DENDA} credit, {@code entry_date = accrual_date}), and
	 * one increment of the installment's gross {@code penalty_amount}.
	 *
	 * @param contractId   contract whose due penalty is to be recognized
	 * @param businessDate single business date captured by the caller; no later day is ever charged
	 * @return how many accrual rows (and journal entries) this call wrote, {@code 0} when nothing was due;
	 *         this is a financial-write diagnostic only — {@code job_run.records_processed} counts contracts
	 *         processed by the daily step (ADR-013), not accrual rows
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID}) —
	 *                                   only a serviced contract accrues penalty
	 */
	int accrueDuePenalty(UUID contractId, LocalDate businessDate);
}
