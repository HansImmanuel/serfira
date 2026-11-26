package com.serfira.shared.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.serfira.TestcontainersConfiguration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * T38 review: {@code @Scheduled} jobs must not run on ShedLock's single keep-alive thread. The keep-alive
 * {@code ScheduledExecutorService} bean makes Boot's task-scheduling auto-configuration back off, so without a
 * dedicated {@code TaskScheduler} every job — the five-second outbox publisher included — shares that one thread
 * with the lock renewals: the publisher stalls for the whole nightly run and a long drain delays the daily jobs.
 */
@Import({TestcontainersConfiguration.class, SchedulingThreadIT.ProbeConfig.class})
@SpringBootTest
class SchedulingThreadIT {

	static final class Probe {
		final CompletableFuture<String> thread = new CompletableFuture<>();

		@Scheduled(fixedDelay = 50)
		void tick() {
			thread.complete(Thread.currentThread().getName());
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfig {
		@Bean
		Probe schedulingThreadProbe() {
			return new Probe();
		}
	}

	@Autowired
	Probe probe;

	@Test
	void scheduledJobsRunOnTheSchedulingPoolNotTheShedLockKeepAliveThread() throws Exception {
		String thread = probe.thread.get(30, TimeUnit.SECONDS);

		assertThat(thread).isNotEqualTo("shedlock-keep-alive").startsWith("serfira-scheduling-");
	}
}
