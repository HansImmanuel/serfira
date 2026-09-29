package com.serfira.penalty.application;

import com.serfira.shared.clock.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Captures one Jakarta business date and delegates scheduled work to the common locked entry point. */
@Component
public class DailyServicingScheduler {

	private final LockedDailyServicingJob job;
	private final Clock clock;

	public DailyServicingScheduler(LockedDailyServicingJob job, Clock clock) {
		this.job = job;
		this.clock = clock;
	}

	@Scheduled(cron = "${serfira.jobs.daily-servicing.cron}", zone = "Asia/Jakarta")
	public void runScheduled() {
		job.run(clock.today());
	}
}
