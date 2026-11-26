package com.serfira.shared.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.job.application.JobRunService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * T38 / ADR-027: a tick drains full batches, stops at a short batch or at a batch with failures (so a failing
 * event retries on the next tick, not in a tight loop), and records a {@code job_run} only when an event failed.
 */
class OutboxPublisherJobTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);

	private final OutboxBatchPublisher batches = mock(OutboxBatchPublisher.class);
	private final JobRunService jobRuns = mock(JobRunService.class);
	private final FixedClock clock = new FixedClock(TODAY);

	@Test
	void anIdleTickWritesNoJobRun() {
		when(batches.publishBatch(100)).thenReturn(new OutboxBatchResult(0, 0, 0));

		job(100).run();

		verify(batches, times(1)).publishBatch(100);
		verifyNoInteractions(jobRuns);
	}

	@Test
	void anAllSuccessTickWritesNoJobRun() {
		when(batches.publishBatch(100)).thenReturn(new OutboxBatchResult(40, 40, 0));

		job(100).run();

		verifyNoInteractions(jobRuns);
	}

	@Test
	void theJobDrainsFullBatchesUntilAShortOne() {
		when(batches.publishBatch(100)).thenReturn(new OutboxBatchResult(100, 100, 0),
				new OutboxBatchResult(40, 40, 0));

		job(100).run();

		verify(batches, times(2)).publishBatch(100);
	}

	@Test
	void aBatchWithFailuresEndsTheTick() {
		when(batches.publishBatch(100)).thenReturn(new OutboxBatchResult(100, 99, 1),
				new OutboxBatchResult(100, 100, 0));

		job(100).run();

		verify(batches, times(1)).publishBatch(100);
	}

	@Test
	void aTickWithFailuresRecordsOneJobRun() {
		UUID runId = UUID.randomUUID();
		when(jobRuns.start(OutboxPublisherJob.JOB_NAME, TODAY)).thenReturn(runId);
		when(batches.publishBatch(100)).thenReturn(new OutboxBatchResult(3, 2, 1));

		job(100).run();

		verify(jobRuns).start(eq("outbox-publisher"), eq(TODAY));
		verify(jobRuns).complete(runId, 2, 1);
		verify(jobRuns, times(0)).failHard(any(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	void nonPositiveBatchSizeIsRejected() {
		assertThatThrownBy(() -> job(0)).isInstanceOf(IllegalArgumentException.class);
	}

	private OutboxPublisherJob job(int batchSize) {
		return new OutboxPublisherJob(batches, jobRuns, new AuditContext(), clock, batchSize);
	}
}
