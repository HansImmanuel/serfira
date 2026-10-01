package com.serfira.shared.security;

import java.util.Optional;

/**
 * Application roles, mirroring {@code ck_app_user_role} on {@code app_user.role} (Addendum §3.1).
 *
 * <p>The JWT {@code roles} claim carries these names verbatim and case-sensitively (ADR-015 D1).
 * {@link #SYSTEM} exists only for in-process jobs (Addendum §3.3, matrix column "—"), so it is never an
 * HTTP role: a token that carries it is denied as a whole (ADR-015 D4).
 */
public enum AppRole {
	ADMIN_OPERASIONAL,
	FINANCE,
	MANAJEMEN,
	SYSTEM;

	private static final String AUTHORITY_PREFIX = "ROLE_";

	/** The Spring Security authority for this role, as {@code hasRole(name())} expects it. */
	public String authority() {
		return AUTHORITY_PREFIX + name();
	}

	/** Whether an HTTP request may act under this role. Only {@link #SYSTEM} is excluded. */
	public boolean isHttpRole() {
		return this != SYSTEM;
	}

	/**
	 * Resolves a claim element to a role by exact name. Absence is expected for unknown or wrongly cased
	 * values, which the caller ignores (ADR-015 D1).
	 */
	public static Optional<AppRole> fromClaimValue(String claimValue) {
		for (AppRole role : values()) {
			if (role.name().equals(claimValue)) {
				return Optional.of(role);
			}
		}
		return Optional.empty();
	}
}
