package com.serfira.shared.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The log-based sink (ADR-027, TS §2.7): one INFO line per event whose key-value pairs — event id, type,
 * aggregate and the raw payload JSON — become fields of the ECS line (T20). The payload is PII-free by
 * construction ({@link OutboxPayload}), so logging it verbatim respects Addendum §9.
 */
@Component
public class LoggingOutboxSink implements OutboxSink {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoggingOutboxSink.class);

	@Override
	public void publish(OutboxEvent event) {
		LOGGER.atInfo()
				.addKeyValue("event_id", event.getId())
				.addKeyValue("event_type", event.getEventType())
				.addKeyValue("aggregate_type", event.getAggregateType())
				.addKeyValue("aggregate_id", event.getAggregateId())
				.addKeyValue("payload", event.getPayloadJson())
				.log("Published outbox event");
	}
}
