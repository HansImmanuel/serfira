package com.serfira.shared.error;

import org.springframework.http.HttpStatus;

/**
 * 409 — a settlement quote no longer matches the contract it priced (E2, task T13, ADR-018 D9). Execution
 * re-prices the current components and compares them (and the live {@code contract.version}) against the
 * stored quote snapshot; any divergence means the contract moved on since the quote was taken, so the
 * execution is rejected and <b>writes nothing</b>. The caller must take a fresh quote.
 */
public final class StaleSettlementQuoteException extends SerfiraException {

	public StaleSettlementQuoteException(String message) {
		super(ErrorCode.STALE_SETTLEMENT_QUOTE, HttpStatus.CONFLICT, message);
	}
}
