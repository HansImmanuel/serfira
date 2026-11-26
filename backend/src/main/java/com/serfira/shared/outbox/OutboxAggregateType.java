package com.serfira.shared.outbox;

/** The aggregate an outbox event reports on ({@code outbox_events.aggregate_type}, ADR-027). */
public enum OutboxAggregateType {
	PAYMENT,
	CONTRACT,
	SETTLEMENT
}
