package com.serfira.shared.outbox;

/**
 * The ADR-027 event catalogue. Constants are named exactly as the stored {@code outbox_events.event_type}
 * value, so a consumer reads {@code PaymentReceived}, not a Java-style {@code PAYMENT_RECEIVED}.
 */
public enum OutboxEventType {
	PaymentReceived,
	PaymentVoided,
	ContractActivated,
	ContractClosed,
	ContractWrittenOff,
	SettlementExecuted
}
