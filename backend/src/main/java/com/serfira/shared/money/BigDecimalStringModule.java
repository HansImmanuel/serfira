package com.serfira.shared.money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.math.BigDecimal;

/**
 * Serializes every {@link BigDecimal} as an exact decimal string (ADR-024, TS §2.2).
 *
 * <p>A JSON number is read by JavaScript as an IEEE-754 double. `NUMERIC(19,2)` allows 17 integer
 * digits, far beyond the 15–16 significant digits a double can hold, so a money value such as
 * {@code 99999999999999999.99} would reach the browser rounded. The wire format is therefore the
 * exact decimal text the database holds ({@code "20000000.00"}); clients parse it as a decimal
 * string instead of a float.
 *
 * <p>Only serialization changes: deserialization still accepts both JSON numbers and strings, so
 * existing callers that send numeric request bodies keep working.
 */
public final class BigDecimalStringModule extends SimpleModule {

	private static final long serialVersionUID = 1L;

	public BigDecimalStringModule() {
		super("serfira-bigdecimal-plain-string");
		addSerializer(BigDecimal.class, new PlainStringSerializer());
	}

	private static final class PlainStringSerializer extends ValueSerializer<BigDecimal> {

		@Override
		public void serialize(BigDecimal value, JsonGenerator generator, SerializationContext context) {
			// toPlainString() never uses scientific notation and preserves the stored scale.
			generator.writeString(value.toPlainString());
		}
	}
}
