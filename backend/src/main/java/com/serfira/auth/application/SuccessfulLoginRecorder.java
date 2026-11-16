package com.serfira.auth.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.serfira.auth.domain.AppUser;
import com.serfira.auth.domain.RefreshToken;
import com.serfira.auth.infrastructure.AppUserRepository;
import com.serfira.auth.infrastructure.RefreshTokenRepository;
import com.serfira.shared.clock.Clock;

/**
 * Commits the database changes of a successful login (T21b, ADR-022 D12).
 *
 * <p>This is a separate bean so the success path runs in its own Spring-managed transaction,
 * called only after the Argon2 verification has finished. {@code AuthService.login} therefore
 * holds no database connection while the expensive KDF runs, and the independent
 * {@code LoginAttemptRecorder} transaction never has to find a second connection inside an outer
 * suspended one.
 *
 * <p>The account row is re-read with a pessimistic write lock and the lockout state is re-checked
 * here. The early check in {@code AuthService.login} avoids an Argon2 verification for an account
 * that was already locked, but it is only an optimization: concurrent failures can establish the
 * lock while the password is being verified. This re-check is the authoritative decision, so such
 * a login answers 423 and mints nothing instead of clearing the freshly committed lock.
 */
@Service
public class SuccessfulLoginRecorder {

	private final AppUserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final Clock clock;

	public SuccessfulLoginRecorder(
			AppUserRepository userRepository,
			RefreshTokenRepository refreshTokenRepository,
			Clock clock) {
		this.userRepository = userRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.clock = clock;
	}

	/**
	 * Clears the failure state, records {@code last_login_at}, stores the presented refresh token
	 * and returns the identity the caller needs to mint the access token.
	 *
	 * @throws AccountLockedException        when a concurrent failure locked the account after the
	 *                                       early check (423)
	 * @throws AuthenticationFailedException when the account disappeared, was deactivated or is
	 *                                       non-interactive between the lookup and this commit
	 */
	@Transactional
	public CompletedLogin recordSuccess(UUID userId, String rawRefreshToken) {
		OffsetDateTime now = clock.now();
		AppUser user = userRepository.findLockedById(userId)
				.orElseThrow(AuthenticationFailedException::new);

		if (user.isLockedAt(now)) {
			throw AccountLockedException.forLockout(user.getLockedUntil(), now);
		}
		if (!user.isActive() || !user.isInteractive()) {
			throw new AuthenticationFailedException();
		}

		user.recordSuccessfulLogin(now);
		userRepository.save(user);
		refreshTokenRepository.save(RefreshToken.issue(userId, rawRefreshToken, now));
		return new CompletedLogin(user.getId(), user.getRole().name(), now);
	}

	/**
	 * The committed identity of a successful login. The access token is minted from it after this
	 * transaction commits, so both tokens carry the same issue time.
	 */
	public record CompletedLogin(UUID userId, String role, OffsetDateTime issuedAt) {
	}
}
