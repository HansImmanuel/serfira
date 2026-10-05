package com.serfira.shared.job.infrastructure;

import com.serfira.shared.job.domain.JobRun;
import com.serfira.shared.job.domain.JobRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Persistence for auditable background-job step invocations. */
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {

	/**
	 * Finds rows in the given status whose {@code job_name} is one of the supplied names. Used by crash
	 * recovery (CR-07) to locate leftover {@code RUNNING} rows of the daily-servicing steps only, so an
	 * unrelated job's row is never abandoned.
	 */
	List<JobRun> findByStatusAndJobNameIn(JobRunStatus status, Collection<String> jobNames);
}
