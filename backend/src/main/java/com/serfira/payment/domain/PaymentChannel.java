package com.serfira.payment.domain;

/**
 * How a payment arrived (03_DOMAIN_MODEL.md §1.7, PRD P-1; V1 {@code ck_payment_channel}).
 *
 * <p>MVP records manual cash/transfer collection. {@link #VA_STUB} and {@link #EWALLET_STUB} are the
 * documented placeholders for an integrated virtual account / e-wallet channel: they are accepted and
 * stored, but no channel integration exists yet, so the value only describes how the operator says the
 * money arrived.
 *
 * <p>Stored in a VARCHAR(20) column with a CHECK constraint, so this vocabulary is also the persisted one.
 */
public enum PaymentChannel {

	/** Cash received at the counter. */
	CASH,

	/** Manual bank transfer, verified by the operator. */
	BANK_TRANSFER,

	/** Virtual account payment (stub — no channel integration yet). */
	VA_STUB,

	/** E-wallet payment (stub — no channel integration yet). */
	EWALLET_STUB
}
