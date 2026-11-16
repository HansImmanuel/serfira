package com.serfira.shared.security;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a production deployment on the documented {@code docker-compose} development
 * keys (T21b, review CR-16).
 *
 * <p>The compose file ships fixed, public key material so local development needs no setup. Those
 * values are in the repository, which makes them useless as secrets: anyone who has read the file
 * can mint a valid JWT or decrypt PII at rest. Deploying with them would be a silent, total
 * compromise, so the failure is loud instead — startup stops and the message names the property to
 * set, never the key itself.
 *
 * <p>It only trips when a production profile is active; dev, test and demo must keep working out of
 * the box. Blank values are left alone too: the key-owning configurations ({@code JwtDecoder},
 * {@code PiiSecurityConfiguration}) already fail fast on a missing key with a clearer message, and
 * so does {@code AuthService}.
 *
 * <p>This deliberately does not do key-strength validation. Length is checked where the keys are
 * bound, and entropy cannot be measured from a string; comparing against known-public values is
 * the one check that catches the real mistake.
 */
@Configuration(proxyBeanMethods = false)
public class DevKeyProductionGuardConfiguration {

	/** The exact values in {@code docker-compose.yml}; changing one there requires changing it here. */
	static final String DEV_JWT_SECRET_BASE64 =
			"c2VyZmlyYS1kZXYtb25seS1qd3Qtc2VjcmV0LWNoYW5nZS1tZS0wMTIzNDU2Nzg5YWJjZGVm";
	static final String DEV_PII_ENCRYPTION_KEY =
			"MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
	static final String DEV_PII_HMAC_KEY =
			"YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

	/**
	 * Profiles that mean "this is a real deployment". The documented override for compose is
	 * {@code SPRING_PROFILES_ACTIVE=prod}; {@code production} is accepted as the same thing rather
	 * than letting a deployment slip through on a spelling difference.
	 */
	static final List<String> PRODUCTION_PROFILES = List.of("prod", "production");

	/**
	 * Reads the raw configured value with {@link Environment#getProperty(String)} rather than
	 * {@code @Value}: the guard must see exactly what the key-owning bean will bind, including a
	 * value that arrived as an environment variable, and it needs no placeholder infrastructure of
	 * its own.
	 */
	@Bean
	DevKeysValidated devKeysValidated(Environment environment) {
		if (!isProduction(environment.getActiveProfiles())) {
			return new DevKeysValidated();
		}

		List<GuardedKey> configuredKeys = List.of(
				new GuardedKey("serfira.security.jwt.secret-base64", "SERFIRA_SECURITY_JWT_SECRET_BASE64",
						environment.getProperty("serfira.security.jwt.secret-base64"), DEV_JWT_SECRET_BASE64),
				new GuardedKey("serfira.security.pii.encryption-key", "SERFIRA_SECURITY_PII_ENCRYPTION_KEY",
						environment.getProperty("serfira.security.pii.encryption-key"), DEV_PII_ENCRYPTION_KEY),
				new GuardedKey("serfira.security.pii.hmac-key", "SERFIRA_SECURITY_PII_HMAC_KEY",
						environment.getProperty("serfira.security.pii.hmac-key"), DEV_PII_HMAC_KEY));

		List<String> offenders = configuredKeys.stream()
				.filter(GuardedKey::isDevDefault)
				.map(key -> key.property() + " (set " + key.environmentVariable() + ")")
				.toList();
		if (!offenders.isEmpty()) {
			throw new IllegalStateException(
					"Refusing to start a production deployment with docker-compose development keys: "
							+ String.join("; ", offenders)
							+ ". The values in docker-compose.yml are public; use deployment-specific keys.");
		}
		return new DevKeysValidated();
	}

	private static boolean isProduction(String[] activeProfiles) {
		for (String profile : activeProfiles) {
			if (PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
				return true;
			}
		}
		return false;
	}

	/** A guarded property, the environment variable that overrides it, and its public dev value. */
	private record GuardedKey(
			String property, String environmentVariable, String configuredValue, String devValue) {

		boolean isDevDefault() {
			// Blank is a missing key, not a dev key: the owning configuration reports that better.
			return configuredValue != null && !configuredValue.isBlank() && configuredValue.equals(devValue);
		}
	}

	/** Marker bean whose creation runs the check during context startup. */
	public record DevKeysValidated() {
	}
}
