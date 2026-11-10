package com.serfira.shared.observability;

import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.DispatcherType;

/**
 * Wires the observability baseline (T20, Addendum §10): registers {@link CorrelationIdFilter} ahead of the
 * Spring Security filter chain so every request — including one that fails authentication or authorization —
 * is assigned a correlation id and carries it on the response and in the logs.
 *
 * <p>Structured JSON logging itself is enabled through {@code logging.structured.format.console} in
 * {@code application.properties}, using Spring Boot's native structured-logging support (no logback XML and
 * no extra dependency). This class only owns the servlet filter registration.
 *
 * <p>A {@link FilterRegistrationBean} with an explicit order, rather than a bare {@code Filter} bean, on
 * purpose: the order is honored both by the embedded container and by Spring Boot's MockMvc test support,
 * which collects registration beans through {@code ServletContextInitializerBeans} and sorts them with the
 * {@code AnnotationAwareOrderComparator}. A bare {@code Filter} bean would be adapted at default precedence
 * in MockMvc and would run <em>after</em> the security chain, so an authentication failure would lose its
 * correlation id.
 */
@Configuration
public class ObservabilityConfiguration {

	/**
	 * Registers the correlation-id filter with an order below Spring Security's chain
	 * ({@link SecurityFilterProperties#DEFAULT_FILTER_ORDER} = -100), so the id is bound before security runs.
	 */
	@Bean
	FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
		FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
		registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10);
		registration.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ERROR);
		return registration;
	}
}
