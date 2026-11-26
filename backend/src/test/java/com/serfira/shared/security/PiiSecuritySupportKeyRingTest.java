package com.serfira.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T28a / ADR-025: the static bridge over a key ring — {@code v2:<kid>:} writes under the active key, reads of
 * {@code v2} under any ring key and of {@code v1} under the legacy key, and HMAC candidates active first.
 */
class PiiSecuritySupportKeyRingTest {

	/** The decoded test-profile keys ({@code src/test/resources/application.properties}). */
	static final byte[] TEST_PROFILE_ENCRYPTION_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	static final byte[] TEST_PROFILE_HMAC_KEY = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);

	private static final byte[] K1_AES = "1".repeat(32).getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "2".repeat(32).getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_AES = "3".repeat(32).getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_HMAC = "4".repeat(32).getBytes(StandardCharsets.UTF_8);

	@BeforeEach
	void bindRotatedRing() {
		PiiSecuritySupport.configure(ring("k2", "k1"));
	}

	@AfterEach
	void restoreTestProfileRing() {
		PiiSecuritySupport.configure(TEST_PROFILE_ENCRYPTION_KEY, TEST_PROFILE_HMAC_KEY);
	}

	private static PiiKeyRing ring(String activeKid, String legacyKid) {
		return new PiiKeyRing(List.of(PiiKey.of("k1", K1_AES, K1_HMAC), PiiKey.of("k2", K2_AES, K2_HMAC)),
				activeKid, legacyKid, PiiEnvelopeVersion.V2);
	}

	@Test
	void newWritesAreV2UnderTheActiveKid() {
		String sealed = PiiSecuritySupport.encrypt("3171012501900001");

		assertThat(sealed).startsWith("v2:k2:");
		assertThat(PiiSecuritySupport.decrypt(sealed)).isEqualTo("3171012501900001");
	}

	@Test
	void aV2EnvelopeUnderANonActiveKidDecrypts() {
		PiiSecuritySupport.configure(ring("k1", "k1"));
		String sealedUnderK1 = PiiSecuritySupport.encrypt("3171012501900001");
		PiiSecuritySupport.configure(ring("k2", "k1"));

		assertThat(sealedUnderK1).startsWith("v2:k1:");
		assertThat(PiiSecuritySupport.decrypt(sealedUnderK1)).isEqualTo("3171012501900001");
	}

	@Test
	void aV1EnvelopeDecryptsUnderTheLegacyKid() {
		String legacy = LegacyV1Envelope.seal(K1_AES, "08123456789");

		assertThat(PiiSecuritySupport.decrypt(legacy)).isEqualTo("08123456789");
	}

	@Test
	void aV1RingWritesWhatThePreviousVersionReads() {
		// ADR-025 D7 phase 1: an older instance (a v1-only reader) must read every row this version writes.
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);

		String sealed = PiiSecuritySupport.encrypt("3171012501900001");

		assertThat(sealed).startsWith("v1:");
		assertThat(LegacyV1Envelope.open(K1_AES, sealed)).isEqualTo("3171012501900001");
		assertThat(PiiSecuritySupport.decrypt(sealed)).isEqualTo("3171012501900001");
	}

	@Test
	void aV1EnvelopeWithoutALegacyKeyFails() {
		PiiSecuritySupport.configure(ring("k2", null));
		String legacy = LegacyV1Envelope.seal(K1_AES, "08123456789");

		assertThatThrownBy(() -> PiiSecuritySupport.decrypt(legacy)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void anUnknownKidFails() {
		String body = PiiSecuritySupport.encrypt("3171012501900001").substring("v2:k2:".length());

		assertThatThrownBy(() -> PiiSecuritySupport.decrypt("v2:k9:" + body))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("not in the key ring")
				.satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("k9"));
	}

	@Test
	void anUnknownNumericKidThatCouldBeANikIsNeverEchoed() {
		// A 16-digit NIK matches the kid syntax: a corrupted envelope must not put it in an exception message.
		String nik = "3171012501900001";
		String body = PiiSecuritySupport.encrypt("x").substring("v2:k2:".length());

		assertThatThrownBy(() -> PiiSecuritySupport.decrypt("v2:" + nik + ":" + body))
				.isInstanceOf(IllegalArgumentException.class)
				.satisfies(failure -> {
					for (Throwable current = failure; current != null; current = current.getCause()) {
						assertThat(String.valueOf(current.getMessage())).doesNotContain(nik).doesNotContain(body);
					}
				});
	}

	static List<String> malformedEnvelopes() {
		return List.of("v2:", "v2:k1", "v2::QUFBQQ==", "v2:K1:QUFBQQ==", "v2:k1:not*base64",
				"v2:k1:" + Base64.getEncoder().encodeToString(new byte[12]), "v3:k1:QUFBQQ==", "3171012501900001");
	}

	@ParameterizedTest
	@MethodSource("malformedEnvelopes")
	void malformedEnvelopesFailWithoutEchoingTheInput(String envelope) {
		assertThatThrownBy(() -> PiiSecuritySupport.decrypt(envelope))
				.isInstanceOf(IllegalArgumentException.class)
				.satisfies(failure -> {
					if (envelope.length() > 3) {
						assertThat(failure.getMessage()).doesNotContain(envelope.substring(3));
					}
				});
	}

	@Test
	void aRelabelledEnvelopeFailsTheTag() {
		String sealed = PiiSecuritySupport.encrypt("3171012501900001");
		String relabelled = "v2:k1:" + sealed.substring("v2:k2:".length());

		assertThatThrownBy(() -> PiiSecuritySupport.decrypt(relabelled)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aTamperedV2EnvelopeFailsTheTag() {
		String sealed = PiiSecuritySupport.encrypt("3171012501900001");
		byte[] body = Base64.getDecoder().decode(sealed.substring("v2:k2:".length()));
		body[body.length - 1] ^= 0x01;

		assertThatThrownBy(() -> PiiSecuritySupport.decrypt("v2:k2:" + Base64.getEncoder().encodeToString(body)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void hmacHexIsTheActiveKeyAndFirstCandidate() {
		List<String> candidates = PiiSecuritySupport.hmacCandidates("3171012501900001");

		assertThat(candidates).hasSize(2);
		assertThat(candidates.get(0)).isEqualTo(PiiSecuritySupport.hmacHex("3171012501900001"))
				.isEqualTo(hmac(K2_HMAC, "3171012501900001"));
		assertThat(candidates.get(1)).isEqualTo(hmac(K1_HMAC, "3171012501900001"));
	}

	@Test
	void aRingOfOneHashesExactlyAsBefore() {
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);

		assertThat(PiiSecuritySupport.hmacHex("3171012501900001")).isEqualTo(hmac(K1_HMAC, "3171012501900001"));
		assertThat(PiiSecuritySupport.hmacCandidates("3171012501900001"))
				.containsExactly(hmac(K1_HMAC, "3171012501900001"));
	}

	@Test
	void ringIntrospection() {
		assertThat(PiiSecuritySupport.activeKid()).isEqualTo("k2");
		assertThat(PiiSecuritySupport.legacyKid()).isEqualTo(java.util.Optional.of("k1"));
		assertThat(PiiSecuritySupport.ringKids()).containsExactly("k2", "k1");
		assertThat(PiiSecuritySupport.writeEnvelope()).isEqualTo(PiiEnvelopeVersion.V2);
	}

	@Test
	void hmacHexUnderAKidMatchesThatKidsCandidate() {
		String value = "3171012501900001";

		assertThat(PiiSecuritySupport.hmacHex("k1", value)).isEqualTo(PiiSecuritySupport.hmacCandidates(value).get(1));
		assertThat(PiiSecuritySupport.hmacHex("k2", value)).isEqualTo(PiiSecuritySupport.hmacHex(value));
	}

	@Test
	void hmacHexUnderAKidOutsideTheRingFailsLoudly() {
		assertThatThrownBy(() -> PiiSecuritySupport.hmacHex("k9", "x"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k9")
				.hasMessageContaining("not in the key ring");
	}

	@Test
	void hmacHexUnderAnInvalidKidDoesNotEchoIt() {
		String notAKid = "3171-0125-0190-0001";

		assertThatThrownBy(() -> PiiSecuritySupport.hmacHex(notAKid, "x"))
				.isInstanceOf(IllegalStateException.class)
				.satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(notAKid));
	}

	@Test
	void sealingKidNamesTheKeyThatSealedAnEnvelope() {
		assertThat(PiiSecuritySupport.sealingKid(PiiSecuritySupport.encrypt("x"))).contains("k2");
		PiiSecuritySupport.configure(ring("k1", "k1"));
		String underK1 = PiiSecuritySupport.encrypt("x");
		PiiSecuritySupport.configure(ring("k2", "k1"));
		assertThat(PiiSecuritySupport.sealingKid(underK1)).contains("k1");
		String v1 = LegacyV1Envelope.seal(K1_AES, "x");
		assertThat(PiiSecuritySupport.sealingKid(v1)).contains("k1");
		assertThat(PiiSecuritySupport.sealingKid("v2:k9:QUFBQQ==")).isEmpty();
		assertThat(PiiSecuritySupport.sealingKid("v2:K1:QUFBQQ==")).isEmpty();
		assertThat(PiiSecuritySupport.sealingKid("garbage")).isEmpty();
		PiiSecuritySupport.configure(ring("k2", null));
		assertThat(PiiSecuritySupport.sealingKid(v1)).isEmpty();
	}

	@Test
	void isActiveV2Envelope() {
		PiiSecuritySupport.configure(ring("k1", "k1"));
		String underK1 = PiiSecuritySupport.encrypt("x");
		PiiSecuritySupport.configure(ring("k2", "k1"));

		assertThat(PiiSecuritySupport.isActiveV2Envelope(PiiSecuritySupport.encrypt("x"))).isTrue();
		assertThat(PiiSecuritySupport.isActiveV2Envelope(underK1)).isFalse();
		assertThat(PiiSecuritySupport.isActiveV2Envelope(LegacyV1Envelope.seal(K1_AES, "x"))).isFalse();
	}

	private static String hmac(byte[] key, String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
