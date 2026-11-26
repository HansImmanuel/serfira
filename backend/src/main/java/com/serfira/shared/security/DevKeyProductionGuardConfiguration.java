package com.serfira.shared.security;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a production deployment on the documented {@code docker-compose} development
 * keys (T21b, review CR-16), checking every member of the PII key ring (T28a, ADR-025), not only the
 * flat {@code k1} pair.
 *
 * <p>The compose file ships fixed, public key material so local development needs no setup. Those
 * values are in the repository, which makes them useless as secrets: anyone who has read the file
 * can mint a valid JWT or decrypt PII at rest. Deploying with them would be a silent, total
 * compromise, so the failure is loud instead — startup stops and the message names the property to
 * set, never the key itself.
 *
 * <p>It only trips when a production profile is active; dev, test and demo must keep working out of
 * the box. {@link Environment#matchesProfiles(String...)} is used rather than
 * {@code getActiveProfiles()} so a deployment whose {@code spring.profiles.default} is
 * {@code prod}/{@code production}, with no explicitly active profile, is still guarded. Blank
 * values are left alone too: the key-owning configurations ({@code JwtDecoder},
 * {@code PiiSecurityConfiguration}) already fail fast on a missing key with a clearer message, and
 * so does {@code AuthService}.
 *
 * <p>The comparison decodes both values and compares the key bytes. Java's Base64 decoder accepts
 * valid unpadded representations, so comparing the textual strings would let a deployment keep the
 * exact public key by removing its trailing {@code =} padding. Malformed configured base64 is not
 * treated as a dev key: the key owner reports that failure with its own message.
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
	 * Reads the raw configured value with {@link Environment#getProperty(String)} rather than
	 * {@code @Value}: the guard must see exactly what the key-owning bean will bind, including a
	 * value that arrived as an environment variable, and it needs no placeholder infrastructure of
	 * its own.
	 */
	@Bean
	DevKeysValidated devKeysValidated(Environment environment) {
		if (!environment.matchesProfiles("prod", "production")) {
			return new DevKeysValidated();
		}

		List<GuardedKey> configuredKeys = new ArrayList<>();
		configuredKeys.add(new GuardedKey("serfira.security.jwt.secret-base64", "SERFIRA_SECURITY_JWT_SECRET_BASE64",
				environment.getProperty("serfira.security.jwt.secret-base64"), DEV_JWT_SECRET_BASE64));
		// Every PII ring member (T28a, ADR-025): the flat k1 pair and each serfira.security.pii.keys.<kid> entry.
		for (PiiKeyRingProperties.ConfiguredKey key : PiiKeyRingProperties.configuredKeys(environment)) {
			configuredKeys.add(new GuardedKey(key.encryptionProperty(), key.encryptionEnvironmentVariable(),
					key.encryptionValue(), DEV_PII_ENCRYPTION_KEY));
			configuredKeys.add(new GuardedKey(key.hmacProperty(), key.hmacEnvironmentVariable(),
					key.hmacValue(), DEV_PII_HMAC_KEY));
		}

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

	/** A guarded property, the environment variable that overrides it, and its public dev value. */
	private record GuardedKey(
			String property, String environmentVariable, String configuredValue, String devValue) {

		boolean isDevDefault() {
			// Blank is a missing key, not a dev key: the owning configuration reports that better.
			if (configuredValue == null || configuredValue.isBlank()) {
				return false;
			}
			// Trimmed exactly as the key loader trims (PiiKeyRingProperties): a dev key with a trailing
			// newline, as a secret mounted from a file has, is still the dev key.
			byte[] configured = decodeOrNull(configuredValue.trim());
			if (configured == null) {
				// Malformed base64 belongs to the key owner; it must not look like a valid key here.
				return false;
			}
			return MessageDigest.isEqual(configured, Base64.getDecoder().decode(devValue));
		}

		private static byte[] decodeOrNull(String value) {
			try {
				return Base64.getDecoder().decode(value);
			} catch (IllegalArgumentException ex) {
				return null;
			}
		}
	}

	/** Marker bean whose creation runs the check during context startup. */
	public record DevKeysValidated() {
	}
}
