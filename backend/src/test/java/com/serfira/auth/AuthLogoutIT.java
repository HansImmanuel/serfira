package com.serfira.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.serfira.TestcontainersConfiguration;
import com.serfira.auth.application.LoginRequest;
import com.serfira.auth.application.RefreshTokenRequest;
import com.serfira.auth.application.TokenPairResponse;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.security.AppRole;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Logout for T21b (Addendum §3.3, ADR-022 addendum D11).
 *
 * <p>Logout revokes the presented refresh token and is idempotent: repeating it, or presenting a
 * token that never existed, still answers 200 with a null body. That is deliberate — an error would
 * let a caller probe whether a token is real, and a client retrying after a dropped response must
 * not see a failure. Only the token that was presented is retired; a second session of the same user
 * keeps refreshing.
 */
@Import({TestcontainersConfiguration.class, AuthClockTestConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class AuthLogoutIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();
	private static final String USERNAME = "logout-it-admin";
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
	void logoutRevokesTheTokenAndPreventsRefresh() throws Exception {
		UUID userId = seedUser();
		TokenPairResponse issued = login();

		MvcResult logout = logout(issued.refreshToken());

		assertThat(logout.getResponse().getStatus())
				.as("response body: %s", logout.getResponse().getContentAsString())
				.isEqualTo(200);
		assertThat(objectMapper.readTree(logout.getResponse().getContentAsString()).get("data").isNull()).isTrue();
		OffsetDateTime revokedAt = jdbc.queryForObject(
				"select revoked_at from refresh_token where token_hash = ?", OffsetDateTime.class,
				sha256Hex(issued.refreshToken()));
		assertThat(revokedAt.toInstant()).isEqualTo(testNow.toInstant());
		// Logout is not a rotation: there is no replacement to link to.
		assertThat(jdbc.queryForObject(
				"select replaced_by_id from refresh_token where user_id = ?", UUID.class, userId)).isNull();

		assertThat(refresh(issued.refreshToken()).getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void logoutIsIdempotentForRepeatedAndUnknownTokens() throws Exception {
		seedUser();
		TokenPairResponse issued = login();

		assertThat(logout(issued.refreshToken()).getResponse().getStatus()).isEqualTo(200);
		MvcResult second = logout(issued.refreshToken());
		MvcResult unknown = logout(UUID.randomUUID().toString());

		assertThat(second.getResponse().getStatus()).isEqualTo(200);
		assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
		assertThat(objectMapper.readTree(unknown.getResponse().getContentAsString()).get("data").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from refresh_token", Long.class)).isEqualTo(1);
	}

	@Test
	void logoutRetiresOnlyThePresentedSession() throws Exception {
		seedUser();
		TokenPairResponse firstDevice = login();
		TokenPairResponse secondDevice = login();

		assertThat(logout(firstDevice.refreshToken()).getResponse().getStatus()).isEqualTo(200);
		MvcResult stillUsable = refresh(secondDevice.refreshToken());

		assertThat(stillUsable.getResponse().getStatus())
				.as("response body: %s", stillUsable.getResponse().getContentAsString())
				.isEqualTo(200);
	}

	@Test
	void logoutWithBlankTokenIsAValidationError() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"refresh_token\": \"\"}"))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString()).get("error");
		assertThat(error.get("code").asText()).isEqualTo("VALIDATION_ERROR");
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
				"Logout IT " + ADMIN,
				ADMIN,
				testNow,
				testNow);
	}

	private TokenPairResponse login() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new LoginRequest(USERNAME, PASSWORD))))
				.andReturn();
		assertThat(result.getResponse().getStatus())
				.as("login body: %s", result.getResponse().getContentAsString())
				.isEqualTo(200);
		JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
		return new TokenPairResponse(data.get("access_token").asText(), data.get("refresh_token").asText());
	}

	private MvcResult logout(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/logout")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
				.andReturn();
	}

	private MvcResult refresh(String refreshToken) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/refresh")
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
				.andReturn();
	}

	private static String sha256Hex(String input) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
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
