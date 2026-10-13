package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStatus;

import java.util.List;

/**
 * The contract-and-installments eligibility snapshot a payment void checks before it reverses anything
 * (E4, task T16, ADR-020 D5), returned by {@link PaymentVoidPort#checkVoidEligibility}.
 *
 * @param contractStatus       the current status of the payment's contract (void requires {@code ACTIVE})
 * @param affectedInstallments one entry per installment the void would recompute, with its current status
 */
public record VoidEligibility(ContractStatus contractStatus, List<VoidAffectedInstallment> affectedInstallments) {

	public VoidEligibility {
		affectedInstallments = List.copyOf(affectedInstallments);
	}
}
