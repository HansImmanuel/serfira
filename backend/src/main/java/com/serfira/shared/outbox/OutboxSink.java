package com.serfira.shared.outbox;

/**
 * Where the publisher delivers an outbox event (ADR-027). Today {@link LoggingOutboxSink} writes it to the
 * structured log; a broker later replaces only this bean. A sink signals a failed delivery by throwing, and
 * must tolerate a re-delivery: the publisher is at-least-once and consumers deduplicate by event id.
 */
public interface OutboxSink {

	void publish(OutboxEvent event);
}
