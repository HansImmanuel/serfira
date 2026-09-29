package com.serfira.shared.job.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Scheduling and renewable, database-clock ShedLock configuration shared by in-process jobs. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class JobSchedulingConfiguration {

	@Bean(destroyMethod = "shutdown")
	ScheduledExecutorService shedLockKeepAliveExecutor() {
		return Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "shedlock-keep-alive");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Bean
	LockProvider lockProvider(DataSource dataSource, ScheduledExecutorService shedLockKeepAliveExecutor) {
		JdbcTemplateLockProvider jdbcProvider = new JdbcTemplateLockProvider(
				JdbcTemplateLockProvider.Configuration.builder()
						.withJdbcTemplate(new JdbcTemplate(dataSource))
						.usingDbTime()
						.build());
		return new KeepAliveLockProvider(jdbcProvider, shedLockKeepAliveExecutor);
	}
}
