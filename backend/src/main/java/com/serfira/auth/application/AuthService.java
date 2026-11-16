package com.serfira.auth.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Date;
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
 * Login, token rotation and logout (Addendum §3.2–§3.3, ADR-022 plus the T21b addendum D10–D12).
 *
 * <p>Verifies the password with Argon2id and, on success, clears the lockout counter, sets
 * {@code last_login_at}, and issues:
 *
 * <ul>
 * <li>a 15-minute HS256 access token whose {@code sub} is the {@code app_user.id} and whose
 * {@code roles} is a one-element string array (ADR-015 D1);</li>
 * <li>an opaque refresh token, returned once to the client and stored only as a SHA-256 hash.</li>
 * </ul>
 *
 * <p>Every credential failure returns the same 401 so the response cannot be used to enumerate
 * users. A dummy Argon2 verification is performed for an unknown username so the timing is
 * comparable to a known-user failure. The one deliberate exception is a locked account, which
 * answers 423 {@code ACCOUNT_LOCKED} (D12) so the frontend can show the remaining window.
 *
 * <p>Refresh rotation is single-use: the presented row is revoked by a conditional update that
 * exactly one concurrent caller can win, so a replayed or raced token never mints a second pair.
 * Plaintext passwords and password/token hashes are never logged.
 */
@Service
public class AuthService {

	private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);

	static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);

	private final AppUserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final PasswordEncoder passwordEncoder;
	private final LoginAttemptRecorder loginAttemptRecorder;
	private final SuccessfulLoginRecorder successfulLoginRecorder;
	private final byte[] jwtSecret;
	private final Clock clock;
	private final String dummyPasswordHash;

	private final Semaphore argon2Permits;

	public AuthService(
			AppUserRepository userRepository,
			RefreshTokenRepository refreshTokenRepository,
			PasswordEncoder passwordEncoder,
			LoginAttemptRecorder loginAttemptRecorder,
			SuccessfulLoginRecorder successfulLoginRecorder,
			@Value("${serfira.security.jwt.secret-base64}") String secretBase64,
			Clock clock,
			@Value("${serfira.auth.argon2.max-concurrent-verifications:2}") int maxConcurrentVerifications) {

		if (maxConcurrentVerifications < 1) {
			throw new IllegalArgumentException("maxConcurrentVerifications must be at least 1");
		}

		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.loginAttemptRecorder = loginAttemptRecorder;
		this.successfulLoginRecorder = successfulLoginRecorder;
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
	 * <p>Deliberately <b>not</b> transactional. The account lookup runs in its own short
	 * repository transaction and ends before the Argon2 verification, so the expensive KDF holds
	 * no database connection; the failure counter is then written by
	 * {@link LoginAttemptRecorder}'s independent transaction, and only a successful verification
	 * opens {@link SuccessfulLoginRecorder}'s transaction. An outer transaction here would keep a
	 * connection suspended while the inner {@code REQUIRES_NEW} transaction asked for a second
	 * one, letting concurrent failed logins exhaust the pool.
	 *
	 * <p>The early lockout check below is an optimization so a locked account never pays an Argon2
	 * verification. The authoritative re-check happens under the account row lock in
	 * {@link SuccessfulLoginRecorder#recordSuccess}, after verification.
	 *
	 * @throws AccountLockedException         when the account is inside its lockout window (423)
	 * @throws AuthenticationFailedException  for every other authentication failure (unknown user,
	 *                                        wrong password, inactive account, non-interactive principal)
	 */
	public TokenPairResponse login(LoginRequest request) {
		AppUser user = userRepository.findByUsername(request.username().trim()).orElse(null);
		OffsetDateTime now = clock.now();

		// Checked before the password so a locked account never pays an Argon2 verification, and so
		// the answer is the same whether or not the supplied password happened to be correct.
		if (user != null && user.isLockedAt(now)) {
			LOGGER.warn("Rejected login for locked account {}", user.getId());
			throw AccountLockedException.forLockout(user.getLockedUntil(), now);
		}

		boolean passwordMatches = verifyPassword(request.password(), user);
		if (user == null || !passwordMatches || !user.isActive() || !user.isInteractive()) {
			if (user != null) {
				// Independent REQUIRES_NEW transaction: the 401 below must not undo the counter.
				loginAttemptRecorder.recordFailure(user.getId());
			}
			// No username, role or disabled-state detail: the same answer for every cause.
			LOGGER.warn("Rejected login attempt");
			throw new AuthenticationFailedException();
		}

		String rawRefreshToken = UUID.randomUUID().toString();
		SuccessfulLoginRecorder.CompletedLogin completed =
				successfulLoginRecorder.recordSuccess(user.getId(), rawRefreshToken);
		return new TokenPairResponse(
				mintAccessToken(completed.userId(), completed.role(), completed.issuedAt()),
				rawRefreshToken);
	}

	/**
	 * Rotates a refresh token: the presented row is revoked and linked to a replacement, and a new
	 * access + refresh pair is issued (T21b, Addendum §3.3).
	 *
	 * <p>The replacement row is inserted first (its id is needed for {@code replaced_by_id}) and
	 * then the presented row is retired with {@code WHERE revoked_at IS NULL}. Losing that race —
	 * or presenting an already-rotated, logged-out or expired token — is the replay case and rolls
	 * the whole transaction back, including the replacement row, so a replayed token can never mint
	 * anything.
	 *
	 * <p>The owning user is re-read rather than trusted from the token row: a deactivated account
	 * must not be able to keep refreshing, even though its access token would then be rejected by
	 * the decoder anyway.
	 *
	 * @throws InvalidRefreshTokenException for every unusable token, with no cause detail (401)
	 */
	@Transactional
	public TokenPairResponse refresh(RefreshTokenRequest request) {
		OffsetDateTime now = clock.now();
		RefreshToken presented = refreshTokenRepository.findByTokenHash(RefreshToken.hash(request.refreshToken()))
				.orElseThrow(InvalidRefreshTokenException::new);
		if (!presented.isUsableAt(now)) {
			LOGGER.warn("Rejected refresh: token already revoked or expired");
			throw new InvalidRefreshTokenException();
		}

		AppUser user = userRepository.findById(presented.getUserId())
				.orElseThrow(InvalidRefreshTokenException::new);
		if (!user.isActive() || !user.isInteractive()) {
			LOGGER.warn("Rejected refresh for account {}: inactive or non-interactive", user.getId());
			throw new InvalidRefreshTokenException();
		}

		String rawRefreshToken = UUID.randomUUID().toString();
		RefreshToken replacement = refreshTokenRepository
				.saveAndFlush(RefreshToken.issue(user.getId(), rawRefreshToken, now));

		int revoked = refreshTokenRepository.revokeIfActive(
				presented.getId(), replacement.getId(), now, user.getId());
		if (revoked != 1) {
			LOGGER.warn("Rejected refresh replay: account {} presented an already-rotated token", user.getId());
			throw new InvalidRefreshTokenException();
		}

		return new TokenPairResponse(mintAccessToken(user.getId(), user.getRole().name(), now), rawRefreshToken);
	}

	/**
	 * Revokes the presented refresh token (T21b). Idempotent by design: an unknown, expired or
	 * already-revoked token is a no-op rather than an error, so logging out twice is safe and the
	 * response cannot be used to test whether a token exists.
	 */
	@Transactional
	public void logout(RefreshTokenRequest request) {
		OffsetDateTime now = clock.now();
		refreshTokenRepository.findByTokenHash(RefreshToken.hash(request.refreshToken()))
				.ifPresent(token -> {
					int revoked = refreshTokenRepository.revokeIfActive(
							token.getId(), null, now, token.getUserId());
					if (revoked == 1) {
						LOGGER.info("Revoked refresh token for account {}", token.getUserId());
					}
				});
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
}
