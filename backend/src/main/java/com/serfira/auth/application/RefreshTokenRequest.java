package com.serfira.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/auth/refresh} and {@code POST /api/v1/auth/logout}
 * (T21b, Addendum §3.2).
 *
 * <p>Both endpoints take the same single opaque value, so they share one record. The length bound
 * is well above the 36-character UUID the server issues and keeps an oversized body away from the
 * SHA-256 lookup (ADR-016 posture). The value is never trimmed or normalised: a token either
 * matches its digest exactly or is rejected.
 */
public record RefreshTokenRequest(@NotBlank @Size(max = 100) String refreshToken) {
}
