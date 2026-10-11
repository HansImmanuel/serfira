package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a settlement quote has already been executed into a settlement (E2, task T13, ADR-018 D9). A quote
 * executes at most once; a second execution with a <b>different</b> {@code Idempotency-Key} is rejected (an
 * identical key replays the stored response through the idempotency path instead). The
 * {@code uk_settlement_quote_id} unique constraint — one settlement per quote — is the database backstop.
 */
public final class SettlementQuoteAlreadyExecutedException extends SerfiraException {

	public SettlementQuoteAlreadyExecutedException(String message) {
		super(ErrorCode.SETTLEMENT_QUOTE_ALREADY_EXECUTED, HttpStatus.CONFLICT, message);
	}
}
