package com.serfira.auth.application;

/**
 * Response body for the two token-minting endpoints — {@code POST /api/v1/auth/login} and
 * {@code POST /api/v1/auth/refresh} (Addendum §3.2, ADR-022 D3/D5).
 *
 * <p>The global {@code SNAKE_CASE} Jackson strategy serialises these fields as
 * {@code access_token} and {@code refresh_token} (ADR-006). The refresh token travels in the body
 * rather than an `HttpOnly` cookie: X-14 was settled in favour of the body for this phase because
 * no browser client exists yet, which keeps the filter chain free of ambient credentials, CSRF
 * handling and CORS credentials (ADR-022 addendum D10). The client holds it in memory.
 *
 * @param accessToken  15-minute HS256 JWT whose {@code sub} is the {@code app_user.id} UUID (ADR-022)
 * @param refreshToken opaque token for {@code POST /api/v1/auth/refresh}; only its SHA-256 hash is
 *                     stored server-side, and each presentation returns a different one (rotation)
 */
public record TokenPairResponse(String accessToken, String refreshToken) {
}
