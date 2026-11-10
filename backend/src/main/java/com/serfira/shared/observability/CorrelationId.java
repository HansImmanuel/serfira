package com.serfira.shared.observability;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * Correlation-id vocabulary and MDC keys for the observability baseline (T20, Addendum §10).
 *
 * <p>A single id — the <em>request id</em> — correlates every log line, the HTTP {@code X-Request-Id}
 * response header, and the {@code idempotency_keys.request_id} column of a claimed key, so a retry can be
 * traced back to the request that first created the resource. The id is a correlation aid only: it never
 * gates an authentication or idempotency decision (those are the JWT and the {@code (endpoint, key)} claim).
 *
 * <p>The MDC keys are the field names that land in the structured JSON log (Addendum §10 requires
 * {@code request_id}, {@code contract_id} when in scope, and {@code module}). {@code timestamp} and
 * {@code level} come from the Spring Boot structured encoder itself.
 */
public final class CorrelationId {

	/** HTTP header carrying the correlation id in and out. */
	public static final String HEADER = "X-Request-Id";

	/** MDC key / JSON field for the request correlation id. */
	public static final String REQUEST_ID = "request_id";

	/** MDC key / JSON field for the contract in scope, when a flow has one. */
	public static final String CONTRACT_ID = "contract_id";

	/** MDC key / JSON field for the owning module (e.g. {@code payment}, {@code daily-servicing}). */
	public static final String MODULE = "module";

	/** Maximum accepted length of a client-supplied id; longer values are replaced with a fresh UUID. */
	static final int MAX_LENGTH = 64;

	private CorrelationId() {
	}

	/** Current request id from the MDC, or {@code null} when none is bound (e.g. a background thread). */
	public static String current() {
		return MDC.get(REQUEST_ID);
	}

	/**
	 * Normalises a client-supplied {@code X-Request-Id}: a blank, absent, or over-long value yields a fresh
	 * random UUID so the field is always present and bounded. A usable client value is trimmed and kept, so
	 * an id propagated from an upstream caller survives.
	 */
	static String normalizeOrGenerate(String supplied) {
		if (supplied == null) {
			return UUID.randomUUID().toString();
		}
		String trimmed = supplied.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
			return UUID.randomUUID().toString();
		}
		return trimmed;
	}
}
