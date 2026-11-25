package com.serfira.shared.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.Iterator;

/**
 * Keeps the generated OpenAPI document in step with the wire format (ADR-024).
 *
 * <p>Every {@link BigDecimal} is serialized as an exact decimal string, but swagger-core inspects the
 * Java type and would describe it as a JSON number. This converter runs before swagger's own resolver
 * (springdoc prepends registered converters) and declares the string type instead, so generated clients
 * and the Compose smoke check see the same contract the server actually emits.
 */
@Configuration
public class OpenApiConfiguration {

	@Bean
	ModelConverter bigDecimalAsStringModelConverter() {
		return new ModelConverter() {
			@Override
			public Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
				if (isBigDecimal(type.getType())) {
					return new StringSchema()
							.format("decimal")
							.description("Exact decimal string; money has scale 2, rates scale 4 (ADR-024).");
				}
				return chain.hasNext() ? chain.next().resolve(type, context, chain) : null;
			}
		};
	}

	private static boolean isBigDecimal(Type type) {
		if (type == null) {
			return false;
		}
		// swagger-core hands over a Jackson 2 JavaType for record components and a Class elsewhere;
		// its own mapper normalizes both to a raw class.
		return BigDecimal.class.equals(Json31.mapper().constructType(type).getRawClass());
	}
}
