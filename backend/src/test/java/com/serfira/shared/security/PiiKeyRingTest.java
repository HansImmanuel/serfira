package com.serfira.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** T28a / ADR-025: the key ring's validation and candidate order. */
class PiiKeyRingTest {

	private static byte[] key(char c) {
		return key(c, 32);
	}

	private static byte[] key(char c, int length) {
		return String.valueOf(c).repeat(length).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void aRingOfOneIsActiveAndLegacy() {
		PiiKeyRing ring = PiiKeyRing.single(key('a'), key('b'));

		assertThat(ring.active().kid()).isEqualTo("k1");
		assertThat(ring.legacy().orElseThrow().kid()).isEqualTo("k1");
		assertThat(ring.candidateOrder()).hasSize(1);
	}

	@Test
	void aRingOfOneWritesV1AndAnExplicitRingWritesWhatItIsGiven() {
		assertThat(PiiKeyRing.single(key('a'), key('b')).writeEnvelope()).isEqualTo(PiiEnvelopeVersion.V1);
		assertThat(new PiiKeyRing(List.of(PiiKey.of("k1", key('a'), key('b'))), "k1", "k1", PiiEnvelopeVersion.V2)
				.writeEnvelope()).isEqualTo(PiiEnvelopeVersion.V2);
	}

	@Test
	void v1WritesNeedTheActiveAndLegacyKeyToBeK1() {
		List<PiiKey> keys = List.of(PiiKey.of("k1", key('a'), key('b')), PiiKey.of("k2", key('c'), key('d')));

		assertThatThrownBy(() -> new PiiKeyRing(keys, "k2", "k1", PiiEnvelopeVersion.V1))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("write-envelope");
		assertThatCode(() -> new PiiKeyRing(keys, "k1", "k1", PiiEnvelopeVersion.V1)).doesNotThrowAnyException();
	}

	@Test
	void movingTheLegacyKeyToTheNewActiveKeyDoesNotAllowV1Writes() {
		// The loophole: active = legacy = k2 would write v1 rows under k2 and strand every existing v1 row (k1).
		List<PiiKey> keys = List.of(PiiKey.of("k1", key('a'), key('b')), PiiKey.of("k2", key('c'), key('d')));

		assertThatThrownBy(() -> new PiiKeyRing(keys, "k2", "k2", PiiEnvelopeVersion.V1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("write-envelope")
				.hasMessageContaining("legacy-kid")
				.hasMessageNotContaining("active key is the legacy key");
	}

	@Test
	void v1WritesWithoutALegacyKeyPointAtLegacyKidNotAtV2() {
		List<PiiKey> keys = List.of(PiiKey.of("k1", key('a'), key('b')));

		assertThatThrownBy(() -> new PiiKeyRing(keys, "k1", null, PiiEnvelopeVersion.V1))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("serfira.security.pii.legacy-kid")
				.hasMessageContaining("SERFIRA_SECURITY_PII_LEGACYKID")
				.hasMessageNotContaining("write-envelope");
	}

	@Test
	void theCanonicalConstructorValidatesLikeOf() {
		SecretKeySpec aes = new SecretKeySpec(key('a'), "AES");
		SecretKeySpec hmac = new SecretKeySpec(key('b'), "HmacSHA256");

		assertThatThrownBy(() -> new PiiKey("a:b", aes, hmac)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new PiiKey("K1", aes, hmac)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new PiiKey("k1", new SecretKeySpec(key('a', 31), "AES"), hmac))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> new PiiKey("k1", aes, new SecretKeySpec(key('b', 31), "HmacSHA256")))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> new PiiKey("k1", null, hmac)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new PiiKey("k1", aes, null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void toStringNamesOnlyTheKid() {
		assertThat(PiiKey.of("k7", key('a'), key('b'))).hasToString("PiiKey[kid=k7]");
	}

	@Test
	void candidateOrderIsActiveFirstThenByKid() {
		PiiKeyRing ring = new PiiKeyRing(List.of(
				PiiKey.of("k1", key('a'), key('b')),
				PiiKey.of("k3", key('c'), key('d')),
				PiiKey.of("k2", key('e'), key('f'))), "k2", null, PiiEnvelopeVersion.V2);

		assertThat(ring.candidateOrder()).extracting(PiiKey::kid).containsExactly("k2", "k1", "k3");
	}

	@Test
	void theActiveKidMustBeInTheRing() {
		assertThatThrownBy(() -> new PiiKeyRing(List.of(PiiKey.of("k1", key('a'), key('b'))), "k2", null, PiiEnvelopeVersion.V2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k2");
	}

	@Test
	void theLegacyKidMustBeInTheRingWhenSet() {
		assertThatThrownBy(() -> new PiiKeyRing(List.of(PiiKey.of("k1", key('a'), key('b'))), "k1", "k0", PiiEnvelopeVersion.V2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k0");
	}

	@Test
	void aDuplicateKidIsRejected() {
		assertThatThrownBy(() -> new PiiKeyRing(List.of(
				PiiKey.of("k1", key('a'), key('b')),
				PiiKey.of("k1", key('c'), key('d'))), "k1", null, PiiEnvelopeVersion.V2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k1");
	}

	@Test
	void twoKidsMayNotShareAnEncryptionKey() {
		assertThatThrownBy(() -> new PiiKeyRing(List.of(
				PiiKey.of("k1", key('a'), key('b')),
				PiiKey.of("k2", key('a'), key('d'))), "k1", null, PiiEnvelopeVersion.V2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k1")
				.hasMessageContaining("k2");
	}

	@Test
	void twoKidsMayNotShareAnHmacKey() {
		assertThatThrownBy(() -> new PiiKeyRing(List.of(
				PiiKey.of("k1", key('a'), key('b')),
				PiiKey.of("k2", key('c'), key('b'))), "k1", null, PiiEnvelopeVersion.V2))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("k1")
				.hasMessageContaining("k2");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "K1", "k:1", "key-2", "k_2", "aaaaaaaaaaaaaaaaa"})
	void anInvalidKidIsRejected(String kid) {
		assertThatThrownBy(() -> PiiKey.of(kid, key('a'), key('b'))).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"k1", "2026a", "aaaaaaaaaaaaaaaa"})
	void aValidKidIsAccepted(String kid) {
		assertThatCode(() -> PiiKey.of(kid, key('a'), key('b'))).doesNotThrowAnyException();
	}

	@Test
	void wrongKeyLengthsAreRejectedNamingTheKid() {
		assertThatThrownBy(() -> PiiKey.of("k7", key('a', 31), key('b')))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("k7").hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> PiiKey.of("k7", key('a', 33), key('b')))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("k7").hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> PiiKey.of("k7", key('a'), key('b', 31)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("k7").hasMessageContaining("32 bytes");
	}
}
