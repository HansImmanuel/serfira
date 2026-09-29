package com.serfira.penalty.application;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Single locked entry point shared by the cron trigger and explicit backfill callers.
 * ShedLock skips, rather than blocks, a concurrent invocation holding the same lock name.
 */
@Service
public class LockedDailyServicingJob {

	private final DailyServicingOrchestrator orchestrator;

	public LockedDailyServicingJob(DailyServicingOrchestrator orchestrator) {
		this.orchestrator = orchestrator;
	}

	@SchedulerLock(name = "daily-servicing", lockAtMostFor = "${serfira.jobs.daily-servicing.lock-at-most-for:PT6H}")
	public void run(LocalDate businessDate) {
		LockAssert.assertLocked();
		orchestrator.run(Objects.requireNonNull(businessDate, "businessDate"));
	}
}
