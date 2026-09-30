package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStateException;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The {@code contract} module's seam for the aging step of the daily job (Addendum §7.3, DM §1.4,
 * ADR-013 A-4/A-5): marks a contract's arrears installments {@code OVERDUE} without the job ever touching the
 * {@code installment} table itself (02_TECH_SPEC.md §1).
 *
 * <p>Why a port of its own: the aging decision is a status transition of the {@code contract} state machine,
 * not money resolution ({@link InstallmentReceivablePort}), interest recognition ({@link InstallmentBillingPort})
 * or penalty recognition ({@link InstallmentPenaltyPort}). A single implementation per interface keeps
 * injection unambiguous.
 *
 * <p>The call must run inside the caller's transaction and never starts one: the daily job owns the aging
 * transaction, which is separate from its billing → penalty transaction (ADR-013 implementation note T6).
 */
public interface InstallmentAgingPort {

	/**
	 * Marks every {@code PENDING}/{@code PARTIALLY_PAID} installment of the contract whose first overdue date
	 * ({@code due_date + grace_period_days + 1}) has been reached on {@code businessDate} and that still has
	 * {@code outstanding > 0} as {@code OVERDUE}.
	 *
	 * <p>Idempotent by state: an installment already {@code OVERDUE} is left alone (no write, no version bump),
	 * and {@code PAID}, {@code SETTLED} and {@code WRITTEN_OFF} are never changed. Aging never moves an
	 * installment out of {@code OVERDUE}.
	 *
	 * @param contractId   contract to age
	 * @param businessDate business date the step runs for
	 * @return number of installments that became {@code OVERDUE} in this call, {@code >= 0}
	 * @throws ContractNotFoundException if no contract has that id (404 {@code CONTRACT_NOT_FOUND})
	 * @throws ContractStateException    if the contract is not ACTIVE or is ACTIVE without a schedule
	 *                                   (409 {@code CONTRACT_STATE_INVALID})
	 */
	int markOverdueInstallments(UUID contractId, LocalDate businessDate);
}
