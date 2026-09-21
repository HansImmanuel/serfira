package com.serfira.shared.idempotency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the idempotency canonicalization (TS §2.5 refinement, ADR-007): a retry that is
 * semantically identical must hash identically, while a different request must not.
 */
class CanonicalRequestJsonTest {

	@Test
	void moneyIsNormalizedToColumnScaleSoScaleVariantsMatch() {
		assertThat(CanonicalRequestJson.money(new BigDecimal("16000000")))
				.isEqualTo(CanonicalRequestJson.money(new BigDecimal("16000000.00")))
				.isEqualTo("16000000.00");
		assertThat(CanonicalRequestJson.money(new BigDecimal("0"))).isEqualTo("0.00");
		assertThat(CanonicalRequestJson.money(null)).isNull();
	}

	@Test
	void rateIsNormalizedToColumnScaleSoScaleVariantsMatch() {
		assertThat(CanonicalRequestJson.rate(new BigDecimal("0.015")))
				.isEqualTo(CanonicalRequestJson.rate(new BigDecimal("0.0150")))
				.isEqualTo("0.0150");
		assertThat(CanonicalRequestJson.rate(null)).isNull();
	}

	@Test
	void textIsTrimmedAndBlankCollapsesToNull() {
		assertThat(CanonicalRequestJson.text("  Budi  ")).isEqualTo("Budi");
		assertThat(CanonicalRequestJson.text("   ")).isNull();
		assertThat(CanonicalRequestJson.text("")).isNull();
		assertThat(CanonicalRequestJson.text(null)).isNull();
	}

	@Test
	void enumIsCanonicalizedByNameAndDateAsIso() {
		assertThat(CanonicalRequestJson.name(com.serfira.contract.domain.InterestScheme.FLAT)).isEqualTo("FLAT");
		assertThat(CanonicalRequestJson.name(null)).isNull();
		assertThat(CanonicalRequestJson.date(LocalDate.of(2026, 1, 31))).isEqualTo("2026-01-31");
		assertThat(CanonicalRequestJson.date(null)).isNull();
	}

	@Test
	void renderPreservesCallerFieldOrderAndEmitsNullLiterals() {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("bb", "2");
		fields.put("aa", null);

		assertThat(CanonicalRequestJson.render(fields)).isEqualTo("{\"bb\":\"2\",\"aa\":null}");
	}

	@Test
	void renderEscapesJsonSignificantCharacters() {
		Map<String, String> fields = CanonicalRequestJson.fields();
		fields.put("name", "a\"b\\c\nd\te\u0001");

		assertThat(CanonicalRequestJson.render(fields))
				.isEqualTo("{\"name\":\"a\\\"b\\\\c\\nd\\te\\u0001\"}");
	}
}