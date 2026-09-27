package com.serfira.shared.idempotency;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;

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

/**
 * Retention semantics of the idempotency mechanism (02_TECH_SPEC.md §2.5, ADR-007 decision 9): inside
 * the retention window a retry replays the stored response, and once the window has elapsed the claim
 * is taken over so the key names a new request instead of blocking (or replaying) forever.
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
	void aRetryAfterTheRetentionWindowTakesTheClaimOverAndRunsTheOperationAgain() {
		AtomicInteger invocations = new AtomicInteger();

		IdempotentResult<String> first = execute("past-window", "body-one", invocations);
		fixedClock().advanceBy(Duration.ofDays(retentionDays() + 1L));
		IdempotentResult<String> second = execute("past-window", "body-one", invocations);

		assertThat(second.replayed()).isFalse();
		assertThat(second.response()).isNotEqualTo(first.response());
		assertThat(invocations).hasValue(2);
		// The row is taken over, never duplicated: uniqueness stays (endpoint, key).
		assertThat(claimCount("past-window")).isEqualTo(1L);
		assertThat(claimStatus("past-window")).isEqualTo(IdempotencyKey.STATUS_COMPLETED);
		assertThat(claimExpiresAt("past-window")).isAfter(clock.now());
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
