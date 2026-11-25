package com.serfira.shared.money;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the exact-decimal wire format (ADR-024): every {@link BigDecimal} leaves the API as a plain
 * decimal string, and numeric request bodies are still accepted on the way in.
 */
class BigDecimalStringModuleTest {

	private final JsonMapper mapper = JsonMapper.builder().addModule(new BigDecimalStringModule()).build();

	@Test
	void serializesMoneyAndRatesAsExactDecimalStrings() {
		String json = mapper.writeValueAsString(
				new Sample(new BigDecimal("99999999999999999.99"), new BigDecimal("0.0150")));

		assertThat(json).contains("\"amount\":\"99999999999999999.99\"");
		assertThat(json).contains("\"rate\":\"0.0150\"");
	}

	@Test
	void neverUsesScientificNotation() {
		String json = mapper.writeValueAsString(new Sample(new BigDecimal("1E+3"), new BigDecimal("1E-4")));

		assertThat(json).contains("\"amount\":\"1000\"");
		assertThat(json).contains("\"rate\":\"0.0001\"");
	}

	@Test
	void stillDeserializesNumericAndStringRequestBodies() {
		Sample numeric = mapper.readValue("{\"amount\":20000000.00,\"rate\":0.0150}", Sample.class);
		Sample text = mapper.readValue("{\"amount\":\"20000000.00\",\"rate\":\"0.0150\"}", Sample.class);

		assertThat(numeric.amount()).isEqualByComparingTo("20000000.00");
		assertThat(numeric.rate()).isEqualByComparingTo("0.0150");
		assertThat(text.amount()).isEqualByComparingTo("20000000.00");
		assertThat(text.rate()).isEqualByComparingTo("0.0150");
	}

	private record Sample(BigDecimal amount, BigDecimal rate) {
	}
}
