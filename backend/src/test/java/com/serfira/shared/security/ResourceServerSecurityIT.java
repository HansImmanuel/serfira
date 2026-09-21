package com.serfira.shared.security;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.serfira.TestcontainersConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2 readiness IT for the JWT resource-server chain (review CRIT-1):
 * default-deny with the standard error envelope, and audit attribution — a write performed
 * under a valid token records the JWT subject as {@code created_by}, never SYSTEM.
 */
@Import({ TestcontainersConfiguration.class, AuditedAssetTestController.class })
@SpringBootTest
@AutoConfigureMockMvc
class ResourceServerSecurityIT {

	/** Mirrors serfira.security.jwt.secret-base64 in src/test/resources/application.properties. */
	private static final byte[] TEST_SECRET = Base64.getDecoder()
			.decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");

	private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	private UUID actorId;

	@BeforeEach
	void resetAndSeedActor() {
		jdbc.execute("""
				TRUNCATE TABLE
					refresh_token, idempotency_keys, job_run, reconciliation_exception, outbox_events,
					settlement_credit_application, settlement_allocation, penalty_adjustment, penalty_accrual,
					contract_credit_application, contract_credit, payment_allocation, payment,
					settlement_quote, settlement, installment, journal_line, journal_entry,
					contract, asset, customer
					CASCADE""");
		// Keep the seeded SYSTEM user intact; only clear IT-created actors.
		jdbc.update("delete from app_user where id <> ?", SYSTEM_USER_ID);
		// The JWT subject must resolve to a real app_user — created_by carries an FK to it.
		actorId = jdbc.queryForObject("""
				insert into app_user (username, password_hash, full_name, role, is_active, created_at, updated_at)
					values (?, 'test-hash', 'IT Actor', 'ADMIN_OPERASIONAL', TRUE, clock_timestamp(), clock_timestamp())
					returning id""", UUID.class, "it-actor-" + UUID.randomUUID());
	}

	@Test
	void unauthenticatedWriteIsRejectedWithStandardEnvelope() throws Exception {
		MvcResult result = mockMvc.perform(post("/api/v1/__test-audit/assets"))
				.andExpect(status().isUnauthorized())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.get("data").isNull()).isTrue();
		assertThat(body.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
	}

	@Test
	void forgedTokenIsRejectedAsUnauthorized() throws Exception {
		String forged = jwtFor(SYSTEM_USER_ID, TEST_SECRET);
		String tampered = tamperSignature(forged);
		MvcResult result = mockMvc.perform(post("/api/v1/__test-audit/assets")
						.header("Authorization", "Bearer " + tampered))
				.andExpect(status().isUnauthorized())
				.andReturn();
		String content = result.getResponse().getContentAsString();
		JsonNode body = objectMapper.readTree(content);
		assertThat(body.get("error"))
				.as("response body: %s", content)
				.isNotNull();
		assertThat(body.get("error").get("code").asText()).isEqualTo("UNAUTHORIZED");
	}

	@Test
	void authenticatedWriteRecordsJwtSubjectAsCreatedBy() throws Exception {
		String token = jwtFor(actorId, TEST_SECRET);

		MvcResult result = mockMvc.perform(post("/api/v1/__test-audit/assets")
						.header("Authorization", "Bearer " + token)
						.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		UUID assetId = UUID.fromString(body.get("data").get("assetId").asText());

		UUID createdBy = jdbc.queryForObject("select created_by from asset where id = ?", UUID.class, assetId);
		assertThat(createdBy).isEqualTo(actorId);
	}

	@Test
	void writeWithoutTokenStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/__test-audit/assets"))
				.andExpect(status().isUnauthorized());
		Integer rows = jdbc.queryForObject("select count(*) from asset", Integer.class);
		assertThat(rows).isZero();
	}

	private String jwtFor(UUID subject, byte[] secret) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(subject.toString())
				.claim("roles", java.util.List.of("ADMIN_OPERASIONAL"))
				.issueTime(Date.from(Instant.now()))
				.expirationTime(Date.from(Instant.now().plusSeconds(600)))
				.build();
		SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
		SecretKey key = new SecretKeySpec(secret, "HmacSHA256");
		JWSSigner signer = new MACSigner(key);
		signedJwt.sign(signer);
		return signedJwt.serialize();
	}

	/**
	 * Flips the last base64url character of the signature. The final character of a 32-byte HS256 signature
	 * encodes only 4 significant bits plus 2 zero padding bits, so replacing it with 'A' (value 0) leaves the
	 * decoded signature byte-identical whenever the original character is 'A', 'B', 'C', or 'D' (all of which
	 * share the 0000 nibble) — the token would stay valid and the test would fail with 200 instead of 401.
	 * 'E' is used in that case, because its nibble differs.
	 */
	private String tamperSignature(String token) {
		String[] parts = token.split("\\.");
		char last = parts[2].charAt(parts[2].length() - 1);
		char flipped = (last >= 'A' && last <= 'D') ? 'E' : 'A';
		return parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 1) + flipped;
	}
}
