package com.serfira.shared.idempotency;

import com.serfira.shared.security.PiiSecuritySupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RequestFingerprint} — the documented refinement of TS §2.5 (ADR-007).
 *
 * <p>Keys are bound once and deliberately not reset: the static bridge is JVM-global and shared with
 * the cached {@code @SpringBootTest} contexts (same reasoning as {@code PiiHasherTest}).
 */
class RequestFingerprintTest {

	@BeforeAll
	static void bindKeys() {
		PiiSecuritySupport.configure("a".repeat(32).getBytes(StandardCharsets.UTF_8),
				"b".repeat(32).getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void isDeterministicForTheSameCanonicalPayload() {
		String canonical = "{\"asset_price\":\"16000000.00\"}";

		assertThat(RequestFingerprint.of(canonical))
				.isEqualTo(RequestFingerprint.of(canonical))
				.hasSize(64);
	}

	@Test
	void differsForDifferentCanonicalPayloads() {
		assertThat(RequestFingerprint.of("{\"tenor_months\":\"12\"}"))
				.isNotEqualTo(RequestFingerprint.of("{\"tenor_months\":\"13\"}"));
	}

	@Test
	void isKeyedRatherThanAPlainSha256Digest() throws Exception {
		// TS §2.5 literally says SHA-256; ADR-007 replaces it with the project's keyed HMAC because
		// the payload carries PII. This test pins that decision.
		String canonical = "{\"customer.nik\":\"3171012501900001\"}";
		String plainSha256 = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));

		assertThat(RequestFingerprint.of(canonical)).isNotEqualTo(plainSha256);
	}
}