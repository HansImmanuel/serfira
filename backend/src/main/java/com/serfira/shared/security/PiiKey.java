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
 *
 * <p>Every construction path validates, the canonical constructor included, so no ring can hold an entry that
 * would write an unreadable envelope. {@link #toString()} prints only the {@code kid}: the generated record form
 * would print {@code SecretKeySpec@<hashCode>}, and that hash is computed from the key bytes.
 */
public record PiiKey(String kid, SecretKeySpec encryptionKey, SecretKeySpec hmacKey) {

	public static final Pattern KID_PATTERN = Pattern.compile("[a-z0-9]{1,16}");

	static final int AES_KEY_BYTES = 32;
	static final String HMAC_ALGORITHM = "HmacSHA256";

	/** Validates the {@code kid} syntax and both key lengths, however the entry is built. */
	public PiiKey {
		requireValidKid(kid);
		if (encryptionKey == null) {
			throw aesLength(kid);
		}
		if (hmacKey == null) {
			throw hmacLength(kid);
		}
		requireLengths(kid, encryptionKey.getEncoded(), hmacKey.getEncoded());
	}

	/** Builds an entry from raw key bytes; validates the {@code kid} syntax and both key lengths. */
	public static PiiKey of(String kid, byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
		requireValidKid(kid);
		requireLengths(kid, encryptionKeyBytes, hmacKeyBytes);
		return new PiiKey(kid, new SecretKeySpec(encryptionKeyBytes, "AES"),
				new SecretKeySpec(hmacKeyBytes, HMAC_ALGORITHM));
	}

	/** Only the {@code kid}: never key material, nor a value computed from it. */
	@Override
	public String toString() {
		return "PiiKey[kid=" + kid + "]";
	}

	static void requireValidKid(String kid) {
		if (!isValidKid(kid)) {
			throw new IllegalStateException("PII key id must be 1-16 lowercase letters or digits");
		}
	}

	static boolean isValidKid(String kid) {
		return kid != null && KID_PATTERN.matcher(kid).matches();
	}

	private static void requireLengths(String kid, byte[] encryptionKeyBytes, byte[] hmacKeyBytes) {
		if (encryptionKeyBytes == null || encryptionKeyBytes.length != AES_KEY_BYTES) {
			throw aesLength(kid);
		}
		if (hmacKeyBytes == null || hmacKeyBytes.length < AES_KEY_BYTES) {
			throw hmacLength(kid);
		}
	}

	private static IllegalStateException aesLength(String kid) {
		return new IllegalStateException(
				"PII key '" + kid + "': AES-256-GCM key must be exactly " + AES_KEY_BYTES + " bytes");
	}

	private static IllegalStateException hmacLength(String kid) {
		return new IllegalStateException(
				"PII key '" + kid + "': HMAC-SHA-256 key must be at least " + AES_KEY_BYTES + " bytes");
	}
}
