package com.serfira.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.serfira.auth.application.AuthService;
import com.serfira.auth.application.LoginRequest;
import com.serfira.auth.application.RefreshTokenRequest;
import com.serfira.auth.application.TokenPairResponse;
import com.serfira.shared.api.ApiResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

/**
 * Authentication endpoints: login, refresh rotation and logout (T21a/T21b, Addendum §3.2/§3.4).
 *
 * <p>All three have their own rows in the matcher table
 * ({@code ResourceServerSecurityConfiguration}) and are {@code permitAll}. Login mints the first
 * token, and refresh/logout are called precisely when the access token is dead or absent, so they
 * cannot require one without making rotation pointless (ADR-022 D7). The refresh token itself is
 * the credential they authenticate with. Every business role may call them; the token's roles come
 * from the stored {@code app_user.role}, never from the request body.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Login, refresh rotation and logout")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/**
	 * Authenticates the given credentials and returns an access + refresh token pair (ADR-022).
	 *
	 * <p>200 on success; 401 {@code UNAUTHORIZED} for every authentication failure; 423
	 * {@code ACCOUNT_LOCKED} while a brute-force lock is in force; 503
	 * {@code AUTHENTICATION_CAPACITY_EXCEEDED} when the Argon2 semaphore is saturated; 400
	 * {@code VALIDATION_ERROR} for a missing or oversized field.
	 */
	@PostMapping("/login")
	@Operation(summary = "Log in and mint an access + refresh token pair",
			description = """
					Verifies the password with Argon2id and returns a 15-minute access token plus an opaque
					refresh token. Every credential failure answers the same 401 so the response cannot be
					used to enumerate usernames. A locked account answers 423 with the remaining window.
					""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Token pair issued"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - invalid credentials or inactive account"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "423",
					description = "ACCOUNT_LOCKED - brute-force lockout window is still in force"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
					description = "AUTHENTICATION_CAPACITY_EXCEEDED - too many concurrent verifications")})
	public ResponseEntity<ApiResponse<TokenPairResponse>> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
	}

	/**
	 * Rotates a refresh token: revokes the presented one and issues a new pair (T21b, Addendum
	 * §3.3). A reused, revoked, logged-out or expired token answers 401 and mints nothing.
	 */
	@PostMapping("/refresh")
	@Operation(summary = "Rotate a refresh token into a new token pair",
			description = """
					Single-use rotation: the presented refresh token is revoked and linked to its replacement,
					and a new access + refresh pair is returned. The old token never works again; presenting it
					answers 401 (replay). Expired, logged-out and deactivated-account tokens answer the same 401.
					""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "New token pair issued; the presented refresh token is revoked"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
					description = "UNAUTHORIZED - invalid, expired, replayed or revoked refresh token")})
	public ResponseEntity<ApiResponse<TokenPairResponse>> refresh(
			@Valid @RequestBody RefreshTokenRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(authService.refresh(request)));
	}

	/**
	 * Revokes the presented refresh token (T21b). Idempotent: an unknown or already-revoked token
	 * is a no-op, so the endpoint always answers 200 and cannot be used to probe for a token.
	 */
	@PostMapping("/logout")
	@Operation(summary = "Revoke a refresh token",
			description = """
					Revokes the presented refresh token so it can no longer be rotated. Idempotent: an unknown,
					expired or already-revoked token still answers 200 with a null body, so logging out twice is
					safe and the response reveals nothing about the token.
					""")
	@ApiResponses({
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
					description = "Refresh token revoked, or already unusable (no-op)"),
			@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
					description = "VALIDATION_ERROR")})
	public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
		authService.logout(request);
		return ResponseEntity.ok(ApiResponse.ok(null));
	}
}
