package com.serfira.auth.application;

/**
 * Response body for {@code POST /api/v1/auth/login} (T21a, Addendum §3.2).
 *
 * <p>The global {@code SNAKE_CASE} Jackson strategy serialises these fields as
 * {@code access_token} and {@code refresh_token} (ADR-006).
 *
 * @param accessToken  15-minute HS256 JWT whose {@code sub} is the {@code app_user.id} UUID (ADR-022)
 * @param refreshToken opaque token to present to {@code POST /api/v1/auth/refresh} (T21b); only its
 *                     SHA-256 hash is stored server-side
 */
public record LoginResponse(String accessToken, String refreshToken) {
}
