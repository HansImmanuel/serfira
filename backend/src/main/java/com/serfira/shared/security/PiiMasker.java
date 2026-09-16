package com.serfira.shared.security;

/**
 * Deterministic display masking for PII (ADR-004 policy of data minimization).
 *
 * <p>Projections that cross the API boundary carry masked values only; the in-memory plaintext
 * stays inside the application layer. A role-based full-value variant is deferred to the RBAC
 * story (Sprint 6b, Addendum §9) and must be added as an explicit, separate projection.
 */
public final class PiiMasker {

	private PiiMasker() {
	}

	/**
	 * Masks a NIK for display: {@code 3171012501900001} → {@code 3171********0001}
	 * (first 4 and last 4 digits visible). Values that are not 16 digits after
	 * normalization are masked conservatively instead of trusted.
	 */
	public static String maskNik(String rawNik) {
		if (isAbsent(rawNik)) {
			return null;
		}
		String digits = PiiHasher.normalizeNik(rawNik);
		if (digits.isEmpty()) {
			return "********";
		}
		if (digits.length() == 16) {
			return digits.substring(0, 4) + "********" + digits.substring(12);
		}
		return genericMask(digits, 2, 2);
	}

	/**
	 * Masks a phone for display: {@code 628123456789} → {@code 6281*****789}
	 * (first 4 and last 3 digits visible, on the normalized form). Short values are
	 * masked conservatively instead of trusted.
	 */
	public static String maskPhone(String rawPhone) {
		if (isAbsent(rawPhone)) {
			return null;
		}
		String digits = PiiHasher.normalizePhone(rawPhone);
		if (digits.isEmpty()) {
			return "****";
		}
		if (digits.length() >= 9) {
			return digits.substring(0, 4) + "*".repeat(digits.length() - 7) + digits.substring(digits.length() - 3);
		}
		return genericMask(digits, 1, 1);
	}

	private static String genericMask(String digits, int visibleHead, int visibleTail) {
		int length = digits.length();
		if (length <= visibleHead + visibleTail) {
			return "*".repeat(length);
		}
		return digits.substring(0, visibleHead)
				+ "*".repeat(length - visibleHead - visibleTail)
				+ digits.substring(length - visibleTail);
	}

	private static boolean isAbsent(String rawValue) {
		return rawValue == null || rawValue.isBlank();
	}
}
