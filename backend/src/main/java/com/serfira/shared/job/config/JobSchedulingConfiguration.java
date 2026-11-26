package com.serfira.shared.job.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Scheduling and renewable, database-clock ShedLock configuration shared by in-process jobs. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class JobSchedulingConfiguration {

	/** One thread per scheduled job: daily servicing, idempotency cleanup and the outbox publisher. */
	static final int SCHEDULING_POOL_SIZE = 3;

	/**
	 * The thread pool every {@code @Scheduled} job runs on (T38 review). Without it, the keep-alive
	 * {@link ScheduledExecutorService} below makes Boot's task-scheduling auto-configuration back off and
	 * {@code @Scheduled} falls back to that single {@code shedlock-keep-alive} thread: the five-second outbox
	 * publisher would stall for the whole nightly run, a long drain would delay the daily jobs, and a running
	 * job would block its own lock renewal. {@code @Scheduled} resolves a {@code TaskScheduler} before a
	 * {@code ScheduledExecutorService}, so this bean takes the jobs and leaves the keep-alive thread to ShedLock.
	 */
	@Bean
	ThreadPoolTaskScheduler taskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(SCHEDULING_POOL_SIZE);
		scheduler.setThreadNamePrefix("serfira-scheduling-");
		scheduler.setWaitForTasksToCompleteOnShutdown(true);
		scheduler.setAwaitTerminationSeconds(30);
		return scheduler;
	}

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
