package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 - a credit application asked for more than the contract's available credit balance (T14, T33 review).
 *
 * <p>Deliberately not the generic {@code CONFLICT}: on the idempotent write endpoints {@code CONFLICT} means
 * "this {@code Idempotency-Key} was already used for a different request" (ADR-017), which a client must
 * treat as a spent key. This is an ordinary business rejection - the apply rolled back with its claim, so
 * the same key stays usable for a corrected amount.
 */
public final class CreditExceedsBalanceException extends SerfiraException {

	public CreditExceedsBalanceException(String message) {
		super(ErrorCode.CREDIT_EXCEEDS_BALANCE, HttpStatus.CONFLICT, message);
	}
}
