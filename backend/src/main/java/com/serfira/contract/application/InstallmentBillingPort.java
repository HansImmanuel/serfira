package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code contract} module's seam for interest recognition (&quot;billing&quot;): makes the
 * scheduled interest of a serviced contract's due installments receivable (04_GAPS_ADDENDUM.md §12,
 * DM §1.4) — 02_TECH_SPEC.md §1 &quot;module berkomunikasi via interface milik module lain&quot;,
 * ADR-011.
 *
 * <p>Why this is a port of its own instead of a method on {@link InstallmentReceivablePort}: that port
 * resolves <b>money</b> against a contract, this one records an <b>accounting fact</b> about the
 * schedule. Keeping the concerns apart keeps each seam's state rules unambiguous and each port
 * single-implementation, so injection stays explicit.
 *
 * <p>The call joins the caller's transaction ({@code REQUIRED}); it never silently starts a second one.
 * The journal it posts ({@code LedgerRefType.BILLING}) therefore commits or rolls back together with the
 * business write that owns the transaction: the payment that triggered the recognition, or the daily
 * job's own transaction (D2).
 */
public interface InstallmentBillingPort {

	/**
	 * Recognizes the scheduled interest of every installment of the contract whose due date has been
	 * reached ({@code due_date <= businessDate}) and that has not been billed yet. Posts one
	 * {@code BILLING} journal entry per installment — debit {@code PIUTANG_BUNGA}, credit
	 * {@code PENDAPATAN_BUNGA}, {@code entry_date = due_date}, {@code ref_id = installment.id}
	 * (DM §1.13, TS §3).
	 *
	 * <p>Idempotent by state: an installment whose {@code recognized_interest_amount} already equals its
	 * {@code interest_amount} (or whose scheduled interest is zero) is left alone, so repeating the call
	 * posts nothing. {@code SETTLED} and {@code WRITTEN_OFF} installments are skipped — settlement
	 * recognizes its own accrued interest and a write-off caps the recognized receivable (Addendum §12,
	 * invariant 15).
	 *
	 * @param contractId   contract whose due interest is to be recognized
	 * @param businessDate business date the step runs for; the due-date window is
	 *                     {@code due_date <= businessDate} (inclusive, so interest is receivable <b>on</b>
	 *                     its due date)
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE (409 {@code CONTRACT_STATE_INVALID})
	 *                                   — only a serviced contract accrues billable interest
	 */
	void billDueInterest(UUID contractId, LocalDate businessDate);
}
