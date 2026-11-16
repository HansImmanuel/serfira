package com.serfira.auth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.serfira.TestcontainersConfiguration;
import com.serfira.auth.application.AccountLockedException;
import com.serfira.auth.application.AuthService;
import com.serfira.auth.application.LoginAttemptRecorder;
import com.serfira.auth.application.LoginRequest;
import com.serfira.auth.application.TokenPairResponse;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Brute-force lockout for T21b (Addendum §3.1/§3.3, ADR-022 addendum D12).
 *
 * <p>Five consecutive failures lock the account for fifteen minutes, counted from the failure that
 * crossed the threshold; the next attempt answers 423 {@code ACCOUNT_LOCKED} even with the right
 * password, and a success after the window clears the state. Both numbers come from
 * {@code serfira.auth.lockout.*} so the policy is configuration, not a constant.
 *
 * <p>Every assertion on the counter reads the <b>database</b>, not the response. That is the point:
 * {@code AuthService.login} answers a 401 on failure, so a counter incremented inside a caller's
 * rollback would be lost and lockout would silently never fire while the HTTP answers still looked
 * correct. The failures are written through {@code LoginAttemptRecorder}'s {@code REQUIRES_NEW}
 * transaction, and only a committed read proves it.
 *
 * <p>The concurrency cases use real PostgreSQL transactions. {@code AuthService.login} holds no
 * transaction while Argon2 runs, so the pausable encoder below can park one successful request
 * inside verification while other requests establish the lock; that is exactly the race the
 * row lock in {@code SuccessfulLoginRecorder} has to resolve.
 */
@Import({TestcontainersConfiguration.class, AuthClockTestConfiguration.class,
		AuthLockoutIT.PausableEncoderConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AuthLockoutIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String USERNAME = "lockout-it-admin";
	private static final String PASSWORD = "Admin123!";
	private static final int MAX_ATTEMPTS = 5;
	private static final Duration WINDOW = Duration.ofMinutes(15);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AuthService authService;

	@Autowired
	LoginAttemptRecorder loginAttemptRecorder;

	@Autowired
	PausablePasswordEncoder pausablePasswordEncoder;

	@Autowired
	FixedClock authFixedClock;

	private OffsetDateTime testNow;

	@BeforeEach
	void resetDatabase() {
		truncateDomainTables();
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		testNow = OffsetDateTime.now(ZoneOffset.ofHours(7)).truncatedTo(ChronoUnit.SECONDS);
		authFixedClock.set(testNow);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void eachFailedLoginPersistsItsCounterDespiteTheThrown401() throws Exception {
		UUID userId = seedUser();

		for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
			MvcResult result = login("WrongPassword!");

			assertThat(result.getResponse().getStatus()).isEqualTo(401);
			// Committed by a separate transaction: a rollback would leave this at 0.
			assertThat(failedAttempts(userId)).isEqualTo(attempt);
		}
		assertThat(lockedUntil(userId)).isNull();
	}

	@Test
	void reachingTheThresholdLocksTheAccountUntilTheWindowPasses() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS);

		assertThat(failedAttempts(userId)).isEqualTo(MAX_ATTEMPTS);
		assertThat(lockedUntil(userId).toInstant()).isEqualTo(testNow.plus(WINDOW).toInstant());

		// The password is correct, but the lock answers first, before any Argon2 verification.
		MvcResult locked = login(PASSWORD);

		assertThat(locked.getResponse().getStatus()).isEqualTo(423);
		assertEnvelopeError(locked, "ACCOUNT_LOCKED");
		assertThat(errorMessage(locked)).contains("15 minute");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
		// An attempt while locked does not extend the window.
		assertThat(lockedUntil(userId).toInstant()).isEqualTo(testNow.plus(WINDOW).toInstant());
	}

	@Test
	void correctLoginAfterTheWindowClearsTheLockAndTheCounter() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS);

		authFixedClock.advanceBy(WINDOW.plusSeconds(1));
		MvcResult result = login(PASSWORD);

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		assertThat(failedAttempts(userId)).isZero();
		assertThat(lockedUntil(userId)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(1);
	}

	@Test
	void successfulLoginResetsTheCounterBeforeTheThreshold() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS - 1);

		assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);

		assertThat(failedAttempts(userId)).isZero();
		assertThat(lockedUntil(userId)).isNull();
		// The next failure starts again at one, not at the threshold.
		assertThat(login("WrongPassword!").getResponse().getStatus()).isEqualTo(401);
		assertThat(failedAttempts(userId)).isEqualTo(1);
	}

	@Test
	void anExpiredLockFollowedByOneWrongPasswordStartsTheCounterAtOne() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS);

		authFixedClock.advanceBy(WINDOW.plusSeconds(1));
		MvcResult result = login("WrongPassword!");

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		// The old counter must not resume at the threshold and lock the account again.
		assertThat(failedAttempts(userId)).isEqualTo(1);
		assertThat(lockedUntil(userId)).isNull();
	}

	@Test
	void concurrentFailuresIncrementTheCounterExactlyOnceEachAndLockAtTheThreshold() throws Exception {
		UUID userId = seedUser();

		runConcurrentFailures(userId, MAX_ATTEMPTS);

		assertThat(failedAttempts(userId)).isEqualTo(MAX_ATTEMPTS);
		assertThat(lockedUntil(userId).toInstant()).isEqualTo(testNow.plus(WINDOW).toInstant());
	}

	@Test
	void aFailureAfterTheLockIsCommittedDoesNotExtendTheWindow() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS);
		OffsetDateTime committedLock = lockedUntil(userId);

		// An attempt that passed the early check before the lock committed can still reach the
		// recorder afterwards. It must not renew the window.
		authFixedClock.advanceBy(Duration.ofMinutes(1));
		loginAttemptRecorder.recordFailure(userId);

		assertThat(failedAttempts(userId)).isEqualTo(MAX_ATTEMPTS);
		assertThat(lockedUntil(userId).toInstant()).isEqualTo(committedLock.toInstant());
	}

	@Test
	void successfulLoginCannotMintTokensWhenConcurrentFailuresLockTheAccountFirst() throws Exception {
		UUID userId = seedUser();
		pausablePasswordEncoder.arm();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<TokenPairResponse> successfulAttempt = pool.submit(
				() -> authService.login(new LoginRequest(USERNAME, PASSWORD)));
		try {
			// The successful request has passed the early check and is inside Argon2. It holds no
			// database transaction now, so these failures can commit the lock underneath it.
			assertThat(pausablePasswordEncoder.awaitVerificationStarted())
					.as("successful login reached password verification")
					.isTrue();
			failLoginTimes(MAX_ATTEMPTS);
			assertThat(lockedUntil(userId).toInstant()).isEqualTo(testNow.plus(WINDOW).toInstant());

			pausablePasswordEncoder.releaseVerification();

			assertThatThrownBy(() -> successfulAttempt.get(10, TimeUnit.SECONDS))
					.isInstanceOf(ExecutionException.class)
					.hasCauseInstanceOf(AccountLockedException.class);
		} finally {
			// Unblock the worker even when an assertion above fails, then stop the pool.
			pausablePasswordEncoder.releaseVerification();
			pool.shutdownNow();
		}

		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
		assertThat(failedAttempts(userId)).isEqualTo(MAX_ATTEMPTS);
		assertThat(lockedUntil(userId).toInstant()).isEqualTo(testNow.plus(WINDOW).toInstant());
	}

	@Test
	void successfulLoginWinningTheRaceLeavesLaterFailuresCountingFromOne() throws Exception {
		UUID userId = seedUser();

		assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);
		assertThat(failedAttempts(userId)).isZero();

		runConcurrentFailures(userId, 3);

		// The success cleared the counter before the failures acquired the row lock.
		assertThat(failedAttempts(userId)).isEqualTo(3);
		assertThat(lockedUntil(userId)).isNull();
	}

	private void runConcurrentFailures(UUID userId, int attempts) throws Exception {
		CyclicBarrier bothReady = new CyclicBarrier(attempts);
		Callable<Void> failure = () -> {
			bothReady.await();
			loginAttemptRecorder.recordFailure(userId);
			return null;
		};
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		try {
			List<Future<Void>> outcomes = pool.invokeAll(
					java.util.Collections.nCopies(attempts, failure));
			for (Future<Void> outcome : outcomes) {
				outcome.get(10, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	private void failLoginTimes(int times) throws Exception {
		for (int attempt = 0; attempt < times; attempt++) {
			assertThat(login("WrongPassword!").getResponse().getStatus()).isEqualTo(401);
		}
	}

	private int failedAttempts(UUID userId) {
		return jdbc.queryForObject(
				"select failed_login_attempts from app_user where id = ?", Integer.class, userId);
	}

	private OffsetDateTime lockedUntil(UUID userId) {
		return jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId);
	}

	private UUID seedUser() {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active,
					failed_login_attempts, locked_until, last_login_at, created_at, updated_at)
					values (?, ?, ?, ?, true, 0, null, null, ?, ?)
					returning id""",
				UUID.class,
				USERNAME,
				passwordEncoder.encode(PASSWORD),
				"Lockout IT " + ADMIN,
				ADMIN,
				testNow,
				testNow);
	}

	private MvcResult login(String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(USERNAME, password))))
				.andReturn();
	}

	private void assertEnvelopeError(MvcResult result, String expectedCode) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("data").isNull()).isTrue();
		assertThat(body.get("error").get("code").asText()).isEqualTo(expectedCode);
	}

	private String errorMessage(MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString())
				.get("error").get("message").asText();
	}

	private void truncateDomainTables() {
		jdbc.execute("""
				TRUNCATE TABLE
					document_number_counter, refresh_token, idempotency_keys, job_run,
					reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
				CASCADE""");
	}

	/**
	 * Exposes the encoder as a primary bean so the test can park the next verification. It
	 * delegates to the real Argon2 encoder, so hashes and timing stay production-shaped.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class PausableEncoderConfiguration {

		@Bean
		@Primary
		PausablePasswordEncoder pausablePasswordEncoder() {
			return new PausablePasswordEncoder();
		}
	}

	/** Argon2 encoder that can hold the next {@code matches} call until the test releases it. */
	static final class PausablePasswordEncoder implements PasswordEncoder {

		private final PasswordEncoder delegate = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
		private final AtomicBoolean pauseArmed = new AtomicBoolean(false);
		private volatile CountDownLatch verificationStarted = new CountDownLatch(0);
		private volatile CountDownLatch verificationMayFinish = new CountDownLatch(0);

		void arm() {
			verificationStarted = new CountDownLatch(1);
			verificationMayFinish = new CountDownLatch(1);
			pauseArmed.set(true);
		}

		boolean awaitVerificationStarted() throws InterruptedException {
			return verificationStarted.await(10, TimeUnit.SECONDS);
		}

		void releaseVerification() {
			verificationMayFinish.countDown();
		}

		@Override
		public String encode(CharSequence rawPassword) {
			return delegate.encode(rawPassword);
		}

		@Override
		public boolean matches(CharSequence rawPassword, String encodedPassword) {
			if (pauseArmed.compareAndSet(true, false)) {
				CountDownLatch started = verificationStarted;
				CountDownLatch mayFinish = verificationMayFinish;
				started.countDown();
				try {
					if (!mayFinish.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to release password verification");
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Interrupted while pausing password verification", ex);
				}
			}
			return delegate.matches(rawPassword, encodedPassword);
		}
	}
}
