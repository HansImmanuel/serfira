package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — an {@code Idempotency-Key} is being reused after its retention window has elapsed.
 *
 * <p>A key is single-use forever per endpoint (ADR-017, option A, amends ADR-007 decision 9); the
 * retention window only bounds how long the stored response is replayed. Once it has elapsed the
 * key is neither replayed nor silently re-run — the request is rejected before any business code
 * executes, so no billing, accrual, contract or payment work is done. The caller must mint a fresh
 * key for a genuinely new request.
 */
public final class IdempotencyKeyExpiredException extends SerfiraException {

	public IdempotencyKeyExpiredException(String message) {
		super(ErrorCode.IDEMPOTENCY_KEY_EXPIRED, HttpStatus.CONFLICT, message);
	}
}
