package com.serfira.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * T28a / ADR-025: the PII key ring is configured additively — the existing flat pair is ring entry {@code k1},
 * {@code serfira.security.pii.keys.<kid>.*} adds entries — and every misconfiguration fails startup naming the
 * property, never the value.
 */
class PiiKeyRingConfigurationTest {

	private static final String K1_ENCRYPTION = base64("0123456789abcdef0123456789abcdef");
	private static final String K1_HMAC = base64("abcdef0123456789abcdef0123456789");
	private static final String K2_ENCRYPTION = base64("k2-encryption-key-0123456789abcd");
	private static final String K2_HMAC = base64("k2-hmac-key-0123456789abcdefghij");

	private static final String FLAT_ENCRYPTION = "serfira.security.pii.encryption-key=";
	private static final String FLAT_HMAC = "serfira.security.pii.hmac-key=";
	private static final String K2_ENCRYPTION_PROPERTY = "serfira.security.pii.keys.k2.encryption-key=";
	private static final String K2_HMAC_PROPERTY = "serfira.security.pii.keys.k2.hmac-key=";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PiiSecurityConfiguration.class);

	@AfterEach
	void restoreTestProfileRing() {
		PiiSecuritySupport.configure(PiiSecuritySupportKeyRingTest.TEST_PROFILE_ENCRYPTION_KEY,
				PiiSecuritySupportKeyRingTest.TEST_PROFILE_HMAC_KEY);
	}

	private static String base64(String thirtyTwoChars) {
		return Base64.getEncoder().encodeToString(thirtyTwoChars.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void theFlatPairAloneIsARingOfOneUnderK1() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(PiiSecuritySupport.encrypt("x")).startsWith("v2:k1:");
				});
	}

	@Test
	void noKeysAtAllFailsWithTheExistingMessage() {
		runner.run(context -> assertThat(fullMessage(context.getStartupFailure()))
				.contains("'serfira.security.pii.encryption-key' is required"));
	}

	@Test
	void aRotatedRingUsesTheActiveKid() {
		PiiSecuritySupport.configure(PiiKeyRing.single(
				Base64.getDecoder().decode(K1_ENCRYPTION), Base64.getDecoder().decode(K1_HMAC)));
		String sealedUnderK1 = PiiSecuritySupport.encrypt("3171012501900001");

		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						K2_ENCRYPTION_PROPERTY + K2_ENCRYPTION, K2_HMAC_PROPERTY + K2_HMAC,
						"serfira.security.pii.active-kid=k2")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(PiiSecuritySupport.encrypt("x")).startsWith("v2:k2:");
					assertThat(PiiSecuritySupport.decrypt(sealedUnderK1)).isEqualTo("3171012501900001");
				});
	}

	@Test
	void aRingWithoutTheFlatPairNeedsAnActiveKid() {
		runner.withPropertyValues(K2_ENCRYPTION_PROPERTY + K2_ENCRYPTION, K2_HMAC_PROPERTY + K2_HMAC)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("serfira.security.pii.active-kid"));
	}

	@Test
	void aRingWithoutTheFlatPairStartsWithAnActiveKid() {
		runner.withPropertyValues(K2_ENCRYPTION_PROPERTY + K2_ENCRYPTION, K2_HMAC_PROPERTY + K2_HMAC,
						"serfira.security.pii.active-kid=k2")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(PiiSecuritySupport.encrypt("x")).startsWith("v2:k2:");
				});
	}

	@Test
	void anUnknownActiveKidFails() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						"serfira.security.pii.active-kid=k2")
				.run(context -> assertThat(fullMessage(context.getStartupFailure())).contains("k2"));
	}

	@Test
	void anUnknownLegacyKidFails() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						"serfira.security.pii.legacy-kid=k0")
				.run(context -> assertThat(fullMessage(context.getStartupFailure())).contains("k0"));
	}

	@Test
	void aRingEntryWithoutItsHmacKeyFailsNamingTheProperty() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						K2_ENCRYPTION_PROPERTY + K2_ENCRYPTION)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("serfira.security.pii.keys.k2.hmac-key")
						.contains("SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY")
						.doesNotContain(K2_ENCRYPTION));
	}

	@Test
	void theFlatPairCannotAlsoBeDefinedAsKeysK1() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						"serfira.security.pii.keys.k1.encryption-key=" + K2_ENCRYPTION,
						"serfira.security.pii.keys.k1.hmac-key=" + K2_HMAC)
				.run(context -> assertThat(fullMessage(context.getStartupFailure())).contains("'k1'"));
	}

	@Test
	void anInvalidKidFails() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						"serfira.security.pii.keys.key-2.encryption-key=" + K2_ENCRYPTION,
						"serfira.security.pii.keys.key-2.hmac-key=" + K2_HMAC)
				.run(context -> assertThat(context.getStartupFailure()).isNotNull());
	}

	@Test
	void duplicateKeyMaterialFails() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						K2_ENCRYPTION_PROPERTY + K1_ENCRYPTION, K2_HMAC_PROPERTY + K1_HMAC)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("share key material"));
	}

	@Test
	void aWrongLengthRingKeyFails() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						K2_ENCRYPTION_PROPERTY + base64("too-short"), K2_HMAC_PROPERTY + K2_HMAC)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("k2").contains("32 bytes"));
	}

	@Test
	void malformedBase64FailsNamingThePropertyNotTheValue() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC,
						K2_ENCRYPTION_PROPERTY + "not*base64", K2_HMAC_PROPERTY + K2_HMAC)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("serfira.security.pii.keys.k2.encryption-key")
						.doesNotContain("not*base64"));
	}

	@Test
	void ringEntriesBindFromEnvironmentVariables() {
		runner.withPropertyValues(FLAT_ENCRYPTION + K1_ENCRYPTION, FLAT_HMAC + K1_HMAC)
				.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
								Map.of("SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY", K2_ENCRYPTION,
										"SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY", K2_HMAC,
										"SERFIRA_SECURITY_PII_ACTIVEKID", "k2"))))
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(PiiSecuritySupport.encrypt("x")).startsWith("v2:k2:");
				});
	}

	private static String fullMessage(Throwable failure) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			text.append(current.getMessage()).append('\n');
		}
		return text.toString();
	}
}
