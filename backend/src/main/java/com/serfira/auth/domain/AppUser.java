package com.serfira.auth.domain;

import java.time.OffsetDateTime;
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
import lombok.Getter;
import lombok.Setter;

import org.hibernate.annotations.DynamicUpdate;

/**
 * JPA entity for {@code app_user} (Addendum §3.1, V1__baseline.sql).
 *
 * <p>Deliberately not an {@code Auditable}: the table's audit columns are set explicitly here.
 * Login updates {@code last_login_at}, {@code failed_login_attempts}, {@code updated_at} and
 * {@code updated_by} for the authenticating user, whereas {@code Auditable} would stamp the
 * request's {@code AuditContext} actor (SYSTEM on an unauthenticated login request). Lockout fields
 * are updated by T21b.
 *
 * <p>The seeded {@code SYSTEM} row is inactive and carries a non-Argon2 {@code password_hash}
 * (V8), so it can never be used to log in; it exists only as an audit/job principal.
 */
@Entity
@Table(name = "app_user")
@DynamicUpdate
@Getter
@Setter
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
}
