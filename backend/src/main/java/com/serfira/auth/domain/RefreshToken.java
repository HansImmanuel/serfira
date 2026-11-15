package com.serfira.auth.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * JPA entity for {@code refresh_token} (Addendum §3.1/§3.3, V1__baseline.sql).
 *
 * <p>Only the SHA-256 hash of the opaque refresh token is stored ({@code token_hash}); the raw
 * value is returned once to the client and never persisted or logged. T21a issues the row;
 * T21b owns rotation/revocation ({@code revoked_at}, {@code replaced_by_id}).
 *
 * <p>Audit columns are explicit rather than inherited from {@code Auditable}: the actor of a login
 * is the authenticating user, not the request's {@code AuditContext} (SYSTEM while unauthenticated).
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

	private static final int TOKEN_HASH_LENGTH = 64;

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, updatable = false, length = TOKEN_HASH_LENGTH)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private OffsetDateTime expiresAt;

	@Column(name = "revoked_at")
	private OffsetDateTime revokedAt;

	@Column(name = "replaced_by_id")
	private UUID replacedById;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "created_by", updatable = false)
	private UUID createdBy;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "updated_by")
	private UUID updatedBy;

	protected RefreshToken() {
		// JPA
	}

	/**
	 * Records a newly issued refresh token for a user.
	 *
	 * @param userId    the owning {@code app_user.id}
	 * @param tokenHash SHA-256 hex of the raw token, exactly 64 characters
	 * @param expiresAt absolute expiry instant
	 * @param now       issue time from the application clock
	 */
	public RefreshToken(UUID userId, String tokenHash, OffsetDateTime expiresAt, OffsetDateTime now) {
		this.userId = Objects.requireNonNull(userId, "userId");
		this.tokenHash = requireHash(tokenHash);
		this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
		this.createdBy = userId;
		this.updatedBy = userId;
	}

	public UUID getId() {
		return id;
	}

	public UUID getUserId() {
		return userId;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public OffsetDateTime getExpiresAt() {
		return expiresAt;
	}

	public OffsetDateTime getRevokedAt() {
		return revokedAt;
	}

	public void setRevokedAt(OffsetDateTime revokedAt) {
		this.revokedAt = revokedAt;
	}

	public UUID getReplacedById() {
		return replacedById;
	}

	public void setReplacedById(UUID replacedById) {
		this.replacedById = replacedById;
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

	public void setUpdatedAt(OffsetDateTime updatedAt) {
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
	}

	public UUID getUpdatedBy() {
		return updatedBy;
	}

	public void setUpdatedBy(UUID updatedBy) {
		this.updatedBy = updatedBy;
	}

	private static String requireHash(String tokenHash) {
		if (tokenHash == null || tokenHash.length() != TOKEN_HASH_LENGTH) {
			throw new IllegalArgumentException("tokenHash must be a 64-character SHA-256 hex value");
		}
		return tokenHash;
	}
}
