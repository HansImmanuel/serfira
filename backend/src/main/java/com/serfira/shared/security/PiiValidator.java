package com.serfira.shared.security;

/**
 * Format validation for raw PII identifiers, applied before any persistence or hashing
 * (review H-2: V2 widened {@code nik}/{@code phone} to {@code TEXT}, so the database no
 * longer constrains length — identity integrity must be enforced at the application boundary).
 *
 * <p>Rules (Addendum §9 normalization):
 * <ul>
 *   <li>NIK — exactly 16 digits after separator stripping,</li>
 *   <li>phone — 9–15 digits after separator stripping and trunk-code conversion.</li>
 * </ul>
 *
 * <p>Callers pass the raw human-entered value; the returned value is the normalized form
 * that matches the lookup-hash input, so a validated value can be hashed and stored without
 * re-normalizing.
 */
public final class PiiValidator {

	private static final int NIK_LENGTH = 16;
	private static final int MIN_PHONE_LENGTH = 9;
	private static final int MAX_PHONE_LENGTH = 15;

	private PiiValidator() {
	}

	/**
	 * Validates a raw NIK and returns its normalized 16-digit form.
	 *
	 * @throws InvalidPiiDataException if the value is absent or not exactly 16 digits
	 */
	public static String requireValidNik(String rawNik) {
		if (rawNik == null || rawNik.isBlank()) {
			throw new InvalidPiiDataException("NIK is required");
		}
		String normalized = PiiHasher.normalizeNik(rawNik);
		if (normalized.length() != NIK_LENGTH) {
			throw new InvalidPiiDataException("NIK must contain exactly " + NIK_LENGTH + " digits");
		}
		return normalized;
	}

	/**
	 * Validates a raw phone number and returns its normalized digit form
	 * (leading {@code 0} trunk converted to {@code 62}).
	 *
	 * @throws InvalidPiiDataException if the value is absent or not 9–15 digits
	 */
	public static String requireValidPhone(String rawPhone) {
		if (rawPhone == null || rawPhone.isBlank()) {
			throw new InvalidPiiDataException("Phone number is required");
		}
		String normalized = PiiHasher.normalizePhone(rawPhone);
		if (normalized.length() < MIN_PHONE_LENGTH || normalized.length() > MAX_PHONE_LENGTH) {
			throw new InvalidPiiDataException(
					"Phone number must contain " + MIN_PHONE_LENGTH + "–" + MAX_PHONE_LENGTH + " digits");
		}
		return normalized;
	}
}
