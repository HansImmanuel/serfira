package com.serfira.shared.idempotency;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
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
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F4 / T18 — the keyset-paged cleanup delete must drain the whole expired set across multiple pages,
 * not just the first {@code batch-size} rows. The batch size is forced to 2 so a handful of expired rows
 * spans several pages (ADR-013 A-9: one {@code job_run} per invocation; TS §2.5 bounded keyset paging).
 *
 * <p>Separate {@code *IT} class because {@code @TestPropertySource} is class-level and
 * {@code IdempotencyCleanupIT} relies on the default batch size (1000) for its count assertions. Mirrors
 * that suite's wiring (Testcontainers PostgreSQL, {@code FixedClock}, real commits, locked entry point).
 */
@Import({TestcontainersConfiguration.class, IdempotencyCleanupPagingIT.FixedClockConfig.class})
@SpringBootTest
@TestPropertySource(properties = "serfira.jobs.idempotency-cleanup.batch-size=2")
class IdempotencyCleanupPagingIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 2, 28);
	private static final int EXPIRED_ROWS = 7;
	private static final int LIVE_ROWS = 3;

	@Autowired
	LockedIdempotencyCleanupJob job;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void resetState() {
		truncateDomainTables();
		fixedClock().setDate(BUSINESS_DATE);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void theJobDeletesEveryExpiredRowAcrossMultiplePages() {
		// With batch-size 2, seven expired rows span four pages (2 + 2 + 2 + 1); the live rows are never
		// eligible. Seed under a backed create endpoint (POST /api/v1/contracts) so the scoped delete applies.
		for (int i = 0; i < EXPIRED_ROWS; i++) {
			seedClaim("expired-" + i, clock.now().minusDays(i + 1));
		}
		for (int i = 0; i < LIVE_ROWS; i++) {
			seedClaim("live-" + i, clock.now().plusDays(i + 1));
		}

		job.run();

		// (a) every expired row gone, (b) every live row retained.
		for (int i = 0; i < EXPIRED_ROWS; i++) {
			assertThat(claimExists("expired-" + i)).as("expired-%d deleted", i).isFalse();
		}
		for (int i = 0; i < LIVE_ROWS; i++) {
			assertThat(claimExists("live-" + i)).as("live-%d retained", i).isTrue();
		}

		// (c) exactly one COMPLETED job_run with records_processed = total expired, no failures.
		assertThat(jdbc.queryForObject("select count(*) from job_run where job_name = 'idempotency-cleanup'",
				Long.class)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				select count(*) from job_run
				where job_name = 'idempotency-cleanup' and status = 'COMPLETED'
					and records_processed = ? and records_failed = 0 and finished_at is not null
				""", Long.class, EXPIRED_ROWS)).isEqualTo(1L);
	}

	// --- helpers ---------------------------------------------------------------------------------------

	private void seedClaim(String key, OffsetDateTime expiresAt) {
		jdbc.update("""
				insert into idempotency_keys
					(id, key, endpoint, request_hash, status, expires_at, created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), ?, 'POST /api/v1/contracts', null, 'COMPLETED', ?,
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", key, expiresAt, SYSTEM_USER_ID, SYSTEM_USER_ID);
	}

	private boolean claimExists(String key) {
		Long count = jdbc.queryForObject("select count(*) from idempotency_keys where key = ?", Long.class, key);
		return count != null && count > 0L;
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	private void truncateDomainTables() {
		jdbc.execute("TRUNCATE TABLE idempotency_keys, job_run CASCADE");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
