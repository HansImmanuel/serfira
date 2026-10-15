package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a payment cannot be voided because the customer credit its EXCESS booked has already been
 * (partially or fully) applied or consumed by a settlement (E4, task T16, ADR-020 D4).
 *
 * <p>Unwinding spent credit would cascade into other posted events (a credit application or a
 * settlement), which is out of the MVP scope. The void is rejected and <b>writes nothing</b>; the
 * eligibility check runs under a row lock on the {@code contract_credit} row, so a concurrent consumer
 * cannot slip between the check and the reversal.
 */
public final class PaymentCreditConsumedException extends SerfiraException {

	public PaymentCreditConsumedException(String message) {
		super(ErrorCode.PAYMENT_CREDIT_CONSUMED, HttpStatus.CONFLICT, message);
	}
}
