package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.shared.security.PiiEnvelopeVersion;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiKey;
import com.serfira.shared.security.PiiKeyRing;
import com.serfira.shared.security.PiiSecuritySupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T28b: the post-write check of one re-keyed row, naming the failed check but never a value. */
class RekeyRowVerifierTest {

	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_AES = "rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_HMAC = "rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8);

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "081234567890";
	private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

	@BeforeEach
	void bindRotatedRing() {
		bindRing("k2");
	}

	@AfterEach
	void restoreTestProfileRing() {
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	private static void bindRing(String activeKid) {
		PiiSecuritySupport.configure(new PiiKeyRing(
				List.of(PiiKey.of("k1", K1_AES, K1_HMAC), PiiKey.of("k2", K2_AES, K2_HMAC)),
				activeKid, "k1", PiiEnvelopeVersion.V2));
	}

	private static RekeyRowVerifier.RekeyedValues expected() {
		return new RekeyRowVerifier.RekeyedValues(ID, NIK, PHONE, PiiHasher.hashNik(NIK), PiiHasher.hashPhone(PHONE));
	}

	private static CustomerPiiRow faithful() {
		return new CustomerPiiRow(ID, PiiSecuritySupport.encrypt(NIK), PiiHasher.hashNik(NIK),
				PiiSecuritySupport.encrypt(PHONE), PiiHasher.hashPhone(PHONE));
	}

	/** The faithful row with the given columns replaced; a null argument keeps the faithful value. */
	private static CustomerPiiRow with(String nikEnvelope, String nikHash, String phoneEnvelope, String phoneLookup) {
		CustomerPiiRow base = faithful();
		return new CustomerPiiRow(ID, nikEnvelope != null ? nikEnvelope : base.nikEnvelope(),
				nikHash != null ? nikHash : base.nikHash(),
				phoneEnvelope != null ? phoneEnvelope : base.phoneEnvelope(),
				phoneLookup != null ? phoneLookup : base.phoneLookup());
	}

	private static String sealUnderK1(String plaintext) {
		bindRing("k1");
		try {
			return PiiSecuritySupport.encrypt(plaintext);
		} finally {
			bindRing("k2");
		}
	}

	private static String k1NikHash() {
		return PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizeNik(NIK));
	}

	private static String k1PhoneHash() {
		return PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizePhone(PHONE));
	}

	private static void assertFails(CustomerPiiRow reread, String check) {
		assertThatThrownBy(() -> RekeyRowVerifier.verify(expected(), reread))
				.isInstanceOf(PiiRekeyVerificationException.class)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("re-keyed customer " + ID + " failed verification: " + check);
	}

	@Test
	void aFaithfulRereadPasses() {
		assertThatCode(() -> RekeyRowVerifier.verify(expected(), faithful())).doesNotThrowAnyException();
	}

	@Test
	void aNikEnvelopeNotUnderTheActiveKeyFails() {
		assertFails(with(sealUnderK1(NIK), null, null, null), "nik envelope is not under the active key");
	}

	@Test
	void aNikThatDecryptsToAnotherValueFails() {
		assertFails(with(PiiSecuritySupport.encrypt("3171012501900002"), null, null, null),
				"nik does not decrypt to its original value");
	}

	@Test
	void aNikHashNotUnderTheActiveKeyFails() {
		assertFails(with(null, k1NikHash(), null, null), "nik_hash is not the active-key hash");
	}

	@Test
	void aPhoneEnvelopeNotUnderTheActiveKeyFails() {
		assertFails(with(null, null, sealUnderK1(PHONE), null), "phone envelope is not under the active key");
	}

	@Test
	void aPhoneThatDecryptsToAnotherValueFails() {
		assertFails(with(null, null, PiiSecuritySupport.encrypt("081234567891"), null),
				"phone does not decrypt to its original value");
	}

	@Test
	void aPhoneLookupNotUnderTheActiveKeyFails() {
		assertFails(with(null, null, null, k1PhoneHash()), "phone_lookup is not the active-key hash");
	}

	@Test
	void failureMessagesCarryNoValues() {
		Pattern hex64 = Pattern.compile("[0-9a-f]{64}");
		List<CustomerPiiRow> bad = List.of(
				with(sealUnderK1(NIK), null, null, null),
				with(PiiSecuritySupport.encrypt("3171012501900002"), null, null, null),
				with(null, k1NikHash(), null, null),
				with(null, null, sealUnderK1(PHONE), null),
				with(null, null, PiiSecuritySupport.encrypt("081234567891"), null),
				with(null, null, null, k1PhoneHash()));

		for (CustomerPiiRow row : bad) {
			assertThatThrownBy(() -> RekeyRowVerifier.verify(expected(), row)).satisfies(e -> {
				assertThat(e.getMessage()).doesNotContain(NIK).doesNotContain(PHONE).doesNotContain("v2:");
				assertThat(hex64.matcher(e.getMessage()).find()).isFalse();
			});
		}
		assertThat(expected().toString()).isEqualTo(ID.toString());
	}
}
