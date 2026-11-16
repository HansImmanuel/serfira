package com.serfira.auth.domain;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.serfira.shared.security.AppRole;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lockout arithmetic on {@link AppUser} (T21b, ADR-022 D12).
 *
 * <p>Pure unit tests: the expired-lock reset and the "do not extend an active lock" rule are
 * time-based domain behaviour, so they are exercised here without Spring or PostgreSQL. The
 * database-level serialization of concurrent failures is covered by {@code AuthLockoutIT}.
 */
class AppUserTest {

	private static final int MAX_ATTEMPTS = 5;
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private static final OffsetDateTime NOW =
			OffsetDateTime.of(2026, 11, 16, 9, 0, 0, 0, ZoneOffset.ofHours(7));

	private final AppUser user = new AppUser(
			"lockout-user", "$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA", "Lockout User",
			AppRole.FINANCE, true, NOW);

	@Test
	void failuresBelowTheThresholdAccumulateWithoutLocking() {
		assertThat(recordFailure(NOW)).isFalse();
		assertThat(recordFailure(NOW)).isFalse();

		assertThat(user.getFailedLoginAttempts()).isEqualTo(2);
		assertThat(user.getLockedUntil()).isNull();
	}

	@Test
	void reachingTheThresholdLocksForTheConfiguredWindow() {
		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
			assertThat(recordFailure(NOW)).isFalse();
		}

		assertThat(recordFailure(NOW)).isTrue();
		assertThat(user.getFailedLoginAttempts()).isEqualTo(MAX_ATTEMPTS);
		assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(WINDOW));
	}

	@Test
	void anExpiredLockIsClearedSoTheNextFailureStartsAtOne() {
		lockAccount();

		OffsetDateTime afterWindow = NOW.plus(WINDOW).plusSeconds(1);

		assertThat(recordFailure(afterWindow)).isFalse();
		assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
		assertThat(user.getLockedUntil()).isNull();
	}

	@Test
	void aFailureAtTheExactExpiryInstantStartsAFreshSequence() {
		lockAccount();

		assertThat(recordFailure(NOW.plus(WINDOW))).isFalse();
		assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
		assertThat(user.getLockedUntil()).isNull();
	}

	@Test
	void anActiveLockIsNotExtendedByAnInFlightFailure() {
		lockAccount();
		OffsetDateTime lockedUntil = user.getLockedUntil();

		OffsetDateTime duringWindow = NOW.plusMinutes(1);
		assertThat(recordFailure(duringWindow)).isTrue();

		assertThat(user.getFailedLoginAttempts()).isEqualTo(MAX_ATTEMPTS);
		assertThat(user.getLockedUntil()).isEqualTo(lockedUntil);
	}

	@Test
	void successfulLoginClearsTheCounterAndAnElapsedLock() {
		lockAccount();

		user.recordSuccessfulLogin(NOW.plus(WINDOW).plusSeconds(1));

		assertThat(user.getFailedLoginAttempts()).isZero();
		assertThat(user.getLockedUntil()).isNull();
		assertThat(user.getLastLoginAt()).isEqualTo(NOW.plus(WINDOW).plusSeconds(1));
	}

	private boolean recordFailure(OffsetDateTime at) {
		return user.recordFailedLogin(MAX_ATTEMPTS, WINDOW, at);
	}

	private void lockAccount() {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			recordFailure(NOW);
		}
		assertThat(user.isLockedAt(NOW)).isTrue();
	}
}
