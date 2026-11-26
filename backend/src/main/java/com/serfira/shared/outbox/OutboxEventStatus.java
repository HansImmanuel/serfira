package com.serfira.shared.outbox;

/** Delivery state of one outbox row ({@code ck_outbox_status}, V1). */
public enum OutboxEventStatus {
	PENDING,
	SENT,
	FAILED
}
