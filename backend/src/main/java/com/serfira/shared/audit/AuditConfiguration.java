package com.serfira.shared.audit;

import com.serfira.shared.clock.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the {@link AuditContext} bean and binds the injectable {@code Clock} / audit context into
 * {@link AuditSupport} for JPA lifecycle callbacks.
 */
@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

	@Bean
	AuditContext auditContext(Clock clock) {
		AuditContext context = new AuditContext();
		AuditSupport.configure(clock, context);
		return context;
	}
}