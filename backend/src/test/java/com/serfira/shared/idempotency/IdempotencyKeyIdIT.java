package com.serfira.shared.idempotency;

import com.serfira.TestcontainersConfiguration;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.config.SystemParameterKeys;
import com.serfira.shared.config.SystemParameterService;
import com.serfira.shared.error.ConflictException;
import com.serfira.shared.error.IdempotencyKeyExpiredException;
import com.serfira.shared.security.PiiEnvelopeVersion;
import com.serfira.shared.security.PiiKey;
import com.serfira.shared.security.PiiKeyRing;
import com.serfira.shared.security.PiiSecuritySupport;

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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T28b / ADR-025: a claim records the key id its fingerprint was computed under, and a replay compares under that
 * recorded key, so rotating the active key does not break an in-flight retry. A claim from before key ids
 * ({@code request_hash_kid} NULL) was hashed under the legacy key.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class IdempotencyKeyIdIT {

	private static final String ENDPOINT = "POST /api/v1/kid-probe";
	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 24);
	private static final UUID SYSTEM_USER_ID = new UUID(0L, 1L);

	private static final byte[] TEST_PROFILE_ENCRYPTION_KEY =
			"0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] TEST_PROFILE_HMAC_KEY =
			"abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_AES = "rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_HMAC = "rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8);

	@Autowired
	IdempotencyService idempotency;

	@Autowired
	IdempotencyKeyUsage usage;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	SystemParameterService systemParameters;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Clock clock;

	@BeforeEach
	void start() {
		fixedClock().setDate(BUSINESS_DATE);
		deleteProbeClaims();
	}

	@AfterEach
	void cleanUpAndRestoreTheTestRing() {
		PiiSecuritySupport.configure(TEST_PROFILE_ENCRYPTION_KEY, TEST_PROFILE_HMAC_KEY);
		deleteProbeClaims();
	}

	@Test
	void aClaimRecordsTheActiveKid() {
		execute("records-kid", "body-a", new AtomicInteger());

		assertThat(recordedKid("records-kid")).isEqualTo("k1");
	}

	@Test
	void aReplayAfterRotationComparesUnderTheRecordedKid() {
		AtomicInteger invocations = new AtomicInteger();
		execute("rotated", "body-a", invocations);

		rotateToK2();

		assertThat(execute("rotated", "body-a", invocations).replayed()).isTrue();
		assertThat(invocations).hasValue(1);
		assertThatThrownBy(() -> execute("rotated", "body-b", invocations))
				.isInstanceOf(ConflictException.class)
				.hasMessage("Idempotency-Key was already used for a different request on " + ENDPOINT);
	}

	@Test
	void aLegacyClaimWithoutAKidComparesUnderTheLegacyKey() {
		AtomicInteger invocations = new AtomicInteger();
		execute("legacy", "body-a", invocations);
		clearKid("legacy");

		rotateToK2();

		assertThat(execute("legacy", "body-a", invocations).replayed()).isTrue();
		assertThat(invocations).hasValue(1);
	}

	@Test
	void aLegacyClaimWithoutALegacyKeyFailsLoudly() {
		AtomicInteger invocations = new AtomicInteger();
		execute("legacy-gone", "body-a", invocations);
		clearKid("legacy-gone");

		PiiSecuritySupport.configure(new PiiKeyRing(List.of(k2()), "k2", null, PiiEnvelopeVersion.V2));

		assertThatThrownBy(() -> execute("legacy-gone", "body-a", invocations))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("legacy-kid");
		assertThat(invocations).hasValue(1);
	}

	@Test
	void aClaimWhoseKidLeftTheRingFailsLoudly() {
		AtomicInteger invocations = new AtomicInteger();
		execute("kid-left", "body-a", invocations);

		PiiSecuritySupport.configure(new PiiKeyRing(List.of(k2()), "k2", null, PiiEnvelopeVersion.V2));

		assertThatThrownBy(() -> execute("kid-left", "body-a", invocations))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k1")
				.hasMessageContaining("not in the key ring");
		assertThat(invocations).hasValue(1);
	}

	@Test
	void anExpiredClaimIsRejectedBeforeItsKidIsResolved() {
		AtomicInteger invocations = new AtomicInteger();
		execute("expired", "body-a", invocations);

		PiiSecuritySupport.configure(new PiiKeyRing(List.of(k2()), "k2", null, PiiEnvelopeVersion.V2));
		fixedClock().advanceBy(Duration.ofDays(retentionDays()).plusMinutes(1));

		assertThatThrownBy(() -> execute("expired", "body-a", invocations))
				.isInstanceOf(IdempotencyKeyExpiredException.class);
		assertThat(invocations).hasValue(1);
	}

	@Test
	void unexpiredUsageCountsByKid() {
		IdempotencyKeyUsage.FingerprintKeyUsage before = usage.unexpired();
		execute("usage-1", "body-a", new AtomicInteger());
		execute("usage-2", "body-a", new AtomicInteger());
		rotateToK2();
		execute("usage-3", "body-a", new AtomicInteger());
		clearKid("usage-1");

		IdempotencyKeyUsage.FingerprintKeyUsage after = usage.unexpired();

		assertThat(delta(after, before, "k1")).isEqualTo(1L);
		assertThat(delta(after, before, "k2")).isEqualTo(1L);
		assertThat(after.unlabelled() - before.unlabelled()).isEqualTo(1L);
	}

	@Test
	void aClaimWithoutExpiryCountsAsUnexpired() {
		IdempotencyKeyUsage.FingerprintKeyUsage before = usage.unexpired();

		jdbc.update("""
				insert into idempotency_keys
					(id, key, endpoint, request_hash, request_hash_kid, status, expires_at,
						created_at, created_by, updated_at, updated_by)
				values (gen_random_uuid(), 'no-expiry', ?, null, 'k1', 'COMPLETED', null,
					clock_timestamp(), ?, clock_timestamp(), ?)
				""", ENDPOINT, SYSTEM_USER_ID, SYSTEM_USER_ID);

		assertThat(delta(usage.unexpired(), before, "k1")).isEqualTo(1L);
	}

	private static long delta(IdempotencyKeyUsage.FingerprintKeyUsage after,
			IdempotencyKeyUsage.FingerprintKeyUsage before, String kid) {
		return after.byKid().getOrDefault(kid, 0L) - before.byKid().getOrDefault(kid, 0L);
	}

	private static PiiKey k1() {
		return PiiKey.of("k1", TEST_PROFILE_ENCRYPTION_KEY, TEST_PROFILE_HMAC_KEY);
	}

	private static PiiKey k2() {
		return PiiKey.of("k2", K2_AES, K2_HMAC);
	}

	private static void rotateToK2() {
		PiiSecuritySupport.configure(new PiiKeyRing(List.of(k1(), k2()), "k2", "k1", PiiEnvelopeVersion.V2));
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

	private String recordedKid(String key) {
		return jdbc.queryForObject("SELECT request_hash_kid FROM idempotency_keys WHERE endpoint = ? AND key = ?",
				String.class, ENDPOINT, key);
	}

	private void clearKid(String key) {
		jdbc.update("UPDATE idempotency_keys SET request_hash_kid = NULL WHERE endpoint = ? AND key = ?", ENDPOINT, key);
	}

	private void deleteProbeClaims() {
		jdbc.update("DELETE FROM idempotency_keys WHERE endpoint = ?", ENDPOINT);
	}

	private FixedClock fixedClock() {
		return (FixedClock) clock;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class KidClockConfig {
		@Bean
		@Primary
		Clock fixedClock() {
			return new FixedClock(BUSINESS_DATE);
		}
	}
}
