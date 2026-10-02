package com.serfira.shared.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.serfira.shared.api.ApiResponse;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ErrorCode;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * JWT resource-server security: authentication (ADR-005) and endpoint authorization (ADR-015).
 *
 * <p><b>Authentication.</b> Every request must present a valid HS256 bearer token, except the explicitly
 * public infrastructure paths (health, OpenAPI/Swagger). A token is valid only if it carries an {@code exp}
 * and a {@code sub} that is a UUID (an {@code app_user} id). Otherwise the decoder rejects it, so it never
 * becomes an authentication and the answer is 401 (ADR-015 D5, review CR-01). The chain is stateless and
 * CSRF is disabled because no ambient browser credential exists.
 *
 * <p><b>Authorization.</b> The Addendum §3.4 matrix lives in exactly one place, the matcher table in
 * {@link #securityFilterChain}, and ends in {@code denyAll()}: an endpoint nobody registered is 403 for an
 * authenticated caller and 401 for an anonymous one (ADR-015 D3). Roles come from the {@code roles} claim
 * ({@link RolesClaimAuthoritiesConverter}). Every new endpoint must add its row here with its 403 tests.
 *
 * <p>{@code AuditActorBindingFilter} turns the JWT subject into the audit actor, so financial writes are
 * attributable (Addendum §3.3).
 *
 * <p>Configuration (base64): {@code serfira.security.jwt.secret-base64} must decode to at least 32 bytes
 * (HS256). Missing/short secrets fail fast at startup. Set {@code SERFIRA_SECURITY_JWT_SECRET_BASE64} in the
 * environment; production must use its own key. Login/refresh/logout and {@code iss}/{@code aud}
 * validation remain T21 (F2).
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

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String FINANCE = AppRole.FINANCE.name();
	private static final String MANAJEMEN = AppRole.MANAJEMEN.name();

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
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256")).build();
		decoder.setJwtValidator(tokenValidator());
		return decoder;
	}

	/**
	 * Default validators plus the two identity rules of ADR-015 D5. {@code setJwtValidator} replaces the
	 * decoder's defaults, so they are delegated to explicitly. Spring Security 7 still accepts a token without
	 * {@code exp} by default, so the expiry requirement must be switched on here.
	 */
	static OAuth2TokenValidator<Jwt> tokenValidator() {
		JwtTimestampValidator expiryRequired = new JwtTimestampValidator();
		expiryRequired.setAllowEmptyExpiryClaim(false);
		JwtClaimValidator<Object> subjectIsActorId =
				new JwtClaimValidator<>(JwtClaimNames.SUB, ResourceServerSecurityConfiguration::isUuid);
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefault(), expiryRequired, subjectIsActorId);
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
		JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
		authenticationConverter.setJwtGrantedAuthoritiesConverter(new RolesClaimAuthoritiesConverter());

		http
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Addendum §3.4 matrix (ADR-015 D3). Order matters: the first matching rule wins.
				.authorizeHttpRequests(authorize -> authorize
						.requestMatchers(PUBLIC_PATHS.toArray(String[]::new)).permitAll()
						// Spring Security authorizes every dispatch. Without this rule a container error
						// dispatch to /error (e.g. a firewall rejection) would be denied and its status replaced
						// by 401/403. Clients cannot choose the dispatch type, so a direct GET /error is a
						// REQUEST dispatch and still falls through to denyAll.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts").hasRole(ADMIN)
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts/*/activate").hasRole(ADMIN)
						.requestMatchers(HttpMethod.POST, "/api/v1/payments").hasRole(ADMIN)
						.requestMatchers(HttpMethod.GET,
								"/api/v1/contracts", "/api/v1/contracts/*", "/api/v1/contracts/*/installments")
								.hasAnyRole(ADMIN, FINANCE, MANAJEMEN)
						.requestMatchers(HttpMethod.GET, "/api/v1/reports/aging")
								.hasAnyRole(ADMIN, FINANCE, MANAJEMEN)
						.anyRequest().denyAll())
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				// The resource-server configurer installs its own BearerTokenAuthenticationEntryPoint
				// for bearer-token requests, so the envelope handlers must be registered here too.
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt
								.decoder(jwtDecoder)
								.jwtAuthenticationConverter(authenticationConverter))
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.addFilterAfter(new AuditActorBindingFilter(auditContext), BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	/**
	 * A {@code sub} is a usable actor id only if it is a canonical lowercase 8-4-4-4-12 UUID that is not the
	 * seeded SYSTEM user (ADR-015 D4/D5, review CR-01). {@link UUID#fromString} alone is too lenient: it
	 * accepts loose forms such as {@code 0-0-0-0-1}, which parse to the SYSTEM id, so an HTTP token could be
	 * bound as the audit actor and write rows attributed to SYSTEM. Requiring the parsed value to re-serialize
	 * to the input rejects every non-canonical form, and the explicit SYSTEM check rejects its canonical form.
	 */
	private static boolean isUuid(Object subject) {
		if (!(subject instanceof String value)) {
			return false;
		}
		UUID parsed;
		try {
			parsed = UUID.fromString(value);
		} catch (IllegalArgumentException ex) {
			return false;
		}
		if (!parsed.toString().equals(value.toLowerCase(Locale.ROOT))) {
			return false;
		}
		return !parsed.equals(AuditContext.SYSTEM_USER_ID);
	}

	private static void writeEnvelopeError(
			HttpServletResponse response, int status, ErrorCode code, String message, ObjectMapper objectMapper)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getWriter(), ApiResponse.fail(ApiError.of(code, message)));
	}
}
