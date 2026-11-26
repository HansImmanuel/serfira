package com.serfira.shared.outbox;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron trigger for the outbox publisher (ADR-027), delegating to the locked entry point. The special cron value
 * {@code -} disables it (tests). Mirrors {@code IdempotencyCleanupScheduler}.
 */
@Component
public class OutboxPublisherScheduler {

	private final LockedOutboxPublisherJob job;

	public OutboxPublisherScheduler(LockedOutboxPublisherJob job) {
		this.job = job;
	}

	@Scheduled(cron = "${serfira.jobs.outbox-publisher.cron}", zone = "Asia/Jakarta")
	public void runScheduled() {
		job.run();
	}
}
