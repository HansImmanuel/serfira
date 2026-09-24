package com.serfira.penalty.application;

import com.serfira.contract.application.ContractNotFoundException;
import com.serfira.contract.domain.ContractStateException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code penalty} module's entry point for daily penalty recognition (story D1, DM §1.9, TS §4.3,
 * Addendum §12, ADR-012): makes the penalty of every late day that has not been charged yet a receivable.
 *
 * <p><b>One transaction per call.</b> {@code REQUIRED} propagation is deliberate: called by the daily job
 * (D2) it opens the transaction itself; called by a flow that already has one (the void catch-up of E4,
 * per Addendum §6) it joins it, so the accrual rows, the installment's {@code penalty_amount} and the
 * journal entries commit or roll back together. Posting on the ledger side is {@code MANDATORY}, so this
 * entry point is what guarantees a transaction exists.
 *
 * <p>Idempotent by state: a day that already has an accrual row is never charged twice, so repeating the
 * call for the same business date posts nothing (V1 {@code uk_penalty_accrual}), while a later business date
 * charges only the days that are still missing — including days that a previous run could not charge because
 * nothing was outstanding.
 *
 * <p>Deliberately <b>not</b> part of the payment path on D1: PRD D-1 and Addendum §12 make this a daily job
 * step, and the lazy trigger belongs to the story that owns the scheduler (D2, with its own ADR).
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
	 * @param businessDate business date the step runs for; no later day is ever charged
	 * @return how many accrual rows (and journal entries) this call wrote, {@code 0} when nothing was due —
	 *         the counter the daily job reports as {@code job_run.records_processed} (D2)
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID}) —
	 *                                   only a serviced contract accrues penalty
	 */
	int accrueDuePenalty(UUID contractId, LocalDate businessDate);
}
