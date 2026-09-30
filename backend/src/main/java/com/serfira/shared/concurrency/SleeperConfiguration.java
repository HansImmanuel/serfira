package com.serfira.shared.concurrency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the application {@link Sleeper} bean. Tests can replace it with a fake by defining their own
 * {@code Sleeper} bean (the default is registered with {@code @ConditionalOnMissingBean}), mirroring
 * {@code ClockConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
public class SleeperConfiguration {

	@Bean
	@ConditionalOnMissingBean(Sleeper.class)
	Sleeper sleeper() {
		return Sleeper.systemSleeper();
	}
}
