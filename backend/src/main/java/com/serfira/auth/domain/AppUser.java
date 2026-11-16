package com.serfira.auth.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import com.serfira.shared.security.AppRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;

/**
 * JPA entity for {@code app_user} (Addendum §3.1, V1__baseline.sql).
 *
 * <p>Deliberately not an {@code Auditable}: the table's audit columns are set explicitly here.
 * Login updates {@code last_login_at}, {@code failed_login_attempts}, {@code updated_at} and
 * {@code updated_by} for the authenticating user, whereas {@code Auditable} would stamp the
 * request's {@code AuditContext} actor (SYSTEM on an unauthenticated login request).
 *
 * <p>The seeded {@code SYSTEM} row is inactive and carries a non-Argon2 {@code password_hash}
 * (V8), so it can never be used to log in; it exists only as an audit/job principal.
 *
 * <p>Brute-force lockout (T21b, Addendum §3.1/§3.3) lives on this entity: a failed password is
 * counted, and reaching the configured threshold locks the account until a fixed window passes.
 * The counter arithmetic is here rather than in {@code AuthService} so the rule is unit-testable
 * without Spring or a database.
 */
@Entity
@Table(name = "app_user")
@DynamicUpdate
public class AppUser {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "username", nullable = false, length = 60)
	private String username;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "full_name", nullable = false, length = 120)
	private String fullName;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	private AppRole role;

	@Column(name = "is_active", nullable = false)
	private boolean isActive;

	@Column(name = "failed_login_attempts", nullable = false)
	private int failedLoginAttempts;

	@Column(name = "locked_until")
	private OffsetDateTime lockedUntil;

	@Column(name = "last_login_at")
	private OffsetDateTime lastLoginAt;

	@Column(name = "created_at", nullable = false)
	private OffsetDateTime createdAt;

	@Column(name = "created_by")
	private UUID createdBy;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	@Column(name = "updated_by")
	private UUID updatedBy;

	protected AppUser() {
		// JPA
	}

	/**
	 * Creates an interactive user. Test and fixture code uses this; production rows arrive through
	 * Flyway seeds (Addendum §18.3/§18.4).
	 */
	public AppUser(String username, String passwordHash, String fullName, AppRole role, boolean isActive,
			OffsetDateTime now) {
		this.username = Objects.requireNonNull(username, "username");
		this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
		this.fullName = Objects.requireNonNull(fullName, "fullName");
		this.role = Objects.requireNonNull(role, "role");
		this.isActive = isActive;
		this.createdAt = Objects.requireNonNull(now, "now");
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getFullName() {
		return fullName;
	}

	public AppRole getRole() {
		return role;
	}

	public boolean isActive() {
		return isActive;
	}

	public int getFailedLoginAttempts() {
		return failedLoginAttempts;
	}

	public OffsetDateTime getLockedUntil() {
		return lockedUntil;
	}

	public OffsetDateTime getLastLoginAt() {
		return lastLoginAt;
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

	/** Whether the account may be used for HTTP requests at all (SYSTEM acts only in-process). */
	public boolean isInteractive() {
		return role.isHttpRole();
	}

	/**
	 * Whether the lockout window is still in force. A lock that has elapsed does not count: the
	 * account is usable again without a write, so an abandoned lock never needs cleanup.
	 */
	public boolean isLockedAt(OffsetDateTime now) {
		return lockedUntil != null && now.isBefore(lockedUntil);
	}

	/**
	 * Counts one failed password and locks the account when the threshold is reached (T21b).
	 *
	 * <p>The window is measured from the failure that crosses the threshold, not from the first
	 * failure, so a slow trickle of attempts cannot keep an account locked indefinitely.
	 *
	 * @return whether this failure locked the account
	 */
	public boolean recordFailedLogin(int maxAttempts, Duration lockWindow, OffsetDateTime now) {
		Objects.requireNonNull(now, "now");
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		failedLoginAttempts++;
		this.updatedAt = now;
		this.updatedBy = id;
		if (failedLoginAttempts < maxAttempts) {
			return false;
		}
		this.lockedUntil = now.plus(Objects.requireNonNull(lockWindow, "lockWindow"));
		return true;
	}

	/** Records a successful authentication: clears the counter and any elapsed lock. */
	public void recordSuccessfulLogin(OffsetDateTime now) {
		Objects.requireNonNull(now, "now");
		this.failedLoginAttempts = 0;
		this.lockedUntil = null;
		this.lastLoginAt = now;
		this.updatedAt = now;
		this.updatedBy = id;
	}
}
