package com.serfira.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit columns of an <b>append-only</b> row: {@code created_at} / {@code created_by} only.
 *
 * <p>Posted financial rows can never be updated — V1's {@code block_modification()} triggers reject
 * UPDATE/DELETE on {@code journal_entry}, {@code journal_line} and the other immutable tables — so they
 * deliberately carry no {@code updated_at}/{@code updated_by} (V1 baseline: "journal_* keep
 * updated_at/updated_by NULL (immutable)"). Extending {@link Auditable} would write audit columns the
 * schema documents as absent, hence this narrower base class.
 *
 * <p>Values are populated by {@link AuditSupport} from the application {@code Clock} and
 * {@code AuditContext}, with the seeded SYSTEM user as fallback actor.
 */
@MappedSuperclass
public abstract class ImmutableAuditable {

	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
	OffsetDateTime createdAt;

	@Column(name = "created_by", updatable = false)
	UUID createdBy;

	@PrePersist
	void onPersist() {
		AuditSupport.onCreate(this);
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}
}
