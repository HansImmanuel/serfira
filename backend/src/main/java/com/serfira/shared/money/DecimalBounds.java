package com.serfira.shared.money;

import java.math.BigDecimal;

import com.serfira.shared.error.BadRequestException;

/**
 * Cheap magnitude guard for request-supplied {@code BigDecimal}s, run before any rescaling (ADR-016,
 * review DoS findings, CWE-400).
 *
 * <p>A caller can encode an enormous number in a few characters with scientific notation: {@code 1e100000000}
 * is eleven characters but denotes a hundred-million-digit integer, and {@code 1e-100000000} denotes a value
 * with a hundred-million-digit scale. {@code setScale(..)} / {@code toPlainString()} on either materializes
 * that representation, burning shared JVM CPU and heap before ordinary range validation runs. A request-size
 * limit cannot contain amplification this compact.
 *
 * <p>The guard therefore rejects anything outside the supported SQL numeric domain using only
 * {@link BigDecimal#precision()} and {@link BigDecimal#scale()} — both read the unscaled value's metadata and
 * never expand it. The number of integer digits is {@code precision() - scale()} (which stays correct for the
 * negative scales that scientific notation produces), so the whole check is constant-time.
 *
 * <p>Money is {@code NUMERIC(19,2)} and rates are {@code NUMERIC(7,4)} (TS §2.1, DM §1.3). This guard enforces
 * the <i>magnitude</i> of that domain only; aggregate business rules (positivity, {@code 0 <= dp < price},
 * rate in {@code [0,1]}) stay where they are and run afterwards on an already-bounded value.
 */
public final class DecimalBounds {

	/** {@code NUMERIC(19,2)}: 19 significant digits, 2 fractional, so at most 17 integer digits. */
	private static final int MONEY_PRECISION = 19;
	private static final int MONEY_SCALE = 2;

	/** {@code NUMERIC(7,4)}: 7 significant digits, 4 fractional, so at most 3 integer digits. */
	private static final int RATE_PRECISION = 7;
	private static final int RATE_SCALE = 4;

	private DecimalBounds() {
	}

	/**
	 * Rejects a money value that cannot fit {@code NUMERIC(19,2)} by magnitude or scale, cheaply and before
	 * any rescaling. Does not check sign or any aggregate rule — the caller still validates those.
	 *
	 * @throws BadRequestException if {@code value} has more than 2 fractional digits or more than 17 integer
	 *                             digits
	 */
	public static void requireMoneyDomain(BigDecimal value, String fieldName) {
		requireDomain(value, fieldName, MONEY_PRECISION, MONEY_SCALE);
	}

	/**
	 * Rejects a rate value that cannot fit {@code NUMERIC(7,4)} by magnitude or scale, cheaply and before any
	 * rescaling. Does not check the {@code [0,1]} range — the caller still validates that.
	 *
	 * @throws BadRequestException if {@code value} has more than 4 fractional digits or more than 3 integer
	 *                             digits
	 */
	public static void requireRateDomain(BigDecimal value, String fieldName) {
		requireDomain(value, fieldName, RATE_PRECISION, RATE_SCALE);
	}

	private static void requireDomain(BigDecimal value, String fieldName, int maxPrecision, int maxScale) {
		if (value == null) {
			return;
		}
		if (value.scale() > maxScale) {
			throw new BadRequestException(fieldName + " must have at most " + maxScale + " decimal places");
		}
		// precision() - scale() is the count of integer digits and holds for negative scales too; it reads
		// only the unscaled value's digit count, so it never expands a scientific-notation magnitude.
		int integerDigits = value.precision() - value.scale();
		if (integerDigits > maxPrecision - maxScale) {
			throw new BadRequestException(
					fieldName + " is out of range for NUMERIC(" + maxPrecision + "," + maxScale + ")");
		}
	}
}
