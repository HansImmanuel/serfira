package com.serfira.shared.job.application;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.job.domain.JobRun;
import com.serfira.shared.job.domain.JobRunStatus;
import com.serfira.shared.job.infrastructure.JobRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Persists job-step lifecycle records in transactions independent from financial contract processing.
 * A failed contract transaction therefore cannot erase the run's audit trail.
 */
@Service
public class JobRunService {

	private final JobRunRepository jobRuns;
	private final Clock clock;

	public JobRunService(JobRunRepository jobRuns, Clock clock) {
		this.jobRuns = jobRuns;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public UUID start(String jobName, LocalDate businessDate) {
		Objects.requireNonNull(businessDate, "businessDate");
		return jobRuns.saveAndFlush(new JobRun(jobName, businessDate, clock.now())).getId();
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(UUID jobRunId, int recordsProcessed, int recordsFailed) {
		JobRun jobRun = jobRuns.findById(Objects.requireNonNull(jobRunId, "jobRunId"))
				.orElseThrow(() -> new IllegalStateException("job run not found: " + jobRunId));
		jobRun.complete(recordsProcessed, recordsFailed, clock.now());
		jobRuns.saveAndFlush(jobRun);
	}

	/**
	 * Marks every currently-RUNNING row for the given job names ABANDONED (CR-07), returning how many were
	 * closed. Runs in its own transaction, like {@link #start}/{@link #complete}, so abandonment commits
	 * independently of contract processing. The daily-servicing lock guarantees no other run is live, so a
	 * RUNNING row can only be a crash remnant — no timeout heuristic is needed (ADR-013 A-9 note T26). Each
	 * row passes through the domain {@link JobRun#abandon} guard and the injected {@link Clock} rather than a
	 * DB-side {@code now()}, keeping the clock rule and the audit columns intact.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int abandonStaleRuns(Collection<String> jobNames) {
		Objects.requireNonNull(jobNames, "jobNames");
		List<JobRun> stale = jobRuns.findByStatusAndJobNameIn(JobRunStatus.RUNNING, jobNames);
		OffsetDateTime finishedAt = clock.now();
		for (JobRun run : stale) {
			run.abandon(finishedAt);
		}
		jobRuns.saveAll(stale);
		return stale.size();
	}

	/**
	 * Finalizes a run that aborted before finishing as FAILED, preserving the counters tallied so far
	 * (CR-07a). Runs in its own transaction like {@link #complete}, so the three failed rows commit
	 * independently of the contract loop that is unwinding.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void failHard(UUID jobRunId, int recordsProcessed, int recordsFailed) {
		JobRun jobRun = jobRuns.findById(Objects.requireNonNull(jobRunId, "jobRunId"))
				.orElseThrow(() -> new IllegalStateException("job run not found: " + jobRunId));
		jobRun.failHard(recordsProcessed, recordsFailed, clock.now());
		jobRuns.saveAndFlush(jobRun);
	}
}
