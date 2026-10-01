package com.serfira.shared.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import com.serfira.shared.error.BadRequestException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the request decimal-magnitude guard (ADR-016, CWE-400). The point is that a compact
 * scientific-notation value is rejected <i>cheaply</i>, before any rescale would expand it, so each hostile
 * case here must return fast rather than hang — if the guard ever regressed to expanding the value, the test
 * would stall instead of asserting.
 */
class DecimalBoundsTest {

	@Test
	void acceptsValuesInsideTheMoneyDomain() {
		assertThatCode(() -> {
			DecimalBounds.requireMoneyDomain(new BigDecimal("0.00"), "amount");
			DecimalBounds.requireMoneyDomain(new BigDecimal("16000000.00"), "amount");
			DecimalBounds.requireMoneyDomain(new BigDecimal("16000000"), "amount");
			// 17 integer digits + 2 fractional is the NUMERIC(19,2) maximum.
			DecimalBounds.requireMoneyDomain(new BigDecimal("99999999999999999.99"), "amount");
			DecimalBounds.requireMoneyDomain(null, "amount");
		}).doesNotThrowAnyException();
	}

	@Test
	void rejectsMoneyFinerThanScaleTwo() {
		assertThatThrownBy(() -> DecimalBounds.requireMoneyDomain(new BigDecimal("1.001"), "amount"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("at most 2 decimal places");
	}

	@Test
	void rejectsMoneyWithTooManyIntegerDigits() {
		// 18 integer digits: one past the NUMERIC(19,2) ceiling.
		assertThatThrownBy(() -> DecimalBounds.requireMoneyDomain(new BigDecimal("100000000000000000.00"), "amount"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("out of range");
	}

	@Test
	void rejectsCompactHugeMoneyMagnitudeWithoutExpandingIt() {
		// 1e100000000 parses to unscaled 1 with scale -100000000, so a scale() <= 2 check alone would pass it
		// and setScale would then build a ~100M-digit integer. The guard must reject it from metadata only.
		assertThatThrownBy(() -> DecimalBounds.requireMoneyDomain(new BigDecimal("1e100000000"), "amount"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("out of range");
	}

	@Test
	void acceptsValuesInsideTheRateDomain() {
		assertThatCode(() -> {
			DecimalBounds.requireRateDomain(new BigDecimal("0.0000"), "interest_rate");
			DecimalBounds.requireRateDomain(new BigDecimal("0.0150"), "interest_rate");
			DecimalBounds.requireRateDomain(new BigDecimal("0.015"), "interest_rate");
			DecimalBounds.requireRateDomain(new BigDecimal("1.0000"), "interest_rate");
			DecimalBounds.requireRateDomain(null, "interest_rate");
		}).doesNotThrowAnyException();
	}

	@Test
	void rejectsRateFinerThanScaleFour() {
		assertThatThrownBy(() -> DecimalBounds.requireRateDomain(new BigDecimal("0.00001"), "interest_rate"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("at most 4 decimal places");
	}

	@Test
	void rejectsCompactTinyRateScaleWithoutExpandingIt() {
		// 1e-100000000 is inside [0,1] but has scale 100000000; setScale(4) would build a huge power-of-ten
		// divisor. The scale check rejects it first from metadata only.
		assertThatThrownBy(() -> DecimalBounds.requireRateDomain(new BigDecimal("1e-100000000"), "interest_rate"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("at most 4 decimal places");
	}

	@Test
	void rejectsRateWithTooManyIntegerDigits() {
		// 4 integer digits: past the NUMERIC(7,4) ceiling of 3, even though it is a valid scale.
		assertThatThrownBy(() -> DecimalBounds.requireRateDomain(new BigDecimal("1000.0000"), "interest_rate"))
				.isInstanceOf(BadRequestException.class)
				.hasMessageContaining("out of range");
	}
}
