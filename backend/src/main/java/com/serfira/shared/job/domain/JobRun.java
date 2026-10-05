package com.serfira.shared.job.domain;

import com.serfira.shared.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** One auditable step invocation of an in-process background job (Addendum §10, ADR-013). */
@Entity
@Table(name = "job_run")
public class JobRun extends Auditable {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "job_name", nullable = false, updatable = false, length = 60)
	private String jobName;

	@Column(name = "business_date", nullable = false, updatable = false)
	private LocalDate businessDate;

	@Column(name = "started_at", nullable = false, updatable = false, columnDefinition = "timestamptz")
	private OffsetDateTime startedAt;

	@Column(name = "finished_at", columnDefinition = "timestamptz")
	private OffsetDateTime finishedAt;

	@Column(name = "records_processed", nullable = false)
	private int recordsProcessed;

	@Column(name = "records_failed", nullable = false)
	private int recordsFailed;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private JobRunStatus status;

	protected JobRun() {
		// JPA
	}

	public JobRun(String jobName, LocalDate businessDate, OffsetDateTime startedAt) {
		if (jobName == null || jobName.isBlank() || jobName.length() > 60) {
			throw new IllegalArgumentException("jobName must contain 1 to 60 characters");
		}
		this.jobName = jobName;
		this.businessDate = Objects.requireNonNull(businessDate, "businessDate");
		this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
		this.status = JobRunStatus.RUNNING;
	}

	public void complete(int recordsProcessed, int recordsFailed, OffsetDateTime finishedAt) {
		if (status != JobRunStatus.RUNNING) {
			throw new IllegalStateException("job run " + id + " is already " + status);
		}
		if (recordsProcessed < 0 || recordsFailed < 0) {
			throw new IllegalArgumentException("job run counters must be non-negative");
		}
		this.recordsProcessed = recordsProcessed;
		this.recordsFailed = recordsFailed;
		this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
		this.status = recordsFailed == 0 ? JobRunStatus.COMPLETED : JobRunStatus.FAILED;
	}

	/**
	 * Marks a leftover RUNNING row as ABANDONED after a crash left it unfinished (CR-07; ADR-013 A-9
	 * implementation note T26). The caller holds the daily-servicing lock, so no live run owns this row.
	 * Counters are left at whatever the crashed run wrote (we do not know how far it got).
	 */
	public void abandon(OffsetDateTime finishedAt) {
		if (status != JobRunStatus.RUNNING) {
			throw new IllegalStateException("job run " + id + " is already " + status);
		}
		this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
		this.status = JobRunStatus.ABANDONED;
	}

	/**
	 * Finalizes a run that aborted before finishing, as FAILED, preserving the partial tallies at the moment
	 * of the abort (CR-07a). Same {@code status == RUNNING} and non-negative counter guards as
	 * {@link #complete}, but the status is unconditionally FAILED rather than derived from
	 * {@code recordsFailed}: an escaping exception is not a clean completion. ABANDONED (crash recovery) and
	 * FAILED (in-process loop escape) therefore stay distinct audit signals.
	 */
	public void failHard(int recordsProcessed, int recordsFailed, OffsetDateTime finishedAt) {
		if (status != JobRunStatus.RUNNING) {
			throw new IllegalStateException("job run " + id + " is already " + status);
		}
		if (recordsProcessed < 0 || recordsFailed < 0) {
			throw new IllegalArgumentException("job run counters must be non-negative");
		}
		this.recordsProcessed = recordsProcessed;
		this.recordsFailed = recordsFailed;
		this.finishedAt = Objects.requireNonNull(finishedAt, "finishedAt");
		this.status = JobRunStatus.FAILED;
	}

	public UUID getId() {
		return id;
	}

	public String getJobName() {
		return jobName;
	}

	public LocalDate getBusinessDate() {
		return businessDate;
	}

	public OffsetDateTime getStartedAt() {
		return startedAt;
	}

	public OffsetDateTime getFinishedAt() {
		return finishedAt;
	}

	public int getRecordsProcessed() {
		return recordsProcessed;
	}

	public int getRecordsFailed() {
		return recordsFailed;
	}

	public JobRunStatus getStatus() {
		return status;
	}
}
