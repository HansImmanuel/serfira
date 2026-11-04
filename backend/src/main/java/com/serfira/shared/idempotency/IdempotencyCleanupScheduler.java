package com.serfira.shared.idempotency;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron trigger for the idempotency-key cleanup (F4/T18), delegating to the common locked entry point.
 * Runs in the Asia/Jakarta business zone; the special cron value {@code -} disables the trigger (used by
 * tests and controlled maintenance). Mirrors {@code DailyServicingScheduler}.
 */
@Component
public class IdempotencyCleanupScheduler {

	private final LockedIdempotencyCleanupJob job;

	public IdempotencyCleanupScheduler(LockedIdempotencyCleanupJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${serfira.jobs.idempotency-cleanup.cron}", zone = "Asia/Jakarta")
	public void runScheduled() {
		job.run();
	}
}
