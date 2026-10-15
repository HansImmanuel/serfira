package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a payment cannot be voided because an installment it resolved is already {@code SETTLED} or
 * {@code WRITTEN_OFF}, or its contract is neither {@code ACTIVE} nor a {@code CLOSED}/{@code MATURITY}
 * contract the void can reopen (e.g. it was closed by a {@code SETTLEMENT} or terminated by a write-off)
 * — E4, task T16, ADR-020 D5/D1.3.
 *
 * <p>A void of the final payment <b>does</b> reopen a {@code CLOSED}/{@code MATURITY} contract (ADR-020
 * D1.3); only a {@code SETTLEMENT} close or a write-off/termination is rejected here. T16 never reverses
 * a settlement or write-off; reversing those closures is a separate, out-of-scope story. When rejected
 * the void <b>writes nothing</b>. The guard is evaluated over <b>every</b> installment the payment's
 * immutable allocations reference, not one assumed target.
 */
public final class PaymentNotVoidableException extends SerfiraException {

	public PaymentNotVoidableException(String message) {
		super(ErrorCode.PAYMENT_NOT_VOIDABLE, HttpStatus.CONFLICT, message);
	}
}
