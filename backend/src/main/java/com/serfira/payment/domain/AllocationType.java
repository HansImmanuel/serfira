package com.serfira.payment.domain;

/**
 * Component a payment allocation resolves (03_DOMAIN_MODEL.md §1.8, PRD §3).
 *
 * <p>The waterfall order of an installment's components is {@link #PENALTY} → {@link #INTEREST} →
 * {@link #PRINCIPAL} (PRD §3 "denda → bunga → pokok", DM §1.8). {@link #EXCESS} is the remainder that
 * could not be applied to any due installment: it carries <b>no</b> installment
 * ({@code payment_allocation.installment_id IS NULL} exactly for EXCESS, V1 {@code ck_payment_allocation_excess}),
 * never raises {@code installment.paid_amount}, and becomes customer credit (DM invariant 6, PRD P-4).
 *
 * <p>Stored in a VARCHAR(20) column with a CHECK constraint, so this vocabulary is also the persisted one.
 */
public enum AllocationType {

	/** Penalty component of a due installment (recognized by the daily penalty accrual, DT §4.3). */
	PENALTY,

	/** Interest component that has been recognized/billed (never the future scheduled interest). */
	INTEREST,

	/** Financing principal component. */
	PRINCIPAL,

	/** Payment remainder held as customer credit; no installment is attached. */
	EXCESS
}
