package com.serfira.shared.outbox;

/**
 * Marker for an outbox event payload (ADR-027). Every payload is a dedicated record, never a response DTO,
 * whose first component is {@code int schemaVersion} set to {@link #SCHEMA_VERSION}.
 *
 * <p>Allowed: ids, business numbers, amounts, enums and business dates/instants. Never the customer's name,
 * NIK, phone or address, and no free text (no write-off or void reason): the publisher writes the payload to
 * the log, and Addendum §9 forbids PII in logs.
 */
public interface OutboxPayload {

	/** The payload schema version every T38 event carries as {@code schema_version}. */
	int SCHEMA_VERSION = 1;
}
