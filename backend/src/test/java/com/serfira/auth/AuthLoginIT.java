package com.serfira.auth;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.serfira.TestcontainersConfiguration;
import com.serfira.auth.application.LoginRequest;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import com.serfira.support.TestJwts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * End-to-end login and identity-binding behaviour for T21a (Addendum §3.1–§3.4, ADR-022).
 *
 * <p>Seven cases cover the acceptance criteria: a seeded user logs in and receives a 15-minute
 * access token whose {@code sub} is the {@code app_user.id} and whose {@code roles} is a string
 * array; a hashed refresh token is persisted; wrong password, unknown user and inactive user are
 * 401; a token with an unknown or inactive {@code sub} is 401; and a financial write after login
 * stamps the real {@code app_user.id} in {@code created_by}/{@code updated_by}.
 *
 * <p>Uses the real Argon2 encoder (never asserts on a hash value) and a {@link
 * AuthClockTestConfiguration fixed clock}, so token lifetimes and {@code last_login_at} are exact.
 */
@Import({TestcontainersConfiguration.class, AuthClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AuthLoginIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String USERNAME = "auth-it-admin";
	private static final String PASSWORD = "Admin123!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JwtDecoder jwtDecoder;

	@Autowired 
	FixedClock authFixedClock;
	
	private OffsetDateTime testNow;

	@BeforeEach
	void resetDatabase() {
		truncateDomainTables();
		// Keep the seeded SYSTEM principal; clear only IT-created users.
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
	}

	@BeforeEach
	void updateClock() {
		testNow	= OffsetDateTime.now(ZoneOffset.ofHours(7)).truncatedTo(ChronoUnit.SECONDS);
		authFixedClock.set(testNow);
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		truncateDomainTables();
	}

	@Test
	void loginIssuesExpectedTokensAndPersistsOnlyTheRefreshTokenHash() throws Exception {
		UUID userId = seedUser(USERNAME, ADMIN, true, PASSWORD, 3);

		MvcResult result = login(USERNAME, PASSWORD);

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		String accessToken = data.get("access_token").asText();
		String refreshToken = data.get("refresh_token").asText();
		assertThat(refreshToken).isNotBlank();

		Jwt jwt = jwtDecoder.decode(accessToken);
		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(jwt.getClaimAsStringList("roles")).containsExactly(ADMIN);
		assertThat(jwt.getIssuedAt()).isEqualTo(testNow.toInstant());
		assertThat(jwt.getExpiresAt()).isEqualTo(testNow.plusMinutes(15).toInstant());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));

		String storedHash = jdbc.queryForObject(
				"select token_hash from refresh_token where user_id = ?", String.class, userId);
		assertThat(storedHash).isEqualTo(sha256Hex(refreshToken));
		assertThat(storedHash).isNotEqualTo(refreshToken);
		OffsetDateTime refreshExpiry = jdbc.queryForObject(
				"select expires_at from refresh_token where user_id = ?", OffsetDateTime.class, userId);
		assertThat(refreshExpiry.toInstant())
				.isEqualTo(testNow.plusDays(7).toInstant());
		assertThat(jdbc.queryForObject(
				"select revoked_at from refresh_token where user_id = ?", OffsetDateTime.class, userId))
				.isNull();

		assertThat(jdbc.queryForObject(
				"select failed_login_attempts from app_user where id = ?", Integer.class, userId)).isZero();
		OffsetDateTime lastLogin = jdbc.queryForObject(
				"select last_login_at from app_user where id = ?", OffsetDateTime.class, userId);
		assertThat(lastLogin.toInstant()).isEqualTo(testNow.toInstant());
	}

	@Test
	void wrongPasswordIsUnauthorizedAndIssuesNoRefreshToken() throws Exception {
		seedUser(USERNAME, ADMIN, true, PASSWORD, 0);

		MvcResult result = login(USERNAME, "WrongPassword!");

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("argon2");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
	}

	@Test
	void unknownUsernameIsUnauthorized() throws Exception {
		MvcResult result = login("no-such-user", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
	}

	@Test
	void inactiveUserIsUnauthorized() throws Exception {
		seedUser(USERNAME, ADMIN, false, PASSWORD, 0);

		MvcResult result = login(USERNAME, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isZero();
	}

	@Test
	void tokenWithUnknownSubjectIsUnauthorized() throws Exception {
		String token = TestJwts.forRoles(UUID.randomUUID(), ADMIN);

		MvcResult result = mockMvc.perform(get("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
	}

	@Test
	void tokenWithInactiveSubjectIsUnauthorized() throws Exception {
		UUID inactiveUserId = seedUser("auth-it-inactive", ADMIN, false, PASSWORD, 0);
		String token = TestJwts.forRoles(inactiveUserId, ADMIN);

		MvcResult result = mockMvc.perform(get("/api/v1/contracts")
				.header("Authorization", "Bearer " + token)).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
	}

	@Test
	void writeAfterLoginStampsTheRealAppUserIdInCreatedBy() throws Exception {
		UUID userId = seedUser(USERNAME, ADMIN, true, PASSWORD, 0);
		String accessToken = accessToken(login(USERNAME, PASSWORD));

		MvcResult result = mockMvc.perform(post("/api/v1/contracts")
						.header("Authorization", "Bearer " + accessToken)
						.header("Idempotency-Key", "auth-login-write")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(contractRequest())))
				.andReturn();

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(201);
		UUID contractId = UUID.fromString(objectMapper
				.readTree(result.getResponse().getContentAsString()).get("data").get("id").asText());
		assertThat(jdbc.queryForObject("select created_by from contract where id = ?", UUID.class, contractId))
				.isEqualTo(userId);
		assertThat(jdbc.queryForObject("select updated_by from contract where id = ?", UUID.class, contractId))
				.isEqualTo(userId);
	}

	private UUID seedUser(String username, String role, boolean active, String rawPassword, int failedAttempts) {
		return jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active,
					failed_login_attempts, locked_until, last_login_at, created_at, updated_at)
					values (?, ?, ?, ?, ?, ?, null, null, ?, ?)
					returning id""",
				UUID.class,
				username,
				passwordEncoder.encode(rawPassword),
				"Auth IT " + role,
				role,
				active,
				failedAttempts,
				testNow,
				testNow);
	}

	private MvcResult login(String username, String password) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new LoginRequest(username, password)));
		return mockMvc.perform(request).andReturn();
	}

	private String accessToken(MvcResult loginResult) throws Exception {
		return objectMapper.readTree(loginResult.getResponse().getContentAsString())
				.get("data").get("access_token").asText();
	}

	private void assertEnvelopeError(MvcResult result, String expectedCode) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("data").isNull()).isTrue();
		assertThat(body.get("error").get("code").asText()).isEqualTo(expectedCode);
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static CreateContractRequest contractRequest() {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31));
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
