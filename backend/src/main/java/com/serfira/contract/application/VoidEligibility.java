package com.serfira.contract.application;

import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.ContractStatus;

import java.util.List;

/**
 * The contract-and-installments eligibility snapshot a payment void checks before it reverses anything
 * (E4, task T16, ADR-020 D5), returned by {@link PaymentVoidPort#checkVoidEligibility}.
 *
 * <p>The void is eligible when the contract is {@code ACTIVE}, or {@code CLOSED} with
 * {@code closedReason == MATURITY} — the latter so voiding the final payment can undo a MATURITY
 * auto-close (ADR-020 D1.3). A {@code SETTLEMENT} close, a {@code TERMINATED}/write-off, or a
 * {@code SETTLED}/{@code WRITTEN_OFF} installment makes it ineligible; reversing those is out of scope.
 *
 * @param contractStatus       the current status of the payment's contract
 * @param closedReason         why the contract is closed, or {@code null} when it is not {@code CLOSED}
 * @param affectedInstallments one entry per installment the void would recompute, with its current status
 */
public record VoidEligibility(ContractStatus contractStatus, ClosedReason closedReason,
		List<VoidAffectedInstallment> affectedInstallments) {

	public VoidEligibility {
		affectedInstallments = List.copyOf(affectedInstallments);
	}

	/**
	 * Whether a void may proceed against this contract (ADR-020 D1.3/D5): {@code ACTIVE}, or a
	 * {@code CLOSED}/{@code MATURITY} contract whose MATURITY auto-close the void can reopen.
	 */
	public boolean contractAllowsVoid() {
		return contractStatus == ContractStatus.ACTIVE
				|| (contractStatus == ContractStatus.CLOSED && closedReason == ClosedReason.MATURITY);
	}
}
