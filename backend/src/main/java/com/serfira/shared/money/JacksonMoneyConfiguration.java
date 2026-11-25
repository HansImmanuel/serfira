package com.serfira.shared.money;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.JacksonModule;

/**
 * Registers the exact-decimal wire format with the application's Jackson 3 mapper (ADR-024).
 *
 * <p>Spring Boot auto-configuration collects every {@link JacksonModule} bean into the shared
 * {@code ObjectMapper}, so this covers controllers, the idempotency replay store and anything else
 * that serializes with the application mapper.
 */
@Configuration
public class JacksonMoneyConfiguration {

	@Bean
	JacksonModule bigDecimalStringModule() {
		return new BigDecimalStringModule();
	}
}
