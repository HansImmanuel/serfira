package com.serfira.payment.domain;

/**
 * Lifecycle state of a payment (03_DOMAIN_MODEL.md §1.7, V1 {@code ck_payment_status}).
 *
 * <p>Voiding is a reversal, never a deletion (PRD P-5): a voided payment keeps its row and its
 * allocations as history; the allocations merely stop counting as <b>active</b> (DM §1.8). The void
 * flow is story E4 — the payment write path (C3) only ever writes {@link #POSTED}.
 */
public enum PaymentStatus {

	/** Money received and allocated; counts as an active resolution of its installments. */
	POSTED,

	/** Reversed by a VOID payment event (E4). Kept for the audit trail, never deleted. */
	VOIDED
}
