package com.serfira.shared.job.application;

import com.serfira.shared.clock.Clock;
import com.serfira.shared.job.domain.JobRun;
import com.serfira.shared.job.infrastructure.JobRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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
}
