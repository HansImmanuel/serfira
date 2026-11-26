package com.serfira.contract.application;

import net.javacrumbs.shedlock.core.LockAssert;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

/**
 * Single locked entry point for the PII re-keying pass (T28b), on its own {@code pii-rekey} ShedLock lock so two
 * instances starting together never re-key at once; ShedLock skips, rather than blocks, the second. The keep-alive
 * lock provider extends {@code lock-at-most-for} while the pass runs. Mirrors {@code LockedIdempotencyCleanupJob}.
 */
public class LockedPiiRekeyJob {

	private final PiiRekeyJob job;

	LockedPiiRekeyJob(PiiRekeyJob job) {
		this.job = job;
	}

	@SchedulerLock(name = "pii-rekey", lockAtMostFor = "${serfira.maintenance.pii-rekey.lock-at-most-for:PT10M}")
	public void run(PiiRekeyMode mode) {
		LockAssert.assertLocked();
		job.run(mode);
	}
}
