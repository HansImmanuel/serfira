package com.serfira.shared.job.infrastructure;

import com.serfira.shared.job.domain.JobRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Persistence for auditable background-job step invocations. */
public interface JobRunRepository extends JpaRepository<JobRun, UUID> {
}
