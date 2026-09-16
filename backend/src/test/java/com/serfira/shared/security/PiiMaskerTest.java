package com.serfira.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII display masking (ADR-004 minimization / review H-3): projections carry masked values only.
 */
class PiiMaskerTest {

	@Test
	void nikKeepsFirstAndLastFourDigits() {
		assertThat(PiiMasker.maskNik("3171012501900001")).isEqualTo("3171********0001");
	}

	@Test
	void nikMaskingIsFormAgnostic() {
		// Separators are stripped before masking, so every human spelling masks identically.
		assertThat(PiiMasker.maskNik("3171-0125-0190-0001")).isEqualTo("3171********0001");
		assertThat(PiiMasker.maskNik("3171 0125 0190 0001")).isEqualTo("3171********0001");
	}

	@Test
	void phoneKeepsFirstFourAndLastThreeDigitsOnNormalizedForm() {
		assertThat(PiiMasker.maskPhone("08123456789")).isEqualTo("6281*****789");
		assertThat(PiiMasker.maskPhone("+62 812-3456-789")).isEqualTo("6281*****789");
	}

	@Test
	void unusualLengthsAreMaskedConservatively() {
		// Not a valid phone length: generic head/tail masking instead of trusting the value.
		assertThat(PiiMasker.maskNik("12345")).isEqualTo("12*45");
		assertThat(PiiMasker.maskPhone("12345678")).isEqualTo("1******8");
		// Too short to show head+tail: fully masked.
		assertThat(PiiMasker.maskNik("123")).isEqualTo("***");
	}

	@Test
	void nullAndBlankMaskToNull() {
		assertThat(PiiMasker.maskNik(null)).isNull();
		assertThat(PiiMasker.maskNik("   ")).isNull();
		assertThat(PiiMasker.maskPhone(null)).isNull();
		assertThat(PiiMasker.maskPhone("")).isNull();
	}
}
