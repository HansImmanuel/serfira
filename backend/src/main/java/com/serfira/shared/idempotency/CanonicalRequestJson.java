package com.serfira.shared.idempotency;

import com.serfira.shared.money.DecimalBounds;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds the canonical JSON retained for idempotency comparison.
 *
 * <p>Canonicalization rules (so that semantically identical retries produce the same fingerprint):
 * <ul>
 *   <li>field order is the caller's insertion order — callers use {@link #fields()} and add fields in
 *       a fixed order, never a hash-ordered map;</li>
 *   <li>money is normalized to scale 2 and rates to scale 4 with {@link RoundingMode#HALF_EVEN},
 *       matching the documented scale of the DB columns, so {@code 16000000} and
 *       {@code 16000000.00} are the same request;</li>
 *   <li>dates use ISO-8601, strings are trimmed and blank strings collapse to {@code null}.</li>
 * </ul>
 *
 * <p>The canonical JSON is transient: only its keyed digest ({@link RequestFingerprint}) is stored.
 */
public final class CanonicalRequestJson {

	private CanonicalRequestJson() {
	}

	/** Ordered, caller-controlled field map. */
	public static Map<String, String> fields() {
		return new LinkedHashMap<>();
	}

	/** Trimmed value, or {@code null} when absent/blank. */
	public static String text(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/**
	 * Money as a scale-2 plain string ({@code null}-safe). Bounds magnitude before {@code setScale} so no
	 * caller can expand a compact scientific-notation value through this formatter (ADR-016, CWE-400); every
	 * service boundary already checks this, and this is the backstop.
	 */
	public static String money(BigDecimal value) {
		if (value == null) {
			return null;
		}
		DecimalBounds.requireMoneyDomain(value, "money");
		return value.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
	}

	/**
	 * Rate as a scale-4 plain string ({@code null}-safe). Bounds magnitude before {@code setScale} for the
	 * same reason as {@link #money(BigDecimal)}: a tiny value such as {@code 1e-100000000} is in {@code [0,1]}
	 * but would make rounding construct an enormous power-of-ten divisor (ADR-016, CWE-400).
	 */
	public static String rate(BigDecimal value) {
		if (value == null) {
			return null;
		}
		DecimalBounds.requireRateDomain(value, "rate");
		return value.setScale(4, RoundingMode.HALF_EVEN).toPlainString();
	}

	/** ISO-8601 date ({@code null}-safe). */
	public static String date(LocalDate value) {
		return value == null ? null : value.toString();
	}

	/** {@code enum} constant name ({@code null}-safe); enums are canonicalized by name, never ordinal. */
	public static String name(Enum<?> value) {
		return value == null ? null : value.name();
	}

	/**
	 * Renders the field map as deterministic JSON. Implemented without a JSON library so the output
	 * does not depend on a serializer's naming strategy or feature configuration.
	 */
	public static String render(Map<String, String> fields) {
		Objects.requireNonNull(fields, "fields");
		StringBuilder json = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, String> field : fields.entrySet()) {
			if (!first) {
				json.append(',');
			}
			first = false;
			json.append('"').append(escape(field.getKey())).append("\":");
			if (field.getValue() == null) {
				json.append("null");
			} else {
				json.append('"').append(escape(field.getValue())).append('"');
			}
		}
		return json.append('}').toString();
	}

	private static String escape(String value) {
		StringBuilder escaped = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> escaped.append("\\\"");
				case '\\' -> escaped.append("\\\\");
				case '\n' -> escaped.append("\\n");
				case '\r' -> escaped.append("\\r");
				case '\t' -> escaped.append("\\t");
				default -> {
					if (c < 0x20) {
						escaped.append(String.format("\\u%04x", (int) c));
					} else {
						escaped.append(c);
					}
				}
			}
		}
		return escaped.toString();
	}
}