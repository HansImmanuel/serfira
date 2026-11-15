package com.serfira.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Semaphore;

import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.serfira.auth.domain.AppUser;
import com.serfira.auth.domain.RefreshToken;
import com.serfira.auth.infrastructure.AppUserRepository;
import com.serfira.auth.infrastructure.RefreshTokenRepository;
import com.serfira.shared.clock.Clock;

/**
 * Login and token issuance for T21a (Addendum §3.2–§3.3, ADR-022).
 *
 * <p>Verifies the password with Argon2id and, on success, resets {@code failed_login_attempts},
 * sets {@code last_login_at}, and issues:
 *
 * <ul>
 * <li>a 15-minute HS256 access token whose {@code sub} is the {@code app_user.id} and whose
 * {@code roles} is a one-element string array (ADR-015 D1);</li>
 * <li>an opaque refresh token, returned once to the client and stored only as a SHA-256 hash
 * (T21b implements rotation and revocation).</li>
 * </ul>
 *
 * <p>Every failure path returns the same 401 so the response cannot be used to enumerate users.
 * A dummy Argon2 verification is performed for an unknown username so the timing is comparable to
 * a known-user failure. Plaintext passwords and password/token hashes are never logged.
 */
@Service
public class AuthService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

	static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
	static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(7);

	private final AppUserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final byte[] jwtSecret;
	private final Clock clock;
	private final String dummyPasswordHash;

	private final Semaphore argon2Permits;

	public AuthService(
			AppUserRepository userRepository,
			RefreshTokenRepository refreshTokenRepository,
			PasswordEncoder passwordEncoder,
			@Value("${serfira.security.jwt.secret-base64}") String secretBase64,
			Clock clock,
			@Value("${serfira.auth.argon2.max-concurrent-verifications:2}") int maxConcurrentVerifications) {
		
		if (maxConcurrentVerifications < 1) {
			throw new IllegalArgumentException(
					"maxConcurrentVerifications must be at least 1");
    	}

		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.jwtSecret = decodeSecret(secretBase64);
		this.clock = clock;
		this.argon2Permits = new Semaphore(maxConcurrentVerifications);
		// A valid Argon2 hash of an unguessable value: lets an unknown username still pay one
		// Argon2 verification, so login timing does not reveal whether the username exists.
		this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/**
	 * Authenticates the given credentials and returns an access + refresh token pair.
	 *
	 * @throws AuthenticationFailedException for every authentication failure (unknown user, wrong
	 *                                       password, inactive account, non-interactive principal)
	 */
	@Transactional
	public LoginResponse login(LoginRequest request) {
		AppUser user = userRepository.findByUsername(request.username().trim()).orElse(null);
		boolean passwordMatches = verifyPassword(request.password(), user);
		if (user == null || !passwordMatches || !user.isActive() || !user.getRole().isHttpRole()) {
			// No username, role or disabled-state detail: the same answer for every cause.
			LOGGER.warn("Rejected login attempt");
			throw new AuthenticationFailedException();
		}

		OffsetDateTime now = clock.now();
		user.setFailedLoginAttempts(0);
		user.setLastLoginAt(now);
		user.setUpdatedAt(now);
		user.setUpdatedBy(user.getId());
		userRepository.save(user);

		String accessToken = mintAccessToken(user.getId(), user.getRole().name(), now);
		String rawRefreshToken = UUID.randomUUID().toString();
		RefreshToken refreshToken = new RefreshToken(
				user.getId(),
				sha256Hex(rawRefreshToken),
				now.plus(REFRESH_TOKEN_LIFETIME),
				now);
		refreshTokenRepository.save(refreshToken);

		return new LoginResponse(accessToken, rawRefreshToken);
	}

	private boolean verifyPassword(String rawPassword, AppUser user) {
		String hash = user == null ? dummyPasswordHash : user.getPasswordHash();
		// Only Argon2 encodings are accepted. The seeded SYSTEM row carries '{disabled}' (V8), so
		// it fails here without invoking the Argon2 parser (and without logging its warning).
		if (hash == null || !hash.startsWith("$argon2id$")) {
			return false;
		}

		if (!argon2Permits.tryAcquire()) {
			throw new AuthenticationCapacityExceededException();
		}

		try {
			return passwordEncoder.matches(rawPassword, hash);
		} finally {
			argon2Permits.release();
		}
	}

	private String mintAccessToken(UUID userId, String role, OffsetDateTime now) {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(userId.toString())
				.claim("roles", List.of(role))
				.issueTime(Date.from(now.toInstant()))
				.expirationTime(Date.from(now.plus(ACCESS_TOKEN_LIFETIME).toInstant()))
				.build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(new SecretKeySpec(jwtSecret, "HmacSHA256")));
			return jwt.serialize();
		} catch (JOSEException ex) {
			throw new IllegalStateException("Failed to mint access token", ex);
		}
	}

	private static byte[] decodeSecret(String secretBase64) {
		Objects.requireNonNull(secretBase64, "secretBase64");
		byte[] keyBytes = Base64.getDecoder().decode(secretBase64);
		if (keyBytes.length < 32) {
			throw new IllegalStateException("serfira.security.jwt.secret-base64 must decode to at least 32 bytes");
		}
		return keyBytes;
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}
}
