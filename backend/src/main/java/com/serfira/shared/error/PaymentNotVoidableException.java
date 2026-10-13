package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a payment cannot be voided because an installment it resolved is already {@code SETTLED} or
 * {@code WRITTEN_OFF}, or its contract is no longer {@code ACTIVE} (closed {@code SETTLEMENT} /
 * {@code MATURITY} / terminated by a write-off) — E4, task T16, ADR-020 D5.
 *
 * <p>T16 never reopens a closed contract or reverses a settlement/write-off; reversing those closures is
 * a separate, out-of-scope story. The void is rejected and <b>writes nothing</b>. The guard is evaluated
 * over <b>every</b> installment the payment's immutable allocations reference, not one assumed target.
 */
public final class PaymentNotVoidableException extends SerfiraException {

	public PaymentNotVoidableException(String message) {
		super(ErrorCode.PAYMENT_NOT_VOIDABLE, HttpStatus.CONFLICT, message);
	}
}
