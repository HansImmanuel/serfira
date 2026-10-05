package com.serfira.shared.job.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure-Java unit tests for the {@link JobRun} terminal transitions added by T26 (CR-07). */
class JobRunTest {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);
	private static final OffsetDateTime STARTED_AT = OffsetDateTime.of(2026, 3, 5, 1, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime FINISHED_AT = STARTED_AT.plusMinutes(3);

	private JobRun running() {
		return new JobRun("billing", BUSINESS_DATE, STARTED_AT);
	}

	@Test
	void abandonMovesARunningRowToAbandonedAndStampsFinishedAt() {
		JobRun run = running();

		run.abandon(FINISHED_AT);

		assertThat(run.getStatus()).isEqualTo(JobRunStatus.ABANDONED);
		assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
	}

	@Test
	void abandonRejectsANonRunningRow() {
		JobRun run = running();
		run.complete(0, 0, FINISHED_AT);

		assertThatThrownBy(() -> run.abandon(FINISHED_AT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already");
	}

	@Test
	void abandonRejectsANullFinishedAt() {
		JobRun run = running();

		assertThatThrownBy(() -> run.abandon(null))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void failHardAlwaysSetsFailedEvenWithZeroFailedCount() {
		JobRun run = running();

		run.failHard(7, 0, FINISHED_AT);

		// FAILED regardless of recordsFailed: an aborted run is not a clean completion (CR-07a).
		assertThat(run.getStatus()).isEqualTo(JobRunStatus.FAILED);
		assertThat(run.getRecordsProcessed()).isEqualTo(7);
		assertThat(run.getRecordsFailed()).isZero();
		assertThat(run.getFinishedAt()).isEqualTo(FINISHED_AT);
	}

	@Test
	void failHardKeepsNonZeroCounters() {
		JobRun run = running();

		run.failHard(4, 2, FINISHED_AT);

		assertThat(run.getStatus()).isEqualTo(JobRunStatus.FAILED);
		assertThat(run.getRecordsProcessed()).isEqualTo(4);
		assertThat(run.getRecordsFailed()).isEqualTo(2);
	}

	@Test
	void failHardRejectsNegativeCounters() {
		JobRun run = running();

		assertThatThrownBy(() -> run.failHard(-1, 0, FINISHED_AT))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> run.failHard(0, -1, FINISHED_AT))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void failHardRejectsANonRunningRow() {
		JobRun run = running();
		run.complete(1, 0, FINISHED_AT);

		assertThatThrownBy(() -> run.failHard(1, 0, FINISHED_AT))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("already");
	}
}
