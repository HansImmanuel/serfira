package com.serfira.shared.idempotency;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

/**
 * Single locked entry point for the idempotency-key cleanup (F4/T18), shared by the cron trigger and
 * explicit test/maintenance callers. A distinct lock name {@code idempotency-cleanup} (not the
 * daily-servicing lock) lets the two jobs run independently and never block each other; ShedLock skips,
 * rather than blocks, a concurrent invocation holding the same lock name. Mirrors
 * {@code LockedDailyServicingJob}.
 */
@Service
public class LockedIdempotencyCleanupJob {

	private final IdempotencyCleanupJob job;

	public LockedIdempotencyCleanupJob(IdempotencyCleanupJob job) {
		this.job = job;
	}

	@SchedulerLock(name = "idempotency-cleanup",
			lockAtMostFor = "${serfira.jobs.idempotency-cleanup.lock-at-most-for:PT10M}")
	public void run() {
		LockAssert.assertLocked();
		job.run();
	}
}
