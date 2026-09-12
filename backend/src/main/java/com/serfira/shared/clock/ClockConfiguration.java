package com.serfira.shared.clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application {@link Clock} bean. Tests can replace it with a {@link FixedClock} by defining
 * their own {@code Clock} bean (the default is registered with {@code @ConditionalOnMissingBean}).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

	@Bean
	@ConditionalOnMissingBean(Clock.class)
	Clock clock() {
		return new SystemClock();
	}
}