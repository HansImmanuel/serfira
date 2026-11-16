package com.serfira.auth;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.serfira.TestcontainersConfiguration;
import com.serfira.auth.application.LoginRequest;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
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
 * {@code AuthService.login} throws a 401 on failure, so a counter incremented inside its transaction
 * would be rolled back and lockout would silently never fire while the HTTP answers still looked
 * correct. The failures are written through {@code LoginAttemptRecorder}'s {@code REQUIRES_NEW}
 * transaction, and only a committed read proves it.
 */
@Import({TestcontainersConfiguration.class, AuthClockTestConfiguration.class})
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
		assertThat(jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId)).isNull();
	}

	@Test
	void reachingTheThresholdLocksTheAccountUntilTheWindowPasses() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS);

		assertThat(failedAttempts(userId)).isEqualTo(MAX_ATTEMPTS);
		assertThat(jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId).toInstant())
				.isEqualTo(testNow.plus(WINDOW).toInstant());

		// The password is correct, but the lock answers first, before any Argon2 verification.
		MvcResult locked = login(PASSWORD);

		assertThat(locked.getResponse().getStatus()).isEqualTo(423);
		assertEnvelopeError(locked, "ACCOUNT_LOCKED");
		assertThat(errorMessage(locked)).contains("15 minute");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
		// An attempt while locked does not extend the window.
		assertThat(jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId).toInstant())
				.isEqualTo(testNow.plus(WINDOW).toInstant());
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
		assertThat(jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(1);
	}

	@Test
	void successfulLoginResetsTheCounterBeforeTheThreshold() throws Exception {
		UUID userId = seedUser();
		failLoginTimes(MAX_ATTEMPTS - 1);

		assertThat(login(PASSWORD).getResponse().getStatus()).isEqualTo(200);

		assertThat(failedAttempts(userId)).isZero();
		assertThat(jdbc.queryForObject(
				"select locked_until from app_user where id = ?", OffsetDateTime.class, userId)).isNull();
		// The next failure starts again at one, not at the threshold.
		assertThat(login("WrongPassword!").getResponse().getStatus()).isEqualTo(401);
		assertThat(failedAttempts(userId)).isEqualTo(1);
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
}
