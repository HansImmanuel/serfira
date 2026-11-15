package com.serfira.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/login} (T21a, Addendum §3.2).
 *
 * <p>The bounds mirror the {@code app_user} column widths and keep an oversized password from
 * reaching the Argon2 KDF (ADR-016 resource-exhaustion posture). The password is never trimmed or
 * normalised.
 */
public record LoginRequest(
		@NotBlank @Size(max = 60) String username,
		@NotBlank @Size(max = 200) String password) {
}
