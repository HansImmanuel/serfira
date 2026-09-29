package com.serfira.penalty.application;

import com.serfira.contract.application.ActiveContractListingPort;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.job.application.JobRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Non-transactional batch orchestration for daily billing followed by penalty accrual.
 * Each processor invocation owns a separate transaction, isolating one contract's failure from the rest.
 */
@Service
public class DailyServicingOrchestrator {

	static final String BILLING_JOB_NAME = "billing";
	static final String PENALTY_JOB_NAME = "penalty-accrual";
	static final int MAX_ATTEMPTS = 5;

	private static final Logger LOGGER = LoggerFactory.getLogger(DailyServicingOrchestrator.class);

	private final ActiveContractListingPort contracts;
	private final DailyServicingContractProcessor processor;
	private final JobRunService jobRuns;
	private final AuditContext auditContext;
	private final Clock clock;

	public DailyServicingOrchestrator(ActiveContractListingPort contracts,
			DailyServicingContractProcessor processor, JobRunService jobRuns,
			AuditContext auditContext, Clock clock) {
		this.contracts = contracts;
		this.processor = processor;
		this.jobRuns = jobRuns;
		this.auditContext = auditContext;
		this.clock = clock;
	}

	/** Runs an explicit business date for the scheduler, tests, or controlled backfill. */
	void run(LocalDate businessDate) {
		Objects.requireNonNull(businessDate, "businessDate");
		LocalDate today = clock.today();
		if (businessDate.isAfter(today)) {
			throw new IllegalArgumentException("businessDate " + businessDate + " is after today " + today);
		}
		auditContext.runAs(AuditContext.SYSTEM_USER_ID, () -> runAsSystem(businessDate));
	}

	private void runAsSystem(LocalDate businessDate) {
		UUID billingRunId = jobRuns.start(BILLING_JOB_NAME, businessDate);
		UUID penaltyRunId = jobRuns.start(PENALTY_JOB_NAME, businessDate);
		int processed = 0;
		int failed = 0;

		for (UUID contractId : contracts.findActiveContractIds()) {
			ProcessingOutcome outcome = processContract(contractId, businessDate);
			if (outcome == ProcessingOutcome.PROCESSED) {
				processed++;
			} else if (outcome == ProcessingOutcome.FAILED) {
				failed++;
			}
		}

		jobRuns.complete(billingRunId, processed, failed);
		jobRuns.complete(penaltyRunId, processed, failed);
	}

	private ProcessingOutcome processContract(UUID contractId, LocalDate businessDate) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				processor.process(contractId, businessDate);
				return ProcessingOutcome.PROCESSED;
			} catch (RuntimeException failure) {
				if (failure instanceof ContractStateException && !contracts.isActive(contractId)) {
					LOGGER.info("Daily servicing skipped contract {} because it is no longer ACTIVE", contractId);
					return ProcessingOutcome.SKIPPED;
				}

				boolean retryable = DailyServicingConflictClassifier.isRetryable(failure);
				if (retryable && attempt < MAX_ATTEMPTS) {
					if (!contracts.isActive(contractId)) {
						LOGGER.info("Daily servicing skipped contract {} because it is no longer ACTIVE", contractId);
						return ProcessingOutcome.SKIPPED;
					}
					LOGGER.warn("Retrying daily servicing for contract {} after attempt {} ({})",
							contractId, attempt, failure.getClass().getSimpleName());
					continue;
				}

				if (!contracts.isActive(contractId)) {
					LOGGER.info("Daily servicing skipped contract {} because it is no longer ACTIVE", contractId);
					return ProcessingOutcome.SKIPPED;
				}
				LOGGER.error("Daily servicing failed for contract {} after {} attempt(s) ({})",
						contractId, attempt, failure.getClass().getName());
				return ProcessingOutcome.FAILED;
			}
		}
		throw new IllegalStateException("unreachable retry state");
	}

	private enum ProcessingOutcome {
		PROCESSED,
		SKIPPED,
		FAILED
	}
}
