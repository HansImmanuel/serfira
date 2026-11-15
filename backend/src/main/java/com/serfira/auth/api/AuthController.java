package com.serfira.auth.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.serfira.auth.application.AuthService;
import com.serfira.auth.application.LoginRequest;
import com.serfira.auth.application.LoginResponse;
import com.serfira.shared.api.ApiResponse;

import jakarta.validation.Valid;

/**
 * Login endpoint (T21a, Addendum §3.2/§3.4).
 *
 * <p>{@code POST /api/v1/auth/login} has its own row in the matcher table
 * ({@code ResourceServerSecurityConfiguration}) and is {@code permitAll}: it mints the first token,
 * so it must be reachable without one. All three business roles may use it, and the token's roles
 * come from the stored {@code app_user.role}, never from the request body.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	/**
	 * Authenticates the given credentials and returns an access + refresh token pair (ADR-022).
	 *
	 * <p>200 on success; 401 {@code UNAUTHORIZED} for every authentication failure; 400
	 * {@code VALIDATION_ERROR} for a missing or oversized field.
	 */
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
		return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
	}
}
