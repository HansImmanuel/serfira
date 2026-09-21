package com.serfira.shared.idempotency;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA mapping of {@code idempotency_keys} (03_DOMAIN_MODEL.md §1.15, 02_TECH_SPEC.md §2.5).
 *
 * <p>One row per {@code (endpoint, key)}: the row is claimed when a retry-safe mutation starts and
 * carries the stored response once the mutation committed. Key scope is endpoint-scoped, so the
 * same client key may be reused on a different endpoint.
 *
 * <p>Only {@code response_json} and {@code status} are ever mutated (the claim itself is an
 * insert); everything else is written once at claim time.
 */
@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
		@UniqueConstraint(name = "uk_idempotency_key_endpoint", columnNames = {"endpoint", "key"})
})
public class IdempotencyKey extends Auditable {

	/** Claimed but not yet completed — only visible inside the claiming transaction. */
	public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";

	/** The mutation committed and the response is replayable. */
	public static final String STATUS_COMPLETED = "COMPLETED";

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "key", nullable = false, length = 80)
	private String key;

	@Column(name = "endpoint", nullable = false, length = 120)
	private String endpoint;

	@Column(name = "request_hash", length = 64)
	private String requestHash;

	@Column(name = "response_json", columnDefinition = "text")
	private String responseJson;

	@Column(name = "status", length = 20)
	private String status;

	@Column(name = "expires_at", columnDefinition = "timestamptz")
	private OffsetDateTime expiresAt;

	protected IdempotencyKey() {
		// JPA
	}

	public UUID getId() {
		return id;
	}

	public String getKey() {
		return key;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public String getResponseJson() {
		return responseJson;
	}

	public String getStatus() {
		return status;
	}

	public OffsetDateTime getExpiresAt() {
		return expiresAt;
	}

	/** Stores the response of the completed mutation so an identical retry can be replayed. */
	void complete(String storedResponseJson) {
		this.responseJson = storedResponseJson;
		this.status = STATUS_COMPLETED;
	}
}