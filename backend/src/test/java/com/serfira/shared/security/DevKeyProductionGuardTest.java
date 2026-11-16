package com.serfira.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

	private static final String PRODUCTION_JWT =
			"ZGVwbG95bWVudC1qd3Qta2V5LW5vdC10aGUtcHVibGljLWRldi1vbmUtMDEyMzQ1Njc4OQ==";
	private static final String PRODUCTION_PII_ENCRYPTION =
			"cHJvZHVjdGlvbi1waWktZW5jcnlwdGlvbi1rZXktbm90LWRldi0wMTIzNDU2Nzg5";
	private static final String PRODUCTION_PII_HMAC =
			"cHJvZHVjdGlvbi1waWktaG1hYy1rZXktbm90LWRldi0wMTIzNDU2Nzg5MA==";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(DevKeyProductionGuardConfiguration.class);

	@Test
	void productionProfileWithAllDevKeysRefusesToStart() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						"serfira.security.jwt.secret-base64=" + DEV_JWT,
						"serfira.security.pii.encryption-key=" + DEV_PII_ENCRYPTION,
						"serfira.security.pii.hmac-key=" + DEV_PII_HMAC)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message)
							.contains("serfira.security.jwt.secret-base64")
							.contains("SERFIRA_SECURITY_JWT_SECRET_BASE64")
							.contains("serfira.security.pii.encryption-key")
							.contains("SERFIRA_SECURITY_PII_ENCRYPTION_KEY")
							.contains("serfira.security.pii.hmac-key")
							.contains("SERFIRA_SECURITY_PII_HMAC_KEY");
					// The message names the property, never the value: a startup log keeps secrets.
					assertThat(message)
							.doesNotContain(DEV_JWT)
							.doesNotContain(DEV_PII_ENCRYPTION)
							.doesNotContain(DEV_PII_HMAC);
				});
	}

	@Test
	void productionProfileWithOneDevKeyAlsoRefusesToStart() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						"serfira.security.jwt.secret-base64=" + PRODUCTION_JWT,
						"serfira.security.pii.encryption-key=" + PRODUCTION_PII_ENCRYPTION,
						"serfira.security.pii.hmac-key=" + DEV_PII_HMAC)
				.run(context -> {
					String message = fullMessage(context.getStartupFailure());

					assertThat(message).contains("serfira.security.pii.hmac-key");
					assertThat(message)
							.doesNotContain("serfira.security.jwt.secret-base64")
							.doesNotContain("serfira.security.pii.encryption-key");
				});
	}

	@Test
	void productionProfileWithDeploymentKeysStarts() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.withPropertyValues(
						"serfira.security.jwt.secret-base64=" + PRODUCTION_JWT,
						"serfira.security.pii.encryption-key=" + PRODUCTION_PII_ENCRYPTION,
						"serfira.security.pii.hmac-key=" + PRODUCTION_PII_HMAC)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void productionSpellingIsAlsoGuarded() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
				.withPropertyValues("serfira.security.jwt.secret-base64=" + DEV_JWT)
				.run(context -> assertThat(fullMessage(context.getStartupFailure()))
						.contains("serfira.security.jwt.secret-base64"));
	}

	@Test
	void demoAndTestProfilesKeepTheDevKeys() {
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
				.withPropertyValues(
						"serfira.security.jwt.secret-base64=" + DEV_JWT,
						"serfira.security.pii.encryption-key=" + DEV_PII_ENCRYPTION,
						"serfira.security.pii.hmac-key=" + DEV_PII_HMAC)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void noProfileAtAllIsNotTreatedAsProduction() {
		runner.withPropertyValues("serfira.security.jwt.secret-base64=" + DEV_JWT)
				.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void missingKeysAreLeftToTheKeyOwners() {
		// A blank key is a different failure with a clearer message (JwtDecoder, PiiSecurityConfiguration).
		runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
				.run(context -> assertThat(context).hasNotFailed());
	}

	private static String fullMessage(Throwable failure) {
		StringBuilder text = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			text.append(current.getMessage()).append('\n');
		}
		return text.toString();
	}
}
