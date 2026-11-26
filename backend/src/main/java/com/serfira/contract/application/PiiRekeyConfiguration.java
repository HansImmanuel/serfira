package com.serfira.contract.application;

import com.serfira.contract.infrastructure.CustomerPiiRows;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.idempotency.IdempotencyKeyUsage;
import com.serfira.shared.job.application.JobRunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The PII re-keying runner (T28b, ADR-025), wired only when {@code serfira.maintenance.pii-rekey.enabled=true}
 * ({@code SERFIRA_MAINTENANCE_PIIREKEY_ENABLED}); otherwise none of these beans exists. Every class is declared here
 * rather than by a stereotype annotation, so component scanning can never create one without the switch.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "serfira.maintenance.pii-rekey", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PiiRekeyProperties.class)
public class PiiRekeyConfiguration {

	@Bean
	CustomerPiiRows customerPiiRows(JdbcClient jdbcClient) {
		return new CustomerPiiRows(jdbcClient);
	}

	@Bean
	CustomerRekeyPageWriter customerRekeyPageWriter(CustomerPiiRows customerPiiRows, AuditContext auditContext,
			Clock clock) {
		return new CustomerRekeyPageWriter(customerPiiRows, auditContext, clock);
	}

	@Bean
	PiiRekeyJob piiRekeyJob(CustomerPiiRows customerPiiRows, CustomerRekeyPageWriter customerRekeyPageWriter,
			IdempotencyKeyUsage idempotencyKeyUsage, JobRunService jobRunService, AuditContext auditContext,
			Clock clock, PiiRekeyProperties properties) {
		return new PiiRekeyJob(customerPiiRows, customerRekeyPageWriter, idempotencyKeyUsage, jobRunService,
				auditContext, clock, properties.batchSize());
	}

	@Bean
	LockedPiiRekeyJob lockedPiiRekeyJob(PiiRekeyJob piiRekeyJob) {
		return new LockedPiiRekeyJob(piiRekeyJob);
	}

	@Bean
	PiiRekeyStartupRunner piiRekeyStartupRunner(LockedPiiRekeyJob lockedPiiRekeyJob, PiiRekeyProperties properties) {
		return new PiiRekeyStartupRunner(lockedPiiRekeyJob, properties);
	}
}
