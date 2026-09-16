package com.serfira.shared.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.serfira.shared.api.ApiResponse;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * JWT resource-server security (review CRIT-1, ahead of the full auth story in Sprint 6b).
 *
 * <p>Default-deny: every request must present a valid bearer token, except the explicitly
 * public infrastructure paths (health, OpenAPI/Swagger). The chain is stateless and CSRF is
 * disabled because no ambient browser credential exists — authentication is per-request via
 * the {@code Authorization} header. Role-based endpoint restrictions follow the Addendum §3.4
 * matrix in Sprint 6b (F3); until then, authorization equals "authenticated".
 *
 * <p>{@code AuditActorBindingFilter} turns the JWT subject into the audit actor, so financial
 * writes are attributable from the first day endpoints exist (Addendum §3.3).
 *
 * <p>Configuration (base64): {@code serfira.security.jwt.secret-base64} must decode to at least
 * 32 bytes (HS256). Missing/short secrets fail fast at startup. Set
 * {@code SERFIRA_SECURITY_JWT_SECRET_BASE64} in the environment; production must use its own key.
 * Login/refresh/logout and the RBAC role matrix remain the Sprint 6b (F2/F3) stories.
 */
@Configuration
@EnableWebSecurity
public class ResourceServerSecurityConfiguration {

	private static final Logger LOGGER = LoggerFactory.getLogger(ResourceServerSecurityConfiguration.class);

	/**
	 * Infrastructure paths reachable without a bearer token. Deliberately minimal and
	 * read-only: health/info for probes, OpenAPI/Swagger for the documented API surface.
	 */
	private static final List<String> PUBLIC_PATHS = List.of(
			"/actuator/health", "/actuator/health/**", "/actuator/info",
			"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

	private static final String MISSING_SECRET_MESSAGE =
			"JWT signing key not configured; set serfira.security.jwt.secret-base64 (base64, >= 32 bytes)";

	@Bean
	JwtDecoder jwtDecoder(@Value("${serfira.security.jwt.secret-base64:}") String secretBase64) {
		if (secretBase64 == null || secretBase64.isBlank()) {
			throw new IllegalStateException(MISSING_SECRET_MESSAGE);
		}
		byte[] keyBytes;
		try {
			keyBytes = Base64.getDecoder().decode(secretBase64);
		} catch (IllegalArgumentException ex) {
			throw new IllegalStateException("serfira.security.jwt.secret-base64 is not valid base64", ex);
		}
		if (keyBytes.length < 32) {
			throw new IllegalStateException("serfira.security.jwt.secret-base64 must decode to at least 32 bytes");
		}
		return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256")).build();
	}

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtDecoder jwtDecoder,
			AuditContext auditContext,
			ObjectMapper objectMapper) throws Exception {
		AuthenticationEntryPoint authenticationEntryPoint = (request, response, authException) -> {
			LOGGER.warn("Rejected unauthenticated request to {}: {}", request.getRequestURI(), authException.getMessage());
			writeEnvelopeError(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
					"Valid bearer token required", objectMapper);
		};
		AccessDeniedHandler accessDeniedHandler = (request, response, accessDeniedException) -> {
			LOGGER.warn("Denied authorized request to {}: {}", request.getRequestURI(), accessDeniedException.getMessage());
			writeEnvelopeError(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN,
					"Access denied", objectMapper);
		};

		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(authorize -> {
					authorize.requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll();
					authorize.anyRequest().authenticated();
				})
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				// The resource-server configurer installs its own BearerTokenAuthenticationEntryPoint
				// for bearer-token requests, so the envelope handlers must be registered here too.
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.decoder(jwtDecoder))
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterAfter(new AuditActorBindingFilter(auditContext), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	private static void writeEnvelopeError(
			HttpServletResponse response, int status, ErrorCode code, String message, ObjectMapper objectMapper)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ApiError.of(code, message)));
	}
}
