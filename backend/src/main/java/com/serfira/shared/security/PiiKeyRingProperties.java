package com.serfira.shared.security;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Reads the PII key ring configuration (ADR-025) from the {@link Environment}. Configuration is additive:
 *
 * <ul>
 *   <li>{@code serfira.security.pii.encryption-key} / {@code hmac-key} — unchanged; when set, ring entry
 *       {@code k1};</li>
 *   <li>{@code serfira.security.pii.keys.<kid>.encryption-key} / {@code .hmac-key} — further entries
 *       ({@code SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY} / {@code _HMACKEY});</li>
 *   <li>{@code serfira.security.pii.active-kid} — defaults to {@code k1} when the flat pair is set, otherwise
 *       required;</li>
 *   <li>{@code serfira.security.pii.legacy-kid} — the key that reads {@code v1:} envelopes; defaults to {@code k1}
 *       when the flat pair is set, otherwise none;</li>
 *   <li>{@code serfira.security.pii.write-envelope} — {@code v1} (default) or {@code v2}: the envelope new values
 *       are written in (ADR-025 D7).</li>
 * </ul>
 *
 * <p>Every failure names the property and its environment variable, never the configured value (Addendum §9).
 */
final class PiiKeyRingProperties {

	static final String PREFIX = "serfira.security.pii";
	static final String FLAT_ENCRYPTION_PROPERTY = PREFIX + ".encryption-key";
	static final String FLAT_HMAC_PROPERTY = PREFIX + ".hmac-key";
	static final String ACTIVE_KID_PROPERTY = PREFIX + ".active-kid";
	static final String LEGACY_KID_PROPERTY = PREFIX + ".legacy-kid";
	static final String KEYS_PROPERTY = PREFIX + ".keys";
	static final String WRITE_ENVELOPE_PROPERTY = PREFIX + ".write-envelope";

	/** AES key must decode to exactly 32 bytes (see PiiKey.of). */
	static final String MISSING_ENCRYPTION_KEY_MESSAGE =
			"'serfira.security.pii.encryption-key' is required (base64, AES-256 key = 32 bytes)"
					+ "; set the SERFIRA_SECURITY_PII_ENCRYPTION_KEY environment variable";

	/** HMAC key must decode to at least 32 bytes. */
	static final String MISSING_HMAC_KEY_MESSAGE =
			"'serfira.security.pii.hmac-key' is required (base64, >= 32 bytes)"
					+ "; set the SERFIRA_SECURITY_PII_HMAC_KEY environment variable";

	private PiiKeyRingProperties() {
	}

	/** One configured ring entry as raw strings, with the property and environment-variable name of each half. */
	record ConfiguredKey(String kid, String encryptionProperty, String encryptionEnvironmentVariable,
			String encryptionValue, String hmacProperty, String hmacEnvironmentVariable, String hmacValue) {

		boolean flat() {
			return encryptionProperty.equals(FLAT_ENCRYPTION_PROPERTY);
		}
	}

	/** The bound shape of one {@code keys.<kid>} entry. */
	record KeyProperties(String encryptionKey, String hmacKey) {
	}

	/**
	 * The raw configured entries: the flat pair first as {@code k1} (when either half is non-blank), then
	 * {@code keys.*} by {@code kid}. Never throws on a value — the dev-key guard reads these too and leaves
	 * malformed configuration to {@link #load}.
	 */
	static List<ConfiguredKey> configuredKeys(Environment environment) {
		List<ConfiguredKey> configured = new ArrayList<>();
		String flatEncryption = environment.getProperty(FLAT_ENCRYPTION_PROPERTY);
		String flatHmac = environment.getProperty(FLAT_HMAC_PROPERTY);
		if (!isBlank(flatEncryption) || !isBlank(flatHmac)) {
			configured.add(new ConfiguredKey(PiiKeyRing.SINGLE_KID,
					FLAT_ENCRYPTION_PROPERTY, "SERFIRA_SECURITY_PII_ENCRYPTION_KEY", flatEncryption,
					FLAT_HMAC_PROPERTY, "SERFIRA_SECURITY_PII_HMAC_KEY", flatHmac));
		}
		Map<String, KeyProperties> ring = new TreeMap<>(Binder.get(environment)
				.bind(KEYS_PROPERTY, Bindable.mapOf(String.class, KeyProperties.class))
				.orElse(Map.of()));
		ring.forEach((kid, key) -> {
			String variablePrefix = "SERFIRA_SECURITY_PII_KEYS_" + kid.toUpperCase(Locale.ROOT) + "_";
			configured.add(new ConfiguredKey(kid,
					KEYS_PROPERTY + "." + kid + ".encryption-key", variablePrefix + "ENCRYPTIONKEY", key.encryptionKey(),
					KEYS_PROPERTY + "." + kid + ".hmac-key", variablePrefix + "HMACKEY", key.hmacKey()));
		});
		return configured;
	}

	/** Builds and validates the ring; any misconfiguration is an {@link IllegalStateException} at startup. */
	static PiiKeyRing load(Environment environment) {
		List<ConfiguredKey> configured = configuredKeys(environment);
		if (configured.isEmpty()) {
			throw new IllegalStateException(MISSING_ENCRYPTION_KEY_MESSAGE);
		}
		boolean flatPairSet = configured.get(0).flat();
		List<PiiKey> keys = new ArrayList<>();
		for (ConfiguredKey key : configured) {
			if (flatPairSet && !key.flat() && key.kid().equals(PiiKeyRing.SINGLE_KID)) {
				throw new IllegalStateException("PII key id 'k1' is configured twice: " + FLAT_ENCRYPTION_PROPERTY
						+ " / " + FLAT_HMAC_PROPERTY + " already define it, so " + KEYS_PROPERTY
						+ ".k1.* must not be set");
			}
			PiiKey.requireValidKid(key.kid());
			byte[] encryption = decode(key.encryptionValue(), key.encryptionProperty(),
					key.encryptionEnvironmentVariable(), key.flat() ? MISSING_ENCRYPTION_KEY_MESSAGE : null,
					"AES-256 key = 32 bytes");
			byte[] hmac = decode(key.hmacValue(), key.hmacProperty(), key.hmacEnvironmentVariable(),
					key.flat() ? MISSING_HMAC_KEY_MESSAGE : null, ">= 32 bytes");
			keys.add(PiiKey.of(key.kid(), encryption, hmac));
		}
		String activeKid = environment.getProperty(ACTIVE_KID_PROPERTY);
		if (isBlank(activeKid)) {
			if (!flatPairSet) {
				throw new IllegalStateException("'" + ACTIVE_KID_PROPERTY + "' is required when only "
						+ KEYS_PROPERTY + ".* entries are configured; set the SERFIRA_SECURITY_PII_ACTIVEKID "
						+ "environment variable");
			}
			activeKid = PiiKeyRing.SINGLE_KID;
		}
		String legacyKid = environment.getProperty(LEGACY_KID_PROPERTY);
		if (isBlank(legacyKid)) {
			legacyKid = flatPairSet ? PiiKeyRing.SINGLE_KID : null;
		}
		return new PiiKeyRing(keys, activeKid.trim(), legacyKid == null ? null : legacyKid.trim(),
				writeEnvelope(environment));
	}

	/** {@code v1} unless configured: a new deployment writes exactly what the previous version reads (ADR-025 D7). */
	private static PiiEnvelopeVersion writeEnvelope(Environment environment) {
		String value = environment.getProperty(WRITE_ENVELOPE_PROPERTY);
		if (isBlank(value)) {
			return PiiEnvelopeVersion.V1;
		}
		return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "v1" -> PiiEnvelopeVersion.V1;
			case "v2" -> PiiEnvelopeVersion.V2;
			default -> throw new IllegalStateException("'" + WRITE_ENVELOPE_PROPERTY
					+ "' must be v1 or v2; set the SERFIRA_SECURITY_PII_WRITEENVELOPE environment variable");
		};
	}

	private static byte[] decode(String value, String property, String environmentVariable, String missingMessage,
			String expected) {
		if (isBlank(value)) {
			throw new IllegalStateException(missingMessage != null ? missingMessage
					: "'" + property + "' is required (base64, " + expected + "); set the " + environmentVariable
							+ " environment variable");
		}
		try {
			return Base64.getDecoder().decode(value.trim());
		} catch (IllegalArgumentException e) {
			// The decoder's message can quote the offending character; the value never reaches the log.
			throw new IllegalStateException("'" + property + "' is not valid base64; set the " + environmentVariable
					+ " environment variable");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
