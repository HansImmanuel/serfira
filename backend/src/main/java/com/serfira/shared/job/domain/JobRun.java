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
