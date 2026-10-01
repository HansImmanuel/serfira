package com.serfira.shared.security;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.serfira.TestcontainersConfiguration;
import com.serfira.contract.api.CreateAssetRequest;
import com.serfira.contract.api.CreateContractRequest;
import com.serfira.contract.api.CreateCustomerRequest;
import com.serfira.contract.domain.AssetType;
import com.serfira.contract.domain.InterestScheme;
import com.serfira.support.TestJwts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Token-level rules of the resource-server chain (ADR-005, ADR-015 D1/D4/D5), against the real
 * {@code POST /api/v1/contracts} write path.
 *
 * <ul>
 * <li>A token that is missing, forged, expired, unbounded (no {@code exp}) or that names no actor (missing or
 * non-UUID {@code sub}) is 401 {@code UNAUTHORIZED} and writes nothing. The non-UUID case is review CR-01:
 * before T7 it was accepted and wrote as SYSTEM.</li>
 * <li>A valid token without a usable HTTP role is 403 {@code FORBIDDEN} and writes nothing.</li>
 * <li>An allowed write records the token {@code sub} as {@code created_by}.</li>
 * </ul>
 *
 * <p>Every rejected request carries a fully valid body and {@code Idempotency-Key}, so a missing rule would
 * show up as a written row, not merely as a different status.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationIT {

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String ADMIN = AppRole.ADMIN_OPERASIONAL.name();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	private UUID actorId;

	@BeforeEach
	void resetAndSeedActor() {
		truncateDomainTables();
		// Keep the seeded SYSTEM user intact; only clear IT-created actors.
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		// The JWT subject must resolve to a real app_user: created_by carries an FK to it.
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'IT Actor', 'ADMIN_OPERASIONAL', TRUE, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "it-actor-" + UUID.randomUUID());
	}

	@AfterEach
	void leaveCleanSharedDatabase() {
		// Nothing referencing this suite's actor may survive: other suites delete app_user rows.
		truncateDomainTables();
	}

	static Stream<Arguments> unauthenticatedTokens() {
		return Stream.of(
				tokenCase("no token", subject -> null),
				tokenCase("tampered signature", subject -> TestJwts.tamperSignature(TestJwts.forRoles(subject, ADMIN))),
				tokenCase("wrong signing key", subject -> TestJwts.signedWithWrongKey(subject, ADMIN)),
				tokenCase("alg none", subject -> TestJwts.unsigned(subject, ADMIN)),
				tokenCase("expired", subject -> TestJwts.expired(subject, ADMIN)),
				tokenCase("no exp", subject -> TestJwts.withoutExpiry(subject, ADMIN)),
				tokenCase("missing sub", subject -> TestJwts.withRawSubject(null, ADMIN)),
				tokenCase("non-UUID sub (CR-01)", subject -> TestJwts.withRawSubject("not-a-uuid", ADMIN)));
	}

	@ParameterizedTest(name = "{0} -> 401")
	@MethodSource("unauthenticatedTokens")
	void tokenThatIdentifiesNoActorIsUnauthorizedAndWritesNothing(Function<UUID, String> token) throws Exception {
		MvcResult result = mockMvc.perform(validCreate(token.apply(actorId), "unauthenticated-key")).andReturn();

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(401);
		assertEnvelopeError(result, "UNAUTHORIZED");
		assertNothingWritten();
	}

	static Stream<Arguments> tokensWithoutUsableRole() {
		return Stream.of(
				tokenCase("no roles claim", TestJwts::withoutRolesClaim),
				tokenCase("string-valued roles", subject -> TestJwts.withRolesClaim(subject, ADMIN)),
				tokenCase("only unknown roles", subject -> TestJwts.forRoles(subject, "SUPERUSER", "admin_operasional")),
				tokenCase("SYSTEM", subject -> TestJwts.forRoles(subject, "SYSTEM")),
				tokenCase("SYSTEM + ADMIN_OPERASIONAL", subject -> TestJwts.forRoles(subject, ADMIN, "SYSTEM")));
	}

	@ParameterizedTest(name = "{0} -> 403")
	@MethodSource("tokensWithoutUsableRole")
	void validTokenWithoutUsableRoleIsForbiddenAndWritesNothing(Function<UUID, String> token) throws Exception {
		String bearer = token.apply(actorId);

		MvcResult write = mockMvc.perform(validCreate(bearer, "forbidden-key")).andReturn();
		assertThat(write.getResponse().getStatus()).isEqualTo(403);
		assertEnvelopeError(write, "FORBIDDEN");
		assertNothingWritten();

		MvcResult read = mockMvc.perform(get("/api/v1/contracts").header("Authorization", "Bearer " + bearer))
				.andReturn();
		assertThat(read.getResponse().getStatus()).isEqualTo(403);
		assertEnvelopeError(read, "FORBIDDEN");
	}

	@Test
	void financeMayReadTheContractList() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/contracts")
						.header("Authorization", "Bearer " + TestJwts.forRoles(actorId, AppRole.FINANCE.name())))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void authenticatedWriteRecordsJwtSubjectAsCreatedBy() throws Exception {
		MvcResult result = mockMvc.perform(validCreate(TestJwts.forRoles(actorId, ADMIN), "attribution-key"))
				.andReturn();

		assertThat(result.getResponse().getStatus())
				.as("response body: %s", result.getResponse().getContentAsString())
				.isEqualTo(201);
		UUID contractId = UUID.fromString(
				objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asText());
		assertThat(jdbc.queryForObject("select created_by from contract where id = ?", UUID.class, contractId))
				.isEqualTo(actorId);
		assertThat(jdbc.queryForList("select distinct created_by from customer", UUID.class))
				.containsExactly(actorId);
		assertThat(jdbc.queryForList("select distinct created_by from asset", UUID.class))
				.containsExactly(actorId);
	}

	@Test
	void publicHealthPathNeedsNoToken() throws Exception {
		assertThat(mockMvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	private MockHttpServletRequestBuilder validCreate(String bearer, String idempotencyKey) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/contracts")
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(contractRequest()));
		return bearer == null ? request : request.header("Authorization", "Bearer " + bearer);
	}

	private static CreateContractRequest contractRequest() {
		return new CreateContractRequest(
				new CreateCustomerRequest("Budi Santoso", "3171012501900001", "08123456789", "Jakarta"),
				new CreateAssetRequest(AssetType.MOTORCYCLE, "Honda", "Beat", null, "B1234XY"),
				new BigDecimal("20000000.00"), new BigDecimal("4000000.00"), 12, InterestScheme.FLAT,
				new BigDecimal("0.0150"), LocalDate.of(2026, 1, 31));
	}

	private void assertEnvelopeError(MvcResult result, String expectedCode) throws Exception {
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("data").isNull()).isTrue();
		assertThat(body.get("error").get("code").asText()).isEqualTo(expectedCode);
	}

	private void assertNothingWritten() {
		for (String table : List.of("contract", "customer", "asset", "idempotency_keys", "journal_entry")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table, Long.class))
					.as("rows in %s", table)
					.isZero();
		}
	}

	private static Arguments tokenCase(String name, Function<UUID, String> token) {
		return Arguments.of(Named.of(name, token));
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
