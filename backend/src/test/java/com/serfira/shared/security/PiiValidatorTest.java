package com.serfira.shared.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PII format validation (review H-2): V2 widened {@code nik}/{@code phone} to {@code TEXT},
 * so length/format integrity is enforced here before persistence or hashing.
 */
class PiiValidatorTest {

	@Test
	void validNikIsNormalizedToSixteenDigits() {
		assertThat(PiiValidator.requireValidNik("3171-0125-0190-0001")).isEqualTo("3171012501900001");
		assertThat(PiiValidator.requireValidNik(" 3171 0125 0190 0001 ")).isEqualTo("3171012501900001");
	}

	@Test
	void nikWithWrongDigitCountIsRejected() {
		assertThatThrownBy(() -> PiiValidator.requireValidNik("317101250190001"))
				.isInstanceOf(InvalidPiiDataException.class)
				.hasMessageContaining("16 digits");
		assertThatThrownBy(() -> PiiValidator.requireValidNik("31710125019000012"))
				.isInstanceOf(InvalidPiiDataException.class);
		// Letters only: nothing left after digit stripping.
		assertThatThrownBy(() -> PiiValidator.requireValidNik("not-a-nik"))
				.isInstanceOf(InvalidPiiDataException.class);
	}

	@Test
	void absentNikIsRejected() {
		assertThatThrownBy(() -> PiiValidator.requireValidNik(null))
				.isInstanceOf(InvalidPiiDataException.class)
				.hasMessageContaining("required");
		assertThatThrownBy(() -> PiiValidator.requireValidNik("   "))
				.isInstanceOf(InvalidPiiDataException.class);
	}

	@Test
	void validPhoneIsNormalizedWithTrunkConversion() {
		assertThat(PiiValidator.requireValidPhone("08123456789")).isEqualTo("628123456789");
		assertThat(PiiValidator.requireValidPhone("+62 812-3456-789")).isEqualTo("628123456789");
	}

	@Test
	void phoneWithWrongDigitCountIsRejected() {
		// 8 digits after normalization (6281 2345) → too short.
		assertThatThrownBy(() -> PiiValidator.requireValidPhone("0812345"))
				.isInstanceOf(InvalidPiiDataException.class)
				.hasMessageContaining("9–15 digits");
		// 16 digits after normalization → beyond E.164 maximum.
		assertThatThrownBy(() -> PiiValidator.requireValidPhone("6281234567890123"))
				.isInstanceOf(InvalidPiiDataException.class);
		// Garbage hashes nothing — it is rejected with a clear reason instead.
		assertThatThrownBy(() -> PiiValidator.requireValidPhone("abc"))
				.isInstanceOf(InvalidPiiDataException.class);
	}

	@Test
	void absentPhoneIsRejected() {
		assertThatThrownBy(() -> PiiValidator.requireValidPhone(null))
				.isInstanceOf(InvalidPiiDataException.class)
				.hasMessageContaining("required");
		assertThatThrownBy(() -> PiiValidator.requireValidPhone(""))
				.isInstanceOf(InvalidPiiDataException.class);
	}

	@Test
	void rejectionCarriesTypedValidationCodeForTheStandardEnvelope() {
		assertThatThrownBy(() -> PiiValidator.requireValidNik("123"))
				.isInstanceOfSatisfying(InvalidPiiDataException.class, ex -> {
					assertThat(ex.status().value()).isEqualTo(400);
					assertThat(ex.code().name()).isEqualTo("VALIDATION_ERROR");
				});
	}
}
