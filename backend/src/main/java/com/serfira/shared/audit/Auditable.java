package com.serfira.shared.audit;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Audit columns shared by every Serfira entity (Domain Model preamble):
 * {@code created_at / created_by / updated_at / updated_by}.
 *
 * <p>Values are populated by {@link AuditSupport} from the application {@code Clock} and {@code AuditContext},
 * with the seeded SYSTEM user as fallback actor. Bootstrap rows inserted directly via SQL keep {@code created_by}
 * {@code NULL} (documented exception, Addendum §3.3).
 */
@MappedSuperclass
public abstract class Auditable {

	@Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
	OffsetDateTime createdAt;

	@Column(name = "created_by", updatable = false)
	UUID createdBy;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	OffsetDateTime updatedAt;

	@Column(name = "updated_by")
	UUID updatedBy;

	@PrePersist
	void onPersist() {
		AuditSupport.onCreate(this);
	}

	@PreUpdate
	void onUpdate() {
		AuditSupport.onUpdate(this);
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getUpdatedAt() {
		return updatedAt;
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}
}