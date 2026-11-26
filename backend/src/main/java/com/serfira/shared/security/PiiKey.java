package com.serfira.shared.security;

import java.util.regex.Pattern;
import javax.crypto.spec.SecretKeySpec;

/**
 * One entry of the PII key ring (ADR-025): a key id ({@code kid}) with its AES-256-GCM encryption key and its
 * HMAC-SHA-256 lookup key. Rotation moves both together, so they live in one entry.
 *
 * <p>A {@code kid} is 1–16 lowercase letters or digits. Spring's environment-variable binding splits path segments
 * on {@code _} and lowercases map keys, so {@code -}, {@code _} and uppercase would not round-trip through
 * {@code SERFIRA_SECURITY_PII_KEYS_<KID>_…}, and {@code :} would break the {@code v2:<kid>:} envelope.
 * Validation messages name the {@code kid}, never key material.
 */
public record PiiKey(String kid, SecretKeySpec encryptionKey, SecretKeySpec hmacKey) {

	public static final Pattern KID_PATTERN = Pattern.compile("[a-z0-9]{1,16}");

	static final int AES_KEY_BYTES = 32;
	static final String HMAC_ALGORITHM = "HmacSHA256";

	/** Validates the {@code kid} syntax and both key lengths. */
	public static PiiKey of(String kid, byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
		requireValidKid(kid);
		if (encryptionKeyBytes == null || encryptionKeyBytes.length != AES_KEY_BYTES) {
			throw new IllegalStateException(
					"PII key '" + kid + "': AES-256-GCM key must be exactly " + AES_KEY_BYTES + " bytes");
		}
		if (hmacKeyBytes == null || hmacKeyBytes.length < AES_KEY_BYTES) {
			throw new IllegalStateException(
					"PII key '" + kid + "': HMAC-SHA-256 key must be at least " + AES_KEY_BYTES + " bytes");
		}
		return new PiiKey(kid, new SecretKeySpec(encryptionKeyBytes, "AES"),
				new SecretKeySpec(hmacKeyBytes, HMAC_ALGORITHM));
	}

	static void requireValidKid(String kid) {
		if (kid == null || !KID_PATTERN.matcher(kid).matches()) {
			throw new IllegalStateException("PII key id must be 1-16 lowercase letters or digits");
		}
	}

	static boolean isValidKid(String kid) {
		return kid != null && KID_PATTERN.matcher(kid).matches();
	}
}
