package com.serfira.shared.outbox;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of {@code outbox_events} (V1, DM §1.15): a domain event written in its business transaction and
 * drained by the publisher (ADR-027). The id is assigned by {@link OutboxEventIds}, never by the database, so
 * events written in one transaction keep their write order. The audit columns come from {@link Auditable}:
 * {@code created_by} is the request's actor, {@code updated_by} the publisher's SYSTEM actor.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent extends Auditable {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_type", nullable = false, updatable = false, length = 60)
	private OutboxAggregateType aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 80)
	private OutboxEventType eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "jsonb")
	private String payloadJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private OutboxEventStatus status;

	@Column(name = "sent_at", columnDefinition = "timestamptz")
	private OffsetDateTime sentAt;

	@Column(name = "retry_count", nullable = false)
	private int retryCount;

	protected OutboxEvent() {
		// JPA
	}

	OutboxEvent(UUID id, OutboxAggregateType aggregateType, UUID aggregateId, OutboxEventType eventType,
			String payloadJson) {
		this.id = Objects.requireNonNull(id, "id");
		this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType");
		this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId");
		this.eventType = Objects.requireNonNull(eventType, "eventType");
		this.payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
		this.status = OutboxEventStatus.PENDING;
	}

	/** The sink accepted the event: it is delivered and is never claimed again. */
	public void markSent(OffsetDateTime sentAt) {
		requirePending();
		this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
		this.status = OutboxEventStatus.SENT;
	}

	/**
	 * The sink refused the event. Counts the failed attempt; once {@code maxRetries} attempts have failed the
	 * row is {@code FAILED} and is no longer claimed.
	 *
	 * @return {@code true} when this failure made the row {@code FAILED}
	 */
	public boolean recordFailure(int maxRetries) {
		if (maxRetries < 1) {
			throw new IllegalArgumentException("maxRetries must be positive");
		}
		requirePending();
		retryCount++;
		if (retryCount >= maxRetries) {
			status = OutboxEventStatus.FAILED;
			return true;
		}
		return false;
	}

	private void requirePending() {
		if (status != OutboxEventStatus.PENDING) {
			throw new IllegalStateException("outbox event " + id + " is already " + status);
		}
	}

	public UUID getId() {
		return id;
	}

	public OutboxAggregateType getAggregateType() {
		return aggregateType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public OutboxEventType getEventType() {
		return eventType;
	}

	public String getPayloadJson() {
		return payloadJson;
	}

	public OutboxEventStatus getStatus() {
		return status;
	}

	public OffsetDateTime getSentAt() {
		return sentAt;
	}

	public int getRetryCount() {
		return retryCount;
	}
}
