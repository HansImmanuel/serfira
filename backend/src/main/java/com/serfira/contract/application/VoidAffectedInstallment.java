package com.serfira.contract.application;

import com.serfira.contract.domain.InstallmentStatus;

import java.util.UUID;

/**
 * The eligibility view of one installment a payment void touches (E4, task T16, ADR-020 D5), returned by
 * {@link PaymentVoidPort#checkVoidEligibility}.
 *
 * <p>The {@code payment} module hands the port the set of installment ids its immutable allocations
 * reference and gets back each installment's current resolution status plus the contract's status, so it
 * can reject the void (409 {@code PAYMENT_NOT_VOIDABLE}) when any installment is terminal
 * ({@code SETTLED}/{@code WRITTEN_OFF}) or the contract is no longer {@code ACTIVE}. The decision stays in
 * one place ({@code payment}'s command service), but the data is read inside the contract module that owns
 * the rows.
 *
 * @param installmentId the installment the void would recompute
 * @param status        its current resolution status
 */
public record VoidAffectedInstallment(UUID installmentId, InstallmentStatus status) {
}
