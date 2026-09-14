package com.serfira.shared.security;

import java.util.Objects;

/**
 * Deterministic, keyed lookup hashes for searchable PII fields (ADR-004, Addendum §9).
 *
 * <p>These are the ONLY values used for equality/uniqueness/indexing — ciphertext is randomized per
 * write and must never be used that way. Normalization is applied before hashing so that equivalent
 * human spellings map to the same lookup value (NIK separators, phone trunk codes).
 */
public final class PiiHasher {

	private PiiHasher() {
	}

	/**
	 * Lookup hash of a NIK, normalized to digits first. Equals the stored {@code nik_hash}.
	 */
	public static String hashNik(String rawNik) {
		return PiiSecuritySupport.hmacHex(normalizeNik(rawNik));
	}

	/**
	 * Lookup hash of a phone, normalized to digits with the Indonesian trunk code (leading {@code 0}
	 * → {@code 62}). Equals the stored {@code phone_lookup}.
	 */
	public static String hashPhone(String rawPhone) {
		return PiiSecuritySupport.hmacHex(normalizePhone(rawPhone));
	}

	/** Strips everything that is not a digit ({@code 3171-0125-0190-0001} → {@code 3171012501900001}). */
	public static String normalizeNik(String rawNik) {
		Objects.requireNonNull(rawNik, "rawNik");
		return rawNik.replaceAll("\\D", "");
	}

	/**
	 * Strips everything that is not a digit and converts a leading {@code 0} local trunk to the
	 * international {@code 62} so {@code 0812-3456-789} ≡ {@code +628123456789}.
	 */
	public static String normalizePhone(String rawPhone) {
		Objects.requireNonNull(rawPhone, "rawPhone");
		String digits = rawPhone.replaceAll("\\D", "");
		if (digits.startsWith("0")) {
			digits = "62" + digits.substring(1);
		}
		return digits;
	}
}