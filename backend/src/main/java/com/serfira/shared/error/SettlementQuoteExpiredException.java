package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a settlement quote is past its TTL at execution time (E2, task T13, ADR-018 D9): {@code clock.now()
 * > valid_until}, or the quote has already been marked EXPIRED. A stale-by-time quote is rejected and
 * <b>writes nothing</b>; the caller must take a fresh quote.
 */
public final class SettlementQuoteExpiredException extends SerfiraException {

	public SettlementQuoteExpiredException(String message) {
		super(ErrorCode.SETTLEMENT_QUOTE_EXPIRED, HttpStatus.CONFLICT, message);
	}
}
