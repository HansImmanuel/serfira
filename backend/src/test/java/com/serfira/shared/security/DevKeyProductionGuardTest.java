package com.serfira.shared.security;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The docker-compose dev-key production guard (T21b, review CR-16).
 *
 * <p>A production deployment that kept the public keys from {@code docker-compose.yml} must not
 * start: those values are in the repository, so anyone can mint a valid JWT or decrypt PII at rest.
 * Dev, test and demo must keep starting unchanged, and the guard must never print the key it
 * rejected — a startup log is exactly where a leaked secret survives.
 *
 * <p>Runs on a bare context instead of {@code @SpringBootTest}: no database, so it is a fast unit
 * test of the guard alone, and the profile is set explicitly rather than inherited from the test
 * configuration.
 */
class DevKeyProductionGuardTest {

	/** The three values the guard compares against, mirrored from docker-compose.yml. */
	private static final String DEV_JWT =
			"c2VyZmlyYS1kZXYtb25seS1qd3Qtc2VjcmV0LWNoYW5nZS1tZS0wMTIzNDU2Nzg5YWJjZGVm";
	private static final String DEV_PII_ENCRYPTION = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	private static final String DEV_PII_HMAC = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	/** The same key bytes with the optional Base64 padding removed. */
	private static final String DEV_PII_ENCRYPTION_UNPADDED = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
	private static final String DEV_PII_HMAC_UNPADDED = "YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk";

	private static final String PRODUCTION_JWT =
			"ZGVwbG95bWVudC1qd3Qta2V5LW5vdC10aGUtcHVibGljLWRldi1vbmUtMDEyMzQ1Njc4OQ==";
	private static final String PRODUCTION_PII_ENCRYPTION =
			"cHJvZHVjdGlvbi1waWktZW5jcnlwdGlvbi1rZXktbm90LWRldi0wMTIzNDU2Nzg5";
	private static final String PRODUCTION_PII_HMAC =
			"cHJvZHVjdGlvbi1waWktaG1hYy1rZXktbm90LWRldi0wMTIzNDU2Nzg5MA==";

	/** A second, deployment-specific ring entry (T28a). */
	private static final String RING_PII_ENCRYPTION = "c2Vjb25kLXJpbmctcGlpLWVuY3J5cHRpb24ta2V5LTAx";
	private static final String RING_PII_HMAC = "c2Vjb25kLXJpbmctcGlpLWhtYWMta2V5LW5vdC1kZXYtMDE=";

	private static final String JWT_PROPERTY = "serfira.security.jwt.secret-base64";
	private static final String PII_ENCRYPTION_PROPERTY = "serfira.security.pii.encryption-key";
	private static final String PII_HMAC_PROPERTY = "serfira.security.pii.hmac-key";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(DevKeyProductionGuardConfiguration.class);

	@Test
	void productionProfileWithAllDevKeysRefusesToStart() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						JWT_PROPERTY + "=" + DEV_JWT,
						PII_ENCRYPTION_PROPERTY + "=" + DEV_PII_ENCRYPTION,
						PII_HMAC_PROPERTY + "=" + DEV_PII_HMAC)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message)
							.contains(JWT_PROPERTY)
							.contains("SERFIRA_SECURITY_JWT_SECRET_BASE64")
							.contains(PII_ENCRYPTION_PROPERTY)
							.contains("SERFIRA_SECURITY_PII_ENCRYPTION_KEY")
							.contains(PII_HMAC_PROPERTY)
							.contains("SERFIRA_SECURITY_PII_HMAC_KEY");
					// The message names the property, never the value: a startup log keeps secrets.
					assertThat(message)
							.doesNotContain(DEV_JWT)
							.doesNotContain(DEV_PII_ENCRYPTION)
							.doesNotContain(DEV_PII_HMAC);
				});
	}

	@ParameterizedTest
	@MethodSource("individualDevKeys")
	void anySingleDevelopmentKeyRefusesStartup(String property, String environmentVariable, String value) {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(property, value))
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains(property).contains(environmentVariable);
					assertThat(message).doesNotContain(value);
				});
	}

	@Test
	void productionProfileWithDeploymentKeysStarts() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						JWT_PROPERTY + "=" + PRODUCTION_JWT,
						PII_ENCRYPTION_PROPERTY + "=" + PRODUCTION_PII_ENCRYPTION,
						PII_HMAC_PROPERTY + "=" + PRODUCTION_PII_HMAC)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void productionSpellingIsAlsoGuarded() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
				.withPropertyValues(JWT_PROPERTY + "=" + DEV_JWT)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains(JWT_PROPERTY));
	}

	@ParameterizedTest
	@ValueSource(strings = {"prod", "production"})
	void aDefaultProductionProfileWithoutActiveProfilesIsAlsoGuarded(String defaultProfile) {
		// spring.profiles.default only applies when no profile is explicitly active; without
		// matchesProfiles the old getActiveProfiles() check saw an empty array and let this pass.
		runner.withPropertyValues(
						"spring.profiles.default=" + defaultProfile,
						JWT_PROPERTY + "=" + DEV_JWT)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains(JWT_PROPERTY));
	}

	@Test
	void unpaddedDevelopmentPiiKeysAreRejected() {
		// Removing the trailing '=' produces identical key bytes: a textual comparison would miss it.
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						JWT_PROPERTY + "=" + PRODUCTION_JWT,
						PII_ENCRYPTION_PROPERTY + "=" + DEV_PII_ENCRYPTION_UNPADDED,
						PII_HMAC_PROPERTY + "=" + DEV_PII_HMAC_UNPADDED)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message)
							.contains(PII_ENCRYPTION_PROPERTY)
							.contains(PII_HMAC_PROPERTY)
							.doesNotContain(JWT_PROPERTY)
							.doesNotContain(DEV_PII_ENCRYPTION_UNPADDED)
							.doesNotContain(DEV_PII_HMAC_UNPADDED);
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"demo", "test"})
	void demoAndTestProfilesKeepTheDevKeys(String profile) {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
				.withPropertyValues(
						JWT_PROPERTY + "=" + DEV_JWT,
						PII_ENCRYPTION_PROPERTY + "=" + DEV_PII_ENCRYPTION,
						PII_HMAC_PROPERTY + "=" + DEV_PII_HMAC)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void noProfileAtAllIsNotTreatedAsProduction() {
		runner.withPropertyValues(JWT_PROPERTY + "=" + DEV_JWT)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void missingKeysAreLeftToTheKeyOwners() {
		// A blank key is a different failure with a clearer message (JwtDecoder, PiiSecurityConfiguration).
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void malformedBase64IsNotMistakenForADevelopmentKey() {
		// The guard must not report a false positive; binding the key will fail with its own message.
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(JWT_PROPERTY + "=not-base64!!")
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void aDevEncryptionKeyAsARingMemberRefusesToStart() {
		// T28a / ADR-025: every ring member is guarded, not only the flat k1 pair.
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(null, null))
				.withPropertyValues(
						"serfira.security.pii.keys.k2.encryption-key=" + DEV_PII_ENCRYPTION,
						"serfira.security.pii.keys.k2.hmac-key=" + RING_PII_HMAC)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains(
							"serfira.security.pii.keys.k2.encryption-key (set SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY)");
					assertThat(message).doesNotContain(DEV_PII_ENCRYPTION).doesNotContain("keys.k2.hmac-key");
				});
	}

	@Test
	void aDevHmacKeyAsARingMemberRefusesToStart() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(null, null))
				.withPropertyValues(
						"serfira.security.pii.keys.k2.encryption-key=" + RING_PII_ENCRYPTION,
						"serfira.security.pii.keys.k2.hmac-key=" + DEV_PII_HMAC)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains(
							"serfira.security.pii.keys.k2.hmac-key (set SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY)");
					assertThat(message).doesNotContain(DEV_PII_HMAC).doesNotContain("keys.k2.encryption-key");
				});
	}

	@Test
	void aDevKeyWithSurroundingWhitespaceIsStillADevKey() {
		// The key loader trims before decoding, so the guard must too: a trailing newline (a secret mounted
		// from a file) must not let the public key through, for the flat pair or for a ring member.
		// A raw property source: withPropertyValues trims, which would hide exactly this case.
		runner.withInitializer(context -> {
					context.getEnvironment().setActiveProfiles("prod");
					context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("raw", Map.of(
							JWT_PROPERTY, PRODUCTION_JWT,
							PII_ENCRYPTION_PROPERTY, DEV_PII_ENCRYPTION + "\n",
							PII_HMAC_PROPERTY, PRODUCTION_PII_HMAC,
							"serfira.security.pii.keys.k2.encryption-key", RING_PII_ENCRYPTION,
							"serfira.security.pii.keys.k2.hmac-key", " " + DEV_PII_HMAC + " ")));
				})
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains(PII_ENCRYPTION_PROPERTY + " (set SERFIRA_SECURITY_PII_ENCRYPTION_KEY)")
							.contains("serfira.security.pii.keys.k2.hmac-key (set SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY)");
					assertThat(message).doesNotContain(DEV_PII_ENCRYPTION).doesNotContain(DEV_PII_HMAC);
				});
	}

	@Test
	void swappedDevPiiKeysInARingMemberRefuseToStart() {
		// The dev HMAC key is 32 bytes, so it is a valid AES key: a dev value in the other role is still public.
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(null, null))
				.withPropertyValues(
						"serfira.security.pii.keys.k2.encryption-key=" + DEV_PII_HMAC,
						"serfira.security.pii.keys.k2.hmac-key=" + DEV_PII_ENCRYPTION)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message)
							.contains("serfira.security.pii.keys.k2.encryption-key (set SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY)")
							.contains("serfira.security.pii.keys.k2.hmac-key (set SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY)");
					assertThat(message).doesNotContain(DEV_PII_ENCRYPTION).doesNotContain(DEV_PII_HMAC);
				});
	}

	@Test
	void theDevJwtSecretAsAPiiHmacKeyRefusesToStart() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(PII_HMAC_PROPERTY, DEV_JWT))
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains(PII_HMAC_PROPERTY + " (set SERFIRA_SECURITY_PII_HMAC_KEY)");
					assertThat(message).doesNotContain(DEV_JWT);
				});
	}

	@Test
	void aProductionRingWithoutDevKeysStarts() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(productionProperties(null, null))
				.withPropertyValues(
						"serfira.security.pii.keys.k2.encryption-key=" + RING_PII_ENCRYPTION,
						"serfira.security.pii.keys.k2.hmac-key=" + RING_PII_HMAC,
						"serfira.security.pii.active-kid=k2")
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static String[] productionProperties(String overriddenProperty, String overriddenValue) {
		return new String[] {
				JWT_PROPERTY + "=" + valueOr(PRODUCTION_JWT, JWT_PROPERTY, overriddenProperty, overriddenValue),
				PII_ENCRYPTION_PROPERTY + "=" + valueOr(PRODUCTION_PII_ENCRYPTION,
						PII_ENCRYPTION_PROPERTY, overriddenProperty, overriddenValue),
				PII_HMAC_PROPERTY + "=" + valueOr(PRODUCTION_PII_HMAC,
						PII_HMAC_PROPERTY, overriddenProperty, overriddenValue)
		};
	}

	private static String valueOr(String productionValue, String property, String overriddenProperty,
			String overriddenValue) {
		return property.equals(overriddenProperty) ? overriddenValue : productionValue;
	}

	private static Stream<Arguments> individualDevKeys() {
		return Stream.of(
				Arguments.of(JWT_PROPERTY, "SERFIRA_SECURITY_JWT_SECRET_BASE64", DEV_JWT),
				Arguments.of(PII_ENCRYPTION_PROPERTY, "SERFIRA_SECURITY_PII_ENCRYPTION_KEY", DEV_PII_ENCRYPTION),
				Arguments.of(PII_HMAC_PROPERTY, "SERFIRA_SECURITY_PII_HMAC_KEY", DEV_PII_HMAC));
	}

	private static String fullMessage(Throwable failure) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			text.append(current.getMessage()).append('\n');
		}
		return text.toString();
	}
}
