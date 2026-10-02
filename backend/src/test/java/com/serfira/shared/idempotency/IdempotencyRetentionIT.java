package com.serfira.shared.idempotency;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.error.IdempotencyKeyExpiredException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Retention semantics of the idempotency mechanism (02_TECH_SPEC.md §2.5, ADR-017, amends ADR-007
 * decision 9): a key is single-use forever per endpoint. Inside the retention window a retry replays the
 * stored response; once the window has elapsed the key is spent, so reuse is rejected with 409
 * {@code IDEMPOTENCY_KEY_EXPIRED} before the operation runs — the row is never taken over and the
 * operation is never re-run.
 *
 * <p>The canonical payload is opaque to {@code IdempotencyService} (it is only fingerprinted), so a
 * plain string stands in for the request body and the assertions stay about the retention window.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyRetentionIT {

	private static final String ENDPOINT = "POST /api/v1/retention-probe";
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 24);

	@Autowired
	IdempotencyService idempotency;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	SystemParameterService systemParameters;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void startInsideTheRetentionWindow() {
		fixedClock().setDate(BUSINESS_DATE);
		deleteProbeClaims();
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		deleteProbeClaims();
	}

	@Test
	void anIdenticalRetryInsideTheRetentionWindowReplaysTheStoredResponseWithoutRunningTheOperation() {
		AtomicInteger invocations = new AtomicInteger();

		IdempotentResult<String> first = execute("inside-window", "body-one", invocations);
		// One day short of the window: still the same retry, so the stored response comes back.
		fixedClock().advanceBy(Duration.ofDays(retentionDays() - 1L));
		IdempotentResult<String> retry = execute("inside-window", "body-one", invocations);

		assertThat(first.replayed()).isFalse();
		assertThat(retry.replayed()).isTrue();
		assertThat(retry.response()).isEqualTo(first.response());
		assertThat(invocations).hasValue(1);
		assertThat(claimCount("inside-window")).isEqualTo(1L);
	}

	@Test
	void aRetryAfterTheRetentionWindowIsRejectedAsExpiredWithoutRunningTheOperationAgain() {
		AtomicInteger invocations = new AtomicInteger();

		IdempotentResult<String> first = execute("past-window", "body-one", invocations);
		OffsetDateTime expiresAtAfterFirst = claimExpiresAt("past-window");
		fixedClock().advanceBy(Duration.ofDays(retentionDays() + 1L));

		// Option A (ADR-017): past retention the key is spent, so the same body is rejected before the
		// supplier runs — never taken over and re-run.
		assertThatThrownBy(() -> execute("past-window", "body-one", invocations))
				.isInstanceOf(IdempotencyKeyExpiredException.class);

		assertThat(first.replayed()).isFalse();
		assertThat(invocations).hasValue(1);
		// The row is left untouched: not duplicated, not overwritten, status and expiry unchanged.
		assertThat(claimCount("past-window")).isEqualTo(1L);
		assertThat(claimStatus("past-window")).isEqualTo(IdempotencyKey.STATUS_COMPLETED);
		assertThat(claimExpiresAt("past-window")).isEqualTo(expiresAtAfterFirst);
	}

	@Test
	void aRetryAfterTheRetentionWindowWithADifferentBodyIsAlsoRejectedAsExpired() {
		AtomicInteger invocations = new AtomicInteger();

		execute("past-window-diff", "body-one", invocations);
		fixedClock().advanceBy(Duration.ofDays(retentionDays() + 1L));

		// A spent key is spent whatever the body: expiry is decided before the fingerprint is compared,
		// so this is IDEMPOTENCY_KEY_EXPIRED, not the in-window different-body CONFLICT.
		assertThatThrownBy(() -> execute("past-window-diff", "body-two", invocations))
				.isInstanceOf(IdempotencyKeyExpiredException.class);
		assertThat(invocations).hasValue(1);
		assertThat(claimCount("past-window-diff")).isEqualTo(1L);
	}

	private IdempotentResult<String> execute(String key, String body, AtomicInteger invocations) {
		return transactions.execute(status -> idempotency.execute(ENDPOINT, key, body, String.class, () -> {
			invocations.incrementAndGet();
			return "result-" + invocations.get();
		}));
	}

	private int retentionDays() {
		return systemParameters.requireInt(SystemParameterKeys.IDEMPOTENCY_KEY_RETENTION_DAYS);
	}

	private long claimCount(String key) {
		Long count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM idempotency_keys WHERE endpoint = ? AND key = ?", Long.class, ENDPOINT, key);
		return count == null ? 0L : count;
	}

	private String claimStatus(String key) {
		return jdbc.queryForObject(
				"SELECT status FROM idempotency_keys WHERE endpoint = ? AND key = ?", String.class, ENDPOINT, key);
	}

	private OffsetDateTime claimExpiresAt(String key) {
		return jdbc.queryForObject(
				"SELECT expires_at FROM idempotency_keys WHERE endpoint = ? AND key = ?",
				OffsetDateTime.class, ENDPOINT, key);
	}

	private void deleteProbeClaims() {
		jdbc.update("DELETE FROM idempotency_keys WHERE endpoint = ?", ENDPOINT);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RetentionClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
