package com.serfira.shared.outbox;

import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes one domain event into {@code outbox_events} inside the caller's business transaction (TS §2.7,
 * ADR-027). {@link Propagation#MANDATORY}: an event can only exist together with the change it reports, so a
 * call without a transaction is an error, and a rollback drops the event with the change.
 *
 * <p>The payload is serialized with the application {@link ObjectMapper}, so keys are snake_case and money is
 * a decimal string (ADR-024). Only the emitting services call this; controllers never write events.
 */
@Component
public class OutboxWriter {

	private final EntityManager entityManager;
	private final ObjectMapper objectMapper;
	private final OutboxEventIds ids;

	public OutboxWriter(EntityManager entityManager, ObjectMapper objectMapper, OutboxEventIds ids) {
		this.entityManager = entityManager;
		this.objectMapper = objectMapper;
		this.ids = ids;
	}

	/**
	 * Appends one {@code PENDING} event and returns its id, which is also the event id consumers deduplicate by.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID append(OutboxAggregateType aggregateType, UUID aggregateId, OutboxEventType eventType,
			OutboxPayload payload) {
		Objects.requireNonNull(eventType, "eventType");
		Objects.requireNonNull(payload, "payload");
		OutboxEvent event = new OutboxEvent(ids.next(), aggregateType, aggregateId, eventType,
				objectMapper.writeValueAsString(payload));
		entityManager.persist(event);
		return event.getId();
	}
}
