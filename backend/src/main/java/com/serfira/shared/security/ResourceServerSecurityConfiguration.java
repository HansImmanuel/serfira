package com.serfira.shared.security;

import java.io.IOException;
import java.time.Duration;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.serfira.shared.api.ApiResponse;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.error.ApiError;
import com.serfira.shared.error.ErrorCode;
import com.serfira.shared.observability.CorrelationId;

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
 * <p><b>Browser client.</b> CORS is the only browser-specific control and is configured here
 * (ADR-023): exact origins from {@code serfira.security.cors.allowed-origins}, an empty value allowing
 * none, no credentials, and a filter position before the matcher table so an untokened preflight is
 * answered instead of denied. CORS is not authorization — the table below remains the enforcement.
 *
 * <p>Configuration (base64): {@code serfira.security.jwt.secret-base64} must decode to at least 32 bytes
 * (HS256). Missing/short secrets fail fast at startup. Set {@code SERFIRA_SECURITY_JWT_SECRET_BASE64} in the
 * environment; production must use its own key. {@code POST /api/v1/auth/login} (T21a, ADR-022) mints
 * tokens from a real {@code app_user}; {@code /auth/refresh} and {@code /auth/logout} (T21b) are the other
 * two public token endpoints, authenticated by the refresh token in the body. {@code iss}/{@code aud}
 * validation remains deferred (ADR-005, CR-10).
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

	/** Used by the active-user validator (T21a, closes T7-D2, Addendum §3.3). */
	private final JdbcTemplate jdbcTemplate;

	public ResourceServerSecurityConfiguration(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

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
		decoder.setJwtValidator(tokenValidator(jdbcTemplate));
		return decoder;
	}

	/**
	 * Default validators plus the two identity rules of ADR-015 D5 and the active-user check that
	 * closes T7-D2 (Addendum §3.3). {@code setJwtValidator} replaces the decoder's defaults, so they
	 * are delegated to explicitly. Spring Security 7 still accepts a token without {@code exp} by
	 * default, so the expiry requirement must be switched on here.
	 *
	 * <p>The active-user check amends ADR-015 D2 ("no app_user lookup per request"): a token whose
	 * {@code sub} is unknown or belongs to an inactive user is rejected at the decoder, so it never
	 * becomes an authentication (401). The cost is one indexed primary-key lookup per authenticated
	 * request; in exchange, deactivating a user takes effect immediately instead of after the
	 * access token expires (ADR-022 D6).
	 */
	static OAuth2TokenValidator<Jwt> tokenValidator(JdbcTemplate jdbcTemplate) {
		JwtTimestampValidator expiryRequired = new JwtTimestampValidator();
		expiryRequired.setAllowEmptyExpiryClaim(false);
		JwtClaimValidator<Object> subjectIsActorId =
				new JwtClaimValidator<>(JwtClaimNames.SUB, ResourceServerSecurityConfiguration::isUuid);
		OAuth2TokenValidator<Jwt> activeUserValidator = jwt -> {
			String sub = jwt.getSubject();
			if (sub == null) {
				return OAuth2TokenValidatorResult.failure(
						new OAuth2Error("invalid_token", "Missing sub claim", null));
			}
			UUID userId;
			try {
				userId = UUID.fromString(sub);
			} catch (IllegalArgumentException ex) {
				return OAuth2TokenValidatorResult.failure(
						new OAuth2Error("invalid_token", "sub is not a valid UUID", null));
			}
			Boolean active = jdbcTemplate.queryForObject(
					"SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND is_active = TRUE)",
					Boolean.class, userId);
			if (!Boolean.TRUE.equals(active)) {
				return OAuth2TokenValidatorResult.failure(
						new OAuth2Error("invalid_token", "Unknown or inactive user", null));
			}
			return OAuth2TokenValidatorResult.success();
		};
		return new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(), expiryRequired, subjectIsActorId, activeUserValidator);
	}

	/**
	 * Browser CORS boundary (ADR-023). The allowlist is validated once at startup, so a malformed entry
	 * fails the application instead of silently never matching an {@code Origin} header.
	 *
	 * <p>{@code allowCredentials} stays false: the client keeps its tokens in memory and sends a bearer
	 * header, never a cookie, which is also why CSRF is disabled below. Wildcards are rejected by
	 * {@link AllowedOrigins} — with credentials off Spring would otherwise serve {@code *} happily.
	 */
	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${serfira.security.cors.allowed-origins:}") String allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(AllowedOrigins.parse(allowedOrigins));
		configuration.setAllowedMethods(List.of("GET", "POST"));
		configuration.setAllowedHeaders(
				List.of("Authorization", "Content-Type", "Idempotency-Key", CorrelationId.HEADER));
		configuration.setExposedHeaders(List.of(CorrelationId.HEADER));
		configuration.setAllowCredentials(false);
		configuration.setMaxAge(Duration.ofMinutes(30));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	@Bean
	SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtDecoder jwtDecoder,
			AuditContext auditContext,
			CorsConfigurationSource corsConfigurationSource,
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
				// The CorsFilter must run before authentication and authorization: a preflight carries no
				// token and is not a business request (ADR-023). WithCredentials stays false by policy.
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
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
						// Login is the token issuer, so it is reachable without a token; every business
						// role may call it (Addendum §3.4, T21a, ADR-022). It still has an explicit row
						// in this table rather than being outside it, so default-deny stays the rule.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						// Refresh and logout are the same by-design exception (T21b): they are called
						// precisely when the access token is dead or absent, and the refresh token in the
						// body is their credential. Requiring a bearer token would make rotation pointless
						// (ADR-022 D7). An invalid refresh token is still 401 from the service, never 403.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh", "/api/v1/auth/logout")
								.permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts").hasRole(ADMIN)
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts/*/activate").hasRole(ADMIN)
						.requestMatchers(HttpMethod.POST, "/api/v1/payments").hasRole(ADMIN)
						// Payment void is ADMIN_OPERASIONAL only (Addendum §3.4, T16, ADR-020). A single-segment
						// "*" means the exact "/api/v1/payments" rule above does not match "/*/void".
						.requestMatchers(HttpMethod.POST, "/api/v1/payments/*/void").hasRole(ADMIN)
						.requestMatchers(HttpMethod.GET,
								"/api/v1/contracts", "/api/v1/contracts/*", "/api/v1/contracts/*/installments")
								.hasAnyRole(ADMIN, FINANCE, MANAJEMEN)
						.requestMatchers(HttpMethod.GET, "/api/v1/reports/aging")
								.hasAnyRole(ADMIN, FINANCE, MANAJEMEN)
						// The statement is ADMIN_OPERASIONAL/FINANCE only (Addendum §3.4; MANAJEMEN -> 403).
						// A single-segment "*" means "/api/v1/contracts/*" above does not match "/*/statement".
						.requestMatchers(HttpMethod.GET, "/api/v1/contracts/*/statement")
								.hasAnyRole(ADMIN, FINANCE)
						// Credit apply is ADMIN_OPERASIONAL only; credit read is ADMIN_OPERASIONAL/FINANCE
						// (Addendum §3.4, T14). Single-segment "*" means "/api/v1/contracts/*" above does not
						// match "/*/credit" or "/*/credit/apply".
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts/*/credit/apply")
								.hasRole(ADMIN)
						.requestMatchers(HttpMethod.GET, "/api/v1/contracts/*/credit")
								.hasAnyRole(ADMIN, FINANCE)
						// Write-off is ADMIN_OPERASIONAL only (Addendum §3.4, T19, F5). Single-segment "*"
						// means "/api/v1/contracts/*" above does not match "/*/write-off".
						.requestMatchers(HttpMethod.POST, "/api/v1/contracts/*/write-off")
								.hasRole(ADMIN)
						// Penalty waive/reduce is ADMIN_OPERASIONAL only (Addendum §3.4, T15, ADR-019 D2).
						.requestMatchers(HttpMethod.POST, "/api/v1/penalty-adjustments")
								.hasRole(ADMIN)
						// Settlement quote is ADMIN_OPERASIONAL only (Addendum §3.4, T12, ADR-018).
						.requestMatchers(HttpMethod.POST, "/api/v1/settlements/quote")
								.hasRole(ADMIN)
						// Settlement execution is ADMIN_OPERASIONAL only (Addendum §3.4, T13, ADR-018). The
						// more specific "/quote" rule above is listed first, so this exact-path rule covers
						// only POST /api/v1/settlements.
						.requestMatchers(HttpMethod.POST, "/api/v1/settlements")
								.hasRole(ADMIN)
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
