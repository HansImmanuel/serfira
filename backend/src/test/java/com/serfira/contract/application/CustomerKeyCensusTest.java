package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.shared.security.LegacyV1Envelope;
import com.serfira.shared.security.PiiEnvelopeVersion;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiKey;
import com.serfira.shared.security.PiiKeyRing;
import com.serfira.shared.security.PiiSecuritySupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T28b: the census classifies every customer row against the active key without writing anything. */
class CustomerKeyCensusTest {

	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_AES = "rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_HMAC = "rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8);

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "081234567890";

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

	/** Seals under {@code kid} by making it the active key for the call, then makes k2 active again. */
	private static String sealV2(String kid, String plaintext) {
		bindRing(kid);
		try {
			return PiiSecuritySupport.encrypt(plaintext);
		} finally {
			bindRing("k2");
		}
	}

	private static String nikHash(String kid, String nik) {
		return PiiSecuritySupport.hmacHex(kid, PiiHasher.normalizeNik(nik));
	}

	private static String phoneHash(String kid, String phone) {
		return PiiSecuritySupport.hmacHex(kid, PiiHasher.normalizePhone(phone));
	}

	private static CustomerPiiRow row(String nikEnvelope, String nikHash, String phoneEnvelope, String phoneLookup) {
		return new CustomerPiiRow(UUID.randomUUID(), nikEnvelope, nikHash, phoneEnvelope, phoneLookup);
	}

	private static CustomerPiiRow rowUnder(String envelopeKid, String hashKid, String nik, String phone) {
		return row(sealV2(envelopeKid, nik), nikHash(hashKid, nik), sealV2(envelopeKid, phone), phoneHash(hashKid, phone));
	}

	private static CustomerKeyCensus census(CustomerPiiRow... rows) {
		CustomerKeyCensus.Builder builder = CustomerKeyCensus.builder();
		for (CustomerPiiRow row : rows) {
			builder.accept(row);
		}
		return builder.build();
	}

	@Test
	void aRowOnTheActiveKeyNeedsNoRekey() {
		CustomerKeyCensus census = census(rowUnder("k2", "k2", NIK, PHONE));

		assertThat(census.scannedRows()).isEqualTo(1);
		assertThat(census.rowsToRekey()).isZero();
		assertThat(census.rowsOffActiveHashes()).isZero();
		assertThat(census.rowsByKid()).isEqualTo(Map.of("k2", 1L));
		assertThat(census.blocksRekey()).isFalse();
	}

	@Test
	void v2K1AndV1RowsNeedRekeyAndCountUnderK1() {
		CustomerPiiRow v2 = rowUnder("k1", "k1", NIK, PHONE);
		CustomerPiiRow v1 = row(LegacyV1Envelope.seal(K1_AES, "3171012501900002"), nikHash("k1", "3171012501900002"),
				LegacyV1Envelope.seal(K1_AES, "081234567891"), phoneHash("k1", "081234567891"));

		CustomerKeyCensus census = census(v2, v1);

		assertThat(census.rowsToRekey()).isEqualTo(2);
		assertThat(census.v1Rows()).isEqualTo(1);
		assertThat(census.rowsByKid()).isEqualTo(Map.of("k1", 2L));
		assertThat(census.rowsOffActiveHashes()).isEqualTo(2);
	}

	@Test
	void aRowWithK2EnvelopesButK1HashesIsOffTheActiveHashes() {
		CustomerKeyCensus census = census(rowUnder("k2", "k1", NIK, PHONE));

		assertThat(census.rowsToRekey()).isEqualTo(1);
		assertThat(census.rowsOffActiveHashes()).isEqualTo(1);
	}

	@Test
	void aV1RowUnderAnActiveK1StillNeedsRekey() {
		PiiSecuritySupport.configure(new PiiKeyRing(List.of(PiiKey.of("k1", K1_AES, K1_HMAC)), "k1", "k1",
				PiiEnvelopeVersion.V2));

		CustomerKeyCensus census = census(row(LegacyV1Envelope.seal(K1_AES, NIK), nikHash("k1", NIK),
				LegacyV1Envelope.seal(K1_AES, PHONE), phoneHash("k1", PHONE)));

		assertThat(census.rowsToRekey()).isEqualTo(1);
		assertThat(census.v1Rows()).isEqualTo(1);
		assertThat(census.rowsOffActiveHashes()).isZero();
	}

	@Test
	void oneCustomerUnderTwoKidsIsADuplicate() {
		CustomerPiiRow first = row(sealV2("k1", NIK), nikHash("k1", NIK), sealV2("k1", "081234567891"),
				phoneHash("k1", "081234567891"));
		CustomerPiiRow second = row(sealV2("k2", NIK), nikHash("k2", NIK), sealV2("k2", "081234567892"),
				phoneHash("k2", "081234567892"));
		CustomerKeyCensus nikTwins = census(first, second);

		assertThat(nikTwins.duplicateNikRows()).isEqualTo(1);
		assertThat(nikTwins.duplicatePhoneRows()).isZero();
		assertThat(nikTwins.blocksRekey()).isTrue();

		CustomerPiiRow phoneTwinA = row(sealV2("k1", "3171012501900002"), nikHash("k1", "3171012501900002"),
				sealV2("k1", PHONE), phoneHash("k1", PHONE));
		CustomerPiiRow phoneTwinB = row(sealV2("k2", "3171012501900003"), nikHash("k2", "3171012501900003"),
				sealV2("k2", PHONE), phoneHash("k2", PHONE));
		CustomerKeyCensus phoneTwins = census(phoneTwinA, phoneTwinB);

		assertThat(phoneTwins.duplicatePhoneRows()).isEqualTo(1);
		assertThat(phoneTwins.duplicateNikRows()).isZero();
		assertThat(phoneTwins.blocksRekey()).isTrue();
	}

	@Test
	void anUnknownKidBlocks() {
		String body = sealV2("k2", NIK).substring("v2:k2:".length());
		CustomerPiiRow row = row("v2:k9:" + body, nikHash("k2", NIK), sealV2("k2", PHONE), phoneHash("k2", PHONE));

		CustomerKeyCensus census = census(row);

		assertThat(census.unknownKidRows()).isEqualTo(1);
		assertThat(census.undecryptableRows()).isZero();
		assertThat(census.unreadableRows()).isEqualTo(1);
		assertThat(census.blocksRekey()).isTrue();
	}

	@Test
	void aTamperedEnvelopeBlocks() {
		String real = sealV2("k2", NIK);
		int at = real.length() - 6;
		char flipped = real.charAt(at) == 'A' ? 'B' : 'A';
		String tampered = real.substring(0, at) + flipped + real.substring(at + 1);
		CustomerPiiRow row = row(tampered, nikHash("k2", NIK), sealV2("k2", PHONE), phoneHash("k2", PHONE));

		CustomerKeyCensus census = census(row);

		assertThat(census.undecryptableRows()).isEqualTo(1);
		assertThat(census.unknownKidRows()).isZero();
		assertThat(census.blocksRekey()).isTrue();
	}

	@Test
	void aRowPrintsOnlyItsId() {
		CustomerPiiRow row = rowUnder("k2", "k2", NIK, PHONE);

		String printed = row.toString();

		assertThat(printed).isEqualTo("CustomerPiiRow[id=" + row.id() + "]");
		assertThat(printed).doesNotContain("v2:").doesNotContain(row.nikHash()).doesNotContain(row.phoneLookup());
	}
}
