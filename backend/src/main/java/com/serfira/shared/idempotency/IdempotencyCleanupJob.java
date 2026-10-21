package com.serfira.shared.idempotency;

import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.job.application.JobRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Non-transactional orchestration of the idempotency-key cleanup hygiene job (F4/T18, TS §2.5).
 *
 * <p>Physically deletes {@code idempotency_keys} rows whose retention window has passed
 * ({@code expires_at < clock.now()}, Asia/Jakarta) in bounded keyset pages, summing the per-page delete
 * counts into {@code records_processed} for one {@code job_run} row named {@code idempotency-cleanup}
 * (ADR-013 A-9: one row per invocation). Deleting a spent row never re-opens the key — the permanent
 * business-row backstops ({@code uq_contract_idempotency}, the V18 all-status
 * {@code uq_payment_idempotency}) hold independently of the claim row, so a later reuse is still rejected.
 * The 2026-10-11 ADR-017 resolution explicitly declines a pre-business-logic permanent-key probe; a voided
 * payment's key stays spent via ADR-020 D3's backstop, not this job.
 *
 * <p>Mirrors {@code DailyServicingOrchestrator}: runs as the SYSTEM actor, abandons any crash-remnant
 * RUNNING row before starting (the ShedLock guarantees no concurrent live run), retries a transient
 * {@link DataAccessException} on a page with the same backoff shape, and finalizes the run with
 * {@code failHard} on an escaping error so the failure is loud and the counters so far are preserved.
 * {@code records_failed} is always 0 on success: the delete has no per-row failure notion under A-9.
 */
@Service
public class IdempotencyCleanupJob {

	static final String JOB_NAME = "idempotency-cleanup";
	static final int MAX_ATTEMPTS = 5;

	/**
	 * Pauses between the {@link #MAX_ATTEMPTS} attempts of a page delete, mirroring
	 * {@code DailyServicingOrchestrator.BACKOFF_MILLIS}: four gaps between five attempts, indexed by
	 * {@code attempt - 1} on the retrying branch (length == pauses actually taken == {@code MAX_ATTEMPTS - 1}).
	 */
	static final long[] BACKOFF_MILLIS = {50L, 150L, 400L, 1000L};

	private static final Logger LOGGER = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

	private final IdempotencyKeyBatchDeleter batchDeleter;
	private final JobRunService jobRuns;
	private final AuditContext auditContext;
	private final Clock clock;
	private final Sleeper sleeper;
	private final int batchSize;

	public IdempotencyCleanupJob(IdempotencyKeyBatchDeleter batchDeleter, JobRunService jobRuns,
			AuditContext auditContext, Clock clock, Sleeper sleeper,
			@Value("${serfira.jobs.idempotency-cleanup.batch-size:1000}") int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batch-size must be positive");
		}
		this.batchDeleter = batchDeleter;
		this.jobRuns = jobRuns;
		this.auditContext = auditContext;
		this.clock = clock;
		this.sleeper = sleeper;
		this.batchSize = batchSize;
	}

	/** Runs the cleanup under the SYSTEM actor; invoked by the locked entry point, tests, or maintenance. */
	void run() {
		auditContext.runAs(AuditContext.SYSTEM_USER_ID, this::runAsSystem);
	}

	private void runAsSystem() {
		// Any pre-existing RUNNING row of this job name is a crash remnant — the ShedLock guarantees no other
		// run is live (reuses the T26 mechanism verbatim; see JobRunService.abandonStaleRuns).
		jobRuns.abandonStaleRuns(List.of(JOB_NAME));

		UUID runId = jobRuns.start(JOB_NAME, clock.today());
		int deleted = 0;
		try {
			// Keyset-paged delete: each page is its own short REQUIRES_NEW transaction. A short page (fewer
			// than batchSize deleted) is the last page, so the loop stops once the expired set is drained.
			int page;
			do {
				page = deletePageWithRetry();
				deleted += page;
			} while (page == batchSize);
		} catch (RuntimeException failure) {
			// An error escaping the retry loop finalizes the row FAILED with the partial tally, then rethrows
			// so the failure is never swallowed. failHard (not complete) because an aborted run is not clean.
			jobRuns.failHard(runId, deleted, 0);
			throw failure;
		}

		jobRuns.complete(runId, deleted, 0);
	}

	/**
	 * Deletes one page in a fresh transaction, retrying a transient {@link DataAccessException} up to
	 * {@link #MAX_ATTEMPTS} times with {@link #BACKOFF_MILLIS} pauses — the same retry shape as the daily
	 * servicing job's per-contract steps. A non-retryable error or an exhausted budget propagates.
	 */
	private int deletePageWithRetry() {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				return batchDeleter.deletePage(batchSize);
			} catch (DataAccessException failure) {
				if (attempt < MAX_ATTEMPTS) {
					LOGGER.warn("Retrying {} page delete after attempt {} ({})",
							JOB_NAME, attempt, failure.getClass().getSimpleName());
					// attempt is 1-based and the last attempt never reaches here (guarded by
					// attempt < MAX_ATTEMPTS), so index attempt - 1 is always within BACKOFF_MILLIS.
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
					continue;
				}
				LOGGER.error("{} page delete failed after {} attempt(s) ({})",
						JOB_NAME, attempt, failure.getClass().getName());
				throw failure;
			}
		}
		throw new IllegalStateException("unreachable retry state");
	}
}
