package com.serfira.shared.outbox;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

/**
 * Single locked entry point for the outbox publisher (ADR-027), shared by the scheduler and tests. Its own lock
 * name {@code outbox-publisher}, so it never blocks or is blocked by the daily jobs; a concurrent tick on
 * another instance is skipped, and {@code SKIP LOCKED} keeps even an unlocked caller from double-publishing.
 * Mirrors {@code LockedIdempotencyCleanupJob}.
 */
@Service
public class LockedOutboxPublisherJob {

	private final OutboxPublisherJob job;

	public LockedOutboxPublisherJob(OutboxPublisherJob job) {
		this.job = job;
	}

	@SchedulerLock(name = "outbox-publisher",
			lockAtMostFor = "${serfira.jobs.outbox-publisher.lock-at-most-for:PT1M}")
	public void run() {
		LockAssert.assertLocked();
		job.run();
	}
}
