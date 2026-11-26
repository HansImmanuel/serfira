package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.serfira.contract.application.PiiRetirementReport.KeyVerdict;
import com.serfira.contract.application.PiiRetirementReport.Verdict;
import com.serfira.contract.infrastructure.CustomerPiiRow;
import com.serfira.shared.idempotency.IdempotencyKeyUsage.FingerprintKeyUsage;
import com.serfira.shared.security.LegacyV1Envelope;
import com.serfira.shared.security.PiiEnvelopeVersion;
import com.serfira.shared.security.PiiHasher;
import com.serfira.shared.security.PiiKey;
import com.serfira.shared.security.PiiKeyRing;
import com.serfira.shared.security.PiiSecuritySupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T28b / Decision 7: which ring keys can be retired, from the census and the unexpired fingerprints. */
class PiiRetirementReportTest {

	private static final byte[] K1_AES = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K1_HMAC = "abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_AES = "rotation-k2-encryption-key-01234".getBytes(StandardCharsets.UTF_8);
	private static final byte[] K2_HMAC = "rotation-k2-hmac-key-0123456789a".getBytes(StandardCharsets.UTF_8);

	private static final String NIK = "3171012501900001";
	private static final String PHONE = "081234567890";
	private static final List<String> RING = List.of("k2", "k1");
	private static final FingerprintKeyUsage NO_FINGERPRINTS = new FingerprintKeyUsage(Map.of(), 0);

	@BeforeEach
	void bindRotatedRing() {
		PiiSecuritySupport.configure(new PiiKeyRing(
				List.of(PiiKey.of("k1", K1_AES, K1_HMAC), PiiKey.of("k2", K2_AES, K2_HMAC)),
				"k2", "k1", PiiEnvelopeVersion.V2));
	}

	@AfterEach
	void restoreTestProfileRing() {
		PiiSecuritySupport.configure(K1_AES, K1_HMAC);
	}

	private static CustomerPiiRow onActiveKey() {
		return new CustomerPiiRow(UUID.randomUUID(), PiiSecuritySupport.encrypt(NIK), PiiHasher.hashNik(NIK),
				PiiSecuritySupport.encrypt(PHONE), PiiHasher.hashPhone(PHONE));
	}

	private static CustomerPiiRow v1UnderK1() {
		return new CustomerPiiRow(UUID.randomUUID(), LegacyV1Envelope.seal(K1_AES, NIK),
				PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizeNik(NIK)), LegacyV1Envelope.seal(K1_AES, PHONE),
				PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizePhone(PHONE)));
	}

	private static CustomerPiiRow k2EnvelopesK1Hashes() {
		return new CustomerPiiRow(UUID.randomUUID(), PiiSecuritySupport.encrypt(NIK),
				PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizeNik(NIK)), PiiSecuritySupport.encrypt(PHONE),
				PiiSecuritySupport.hmacHex("k1", PiiHasher.normalizePhone(PHONE)));
	}

	private static CustomerPiiRow unknownKid() {
		String body = PiiSecuritySupport.encrypt(NIK).substring("v2:k2:".length());
		return new CustomerPiiRow(UUID.randomUUID(), "v2:k9:" + body, PiiHasher.hashNik(NIK),
				PiiSecuritySupport.encrypt(PHONE), PiiHasher.hashPhone(PHONE));
	}

	private static CustomerKeyCensus census(CustomerPiiRow... rows) {
		CustomerKeyCensus.Builder builder = CustomerKeyCensus.builder();
		for (CustomerPiiRow row : rows) {
			builder.accept(row);
		}
		return builder.build();
	}

	private static PiiRetirementReport report(CustomerKeyCensus census, FingerprintKeyUsage fingerprints) {
		return PiiRetirementReport.of(census, fingerprints, RING, "k2", Optional.of("k1"));
	}

	private static Verdict verdict(PiiRetirementReport report, String kid) {
		return report.verdictFor(kid).orElseThrow().verdict();
	}

	@Test
	void theActiveKidIsNeverRetirable() {
		PiiRetirementReport report = report(census(onActiveKey()), NO_FINGERPRINTS);

		assertThat(verdict(report, "k2")).isEqualTo(Verdict.ACTIVE);
		assertThat(report.activeKid()).isEqualTo("k2");
		assertThat(report.verdictFor("k2").orElseThrow().envelopeRows()).isEqualTo(1);
	}

	@Test
	void aKidWithEnvelopeRowsIsNotRetirable() {
		PiiRetirementReport report = report(census(v1UnderK1()), NO_FINGERPRINTS);

		KeyVerdict k1 = report.verdictFor("k1").orElseThrow();
		assertThat(k1.verdict()).isEqualTo(Verdict.NOT_RETIRABLE);
		assertThat(k1.envelopeRows()).isEqualTo(1);
	}

	@Test
	void noOldKidIsRetirableWhileAnyHashIsOffTheActiveKey() {
		PiiRetirementReport report = report(census(k2EnvelopesK1Hashes()), NO_FINGERPRINTS);

		assertThat(report.rowsOffActiveHashes()).isEqualTo(1);
		assertThat(report.verdictFor("k1").orElseThrow().envelopeRows()).isZero();
		assertThat(verdict(report, "k1")).isEqualTo(Verdict.NOT_RETIRABLE);
	}

	@Test
	void aKidWithAnUnexpiredFingerprintIsNotRetirable() {
		PiiRetirementReport report = report(census(onActiveKey()), new FingerprintKeyUsage(Map.of("k1", 3L), 0));

		KeyVerdict k1 = report.verdictFor("k1").orElseThrow();
		assertThat(k1.verdict()).isEqualTo(Verdict.NOT_RETIRABLE);
		assertThat(k1.unexpiredFingerprints()).isEqualTo(3);
	}

	@Test
	void unlabelledFingerprintsCountAgainstTheLegacyKid() {
		PiiRetirementReport report = report(census(onActiveKey()), new FingerprintKeyUsage(Map.of(), 2));

		KeyVerdict k1 = report.verdictFor("k1").orElseThrow();
		assertThat(k1.verdict()).isEqualTo(Verdict.NOT_RETIRABLE);
		assertThat(k1.unexpiredFingerprints()).isEqualTo(2);
		assertThat(report.verdictFor("k2").orElseThrow().unexpiredFingerprints()).isZero();
	}

	@Test
	void anOldKidWithAllCountsZeroIsRetirable() {
		PiiRetirementReport report = report(census(onActiveKey()), NO_FINGERPRINTS);

		assertThat(verdict(report, "k1")).isEqualTo(Verdict.RETIRABLE);
	}

	@Test
	void unreadableRowsMakeNothingRetirable() {
		PiiRetirementReport report = report(census(onActiveKey(), unknownKid()), NO_FINGERPRINTS);

		assertThat(report.unreadableRows()).isEqualTo(1);
		assertThat(verdict(report, "k1")).isEqualTo(Verdict.NOT_RETIRABLE);
	}

	@Test
	void aFingerprintKidOutsideTheRingIsMissing() {
		PiiRetirementReport report = report(census(onActiveKey()),
				new FingerprintKeyUsage(Map.of("k9", 4L, "k8", 1L), 0));

		assertThat(verdict(report, "k9")).isEqualTo(Verdict.MISSING_FROM_RING);
		assertThat(report.verdictFor("k9").orElseThrow().unexpiredFingerprints()).isEqualTo(4);
		assertThat(report.keys()).extracting(KeyVerdict::kid).containsExactly("k2", "k1", "k8", "k9");
		assertThat(report.verdictFor("k7")).isEmpty();
	}

	@Test
	void logLinesCarryOnlyKidsCountsAndVerdicts() {
		PiiRetirementReport report = report(census(onActiveKey(), v1UnderK1(), unknownKid()),
				new FingerprintKeyUsage(Map.of("k9", 1L), 1));

		List<String> lines = report.logLines();

		assertThat(lines).hasSize(4);
		assertThat(lines).allMatch(line -> line.matches("^pii-rekey (key|summary) [a-zA-Z=0-9_ ]+$"));
		assertThat(lines.get(0)).isEqualTo("pii-rekey key kid=k2 verdict=ACTIVE envelopeRows=2 unexpiredFingerprints=0");
		assertThat(lines.get(3)).startsWith("pii-rekey summary activeKid=k2 rowsOffActiveHashes=");
		assertThat(lines.get(3)).endsWith("unreadableRows=1");
	}
}
