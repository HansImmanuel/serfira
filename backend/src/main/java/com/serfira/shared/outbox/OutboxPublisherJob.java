package com.serfira.shared.outbox;

import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.job.application.JobRunService;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One publisher tick (ADR-027), under the SYSTEM actor: drains {@code PENDING} events batch by batch.
 *
 * <p>The tick continues while batches come back full and clean. It stops at a short batch (the backlog is
 * drained) and also at a batch with any failure, so a refused event waits for the next tick instead of
 * spending its whole retry budget in one tight loop.
 *
 * <p>{@code job_run} policy (ADR-027): only a tick in which at least one event failed writes a row named
 * {@code outbox-publisher}, with {@code records_processed} = events sent and {@code records_failed} = events
 * refused, so {@code JobRun.complete} marks it {@code FAILED}. Idle and all-success ticks write nothing — at a
 * five-second cadence they would bury the daily jobs' rows; their record is the per-event log line and the
 * {@code SENT} row.
 */
@Service
public class OutboxPublisherJob {

	static final String JOB_NAME = "outbox-publisher";

	private final OutboxBatchPublisher batches;
	private final JobRunService jobRuns;
	private final AuditContext auditContext;
	private final Clock clock;
	private final int batchSize;

	public OutboxPublisherJob(OutboxBatchPublisher batches, JobRunService jobRuns, AuditContext auditContext,
			Clock clock, @Value("${serfira.jobs.outbox-publisher.batch-size:100}") int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batch-size must be positive");
		}
		this.batches = batches;
		this.jobRuns = jobRuns;
		this.auditContext = auditContext;
		this.clock = clock;
		this.batchSize = batchSize;
	}

	/** Runs one tick under the SYSTEM actor; invoked by the locked entry point. */
	void run() {
		auditContext.runAs(AuditContext.SYSTEM_USER_ID, this::runAsSystem);
	}

	private void runAsSystem() {
		int sent = 0;
		int failed = 0;
		OutboxBatchResult batch;
		do {
			batch = batches.publishBatch(batchSize);
			sent += batch.sent();
			failed += batch.failed();
		} while (batch.claimed() == batchSize && batch.failed() == 0);

		if (failed > 0) {
			UUID runId = jobRuns.start(JOB_NAME, clock.today());
			jobRuns.complete(runId, sent, failed);
		}
	}
}
