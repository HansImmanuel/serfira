package com.serfira.auth.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.serfira.auth.domain.AppUser;
import com.serfira.auth.infrastructure.AppUserRepository;
import com.serfira.shared.clock.Clock;

/**
 * Records a failed login against the account's lockout counter (T21b, Addendum §3.1).
 *
 * <p>This is a separate bean on purpose. {@code AuthService.login} answers every credential
 * failure with a 401, so a counter incremented inside a caller's transaction could be undone by
 * the rollback while the HTTP response still looked correct. Running the write in a
 * {@code REQUIRES_NEW} transaction commits the failure independently, and the separate bean
 * guarantees the call crosses a proxy so the propagation actually applies.
 *
 * <p>The account row is re-read with a pessimistic write lock, so concurrent failures for the same
 * account serialize: each committed failure increments the persisted counter exactly once and
 * threshold detection uses the current value. Different accounts lock different rows and do not
 * block each other.
 *
 * <p>The threshold and window are configuration, not constants, so a deployment can tune them
 * without a rebuild; the defaults (5 attempts / 15 minutes) are ADR-022 addendum D12.
 */
@Service
public class LoginAttemptRecorder {

	private static final Logger LOGGER = LoggerFactory.getLogger(LoginAttemptRecorder.class);

	private final AppUserRepository userRepository;
	private final Clock clock;
	private final int maxAttempts;
	private final Duration lockWindow;

	public LoginAttemptRecorder(
			AppUserRepository userRepository,
			Clock clock,
			@Value("${serfira.auth.lockout.max-attempts:5}") int maxAttempts,
			@Value("${serfira.auth.lockout.window:PT15M}") Duration lockWindow) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("serfira.auth.lockout.max-attempts must be at least 1");
		}
		if (lockWindow.isNegative() || lockWindow.isZero()) {
			throw new IllegalArgumentException("serfira.auth.lockout.window must be positive");
		}
		this.userRepository = userRepository;
		this.clock = clock;
		this.maxAttempts = maxAttempts;
		this.lockWindow = lockWindow;
	}

	/**
	 * Counts one failed password for the given user and locks the account when the threshold is
	 * reached. Unknown user ids are ignored: there is no counter to own, and the caller answers
	 * the same 401 either way.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordFailure(UUID userId) {
		OffsetDateTime now = clock.now();
		userRepository.findLockedById(userId).ifPresent(user -> {
			boolean locked = user.recordFailedLogin(maxAttempts, lockWindow, now);
			if (locked) {
				LOGGER.warn("Locked account {} after {} failed login attempts until {}",
						user.getId(), user.getFailedLoginAttempts(), user.getLockedUntil());
				return;
			}
			LOGGER.warn("Failed login attempt {} of {} for account {}",
					user.getFailedLoginAttempts(), maxAttempts, user.getId());
		});
	}
}
