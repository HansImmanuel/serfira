package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.idempotency.IdempotencyKeyUsage;
import com.serfira.shared.job.application.JobRunService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * T28b Decision 6: the re-keying runner exists only when {@code serfira.maintenance.pii-rekey.enabled=true}, runs a
 * dry run unless told otherwise, and binds from the environment-variable form an operator uses.
 */
class PiiRekeyConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(PiiRekeyConfiguration.class)
			.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
			.withBean(IdempotencyKeyUsage.class, () -> mock(IdempotencyKeyUsage.class))
			.withBean(JobRunService.class, () -> mock(JobRunService.class))
			.withBean(AuditContext.class, () -> mock(AuditContext.class))
			.withBean(Clock.class, () -> mock(Clock.class));

	@Test
	void noBeanExistsWithoutTheProperty() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(PiiRekeyJob.class);
			assertThat(context).doesNotHaveBean(PiiRekeyStartupRunner.class);
		});
	}

	@Test
	void theModeDefaultsToDryRun() {
		runner.withPropertyValues("serfira.maintenance.pii-rekey.enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(PiiRekeyStartupRunner.class);
					PiiRekeyProperties properties = context.getBean(PiiRekeyProperties.class);
					assertThat(properties.mode()).isEqualTo(PiiRekeyMode.DRY_RUN);
					assertThat(properties.batchSize()).isEqualTo(500);
				});
	}

	@Test
	void theRunnerBindsFromEnvironmentVariables() {
		runner.withInitializer(context -> context.getEnvironment().getPropertySources().replace(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
								Map.of("SERFIRA_MAINTENANCE_PIIREKEY_ENABLED", "true",
										"SERFIRA_MAINTENANCE_PIIREKEY_MODE", "EXECUTE",
										"SERFIRA_MAINTENANCE_PIIREKEY_BATCHSIZE", "2"))))
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(PiiRekeyStartupRunner.class);
					PiiRekeyProperties properties = context.getBean(PiiRekeyProperties.class);
					assertThat(properties.mode()).isEqualTo(PiiRekeyMode.EXECUTE);
					assertThat(properties.batchSize()).isEqualTo(2);
				});
	}

	@Test
	void anUnknownModeFailsStartup() {
		runner.withPropertyValues("serfira.maintenance.pii-rekey.enabled=true",
						"serfira.maintenance.pii-rekey.mode=EXECUTED")
				.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void aBatchSizeBelowOneFailsStartup() {
		runner.withPropertyValues("serfira.maintenance.pii-rekey.enabled=true",
						"serfira.maintenance.pii-rekey.batch-size=0")
				.run(context -> assertThat(context).hasFailed());
	}
}
