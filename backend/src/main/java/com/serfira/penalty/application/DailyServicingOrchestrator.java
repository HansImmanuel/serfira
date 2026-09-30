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
 * Non-transactional batch orchestration of the daily job: billing → penalty accrual → aging (Addendum §7.3).
 *
 * <p>Per contract there are two transactions, each retried on its own: billing + penalty together
 * ({@link DailyServicingContractProcessor#process}), then aging ({@link DailyServicingContractProcessor#age}).
 * Aging runs even when billing + penalty failed for that contract, because its condition does not depend on
 * the penalty (ADR-013 implementation note T6). Each processor invocation owns a separate transaction,
 * isolating one contract's failure from the rest.
 *
 * <p>{@code job_run} gets one row per step (A-9). {@code billing} and {@code penalty-accrual} share one
 * transaction and therefore one outcome per contract; {@code aging} is counted separately.
 */
@Service
public class DailyServicingOrchestrator {

	static final String BILLING_JOB_NAME = "billing";
	static final String PENALTY_JOB_NAME = "penalty-accrual";
	static final String AGING_JOB_NAME = "aging";
	static final int MAX_ATTEMPTS = 5;

	private static final String BILLING_AND_PENALTY_STEP = "billing+penalty";

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
		UUID agingRunId = jobRuns.start(AGING_JOB_NAME, businessDate);
		StepCounters servicing = new StepCounters();
		StepCounters aging = new StepCounters();

		for (UUID contractId : contracts.findActiveContractIds()) {
			ProcessingOutcome servicingOutcome = runWithRetry(contractId, BILLING_AND_PENALTY_STEP,
					() -> processor.process(contractId, businessDate));
			servicing.record(servicingOutcome);

			// SKIPPED means the contract was observed non-ACTIVE; ACTIVE is never re-entered (DM §1.3), so
			// aging would only be skipped again. A FAILED servicing step does not stop aging.
			ProcessingOutcome agingOutcome = servicingOutcome == ProcessingOutcome.SKIPPED
					? ProcessingOutcome.SKIPPED
					: runWithRetry(contractId, AGING_JOB_NAME, () -> processor.age(contractId, businessDate));
			aging.record(agingOutcome);
		}

		jobRuns.complete(billingRunId, servicing.processed, servicing.failed);
		jobRuns.complete(penaltyRunId, servicing.processed, servicing.failed);
		jobRuns.complete(agingRunId, aging.processed, aging.failed);
	}

	/**
	 * Runs one per-contract step in fresh transactions, up to {@link #MAX_ATTEMPTS} times for retryable
	 * conflicts. A contract that is no longer ACTIVE is skipped rather than failed.
	 */
	private ProcessingOutcome runWithRetry(UUID contractId, String stepName, Runnable step) {
		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			try {
				step.run();
				return ProcessingOutcome.PROCESSED;
			} catch (RuntimeException failure) {
				if (failure instanceof ContractStateException && !contracts.isActive(contractId)) {
					logSkipped(contractId, stepName);
					return ProcessingOutcome.SKIPPED;
				}

				boolean retryable = DailyServicingConflictClassifier.isRetryable(failure);
				if (retryable && attempt < MAX_ATTEMPTS) {
					if (!contracts.isActive(contractId)) {
						logSkipped(contractId, stepName);
						return ProcessingOutcome.SKIPPED;
					}
					LOGGER.warn("Retrying daily {} for contract {} after attempt {} ({})",
							stepName, contractId, attempt, failure.getClass().getSimpleName());
					continue;
				}

				if (!contracts.isActive(contractId)) {
					logSkipped(contractId, stepName);
					return ProcessingOutcome.SKIPPED;
				}
				LOGGER.error("Daily {} failed for contract {} after {} attempt(s) ({})",
						stepName, contractId, attempt, failure.getClass().getName());
				return ProcessingOutcome.FAILED;
			}
		}
		throw new IllegalStateException("unreachable retry state");
	}

	private static void logSkipped(UUID contractId, String stepName) {
		LOGGER.info("Daily {} skipped contract {} because it is no longer ACTIVE", stepName, contractId);
	}

	private enum ProcessingOutcome {
		PROCESSED,
		SKIPPED,
		FAILED
	}

	/** Contract counters of one {@code job_run} step (A-9: contracts, not rows). */
	private static final class StepCounters {

		private int processed;
		private int failed;

		void record(ProcessingOutcome outcome) {
			if (outcome == ProcessingOutcome.PROCESSED) {
				processed++;
			} else if (outcome == ProcessingOutcome.FAILED) {
				failed++;
			}
		}
	}
}
