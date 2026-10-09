package com.serfira.settlement.domain;

/**
 * Lifecycle of a settlement quote (DM §1.5, ADR-018): a quote is {@code QUOTED} when priced, moves to
 * {@code EXECUTED} when a settlement consumes it (T13), or {@code EXPIRED} once its TTL passes. Mirrors
 * {@code ck_settlement_quote_status} on {@code settlement_quote.status} (V1).
 */
public enum SettlementQuoteStatus {
	QUOTED,
	EXECUTED,
	EXPIRED
}
