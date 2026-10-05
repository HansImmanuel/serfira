package com.serfira.penalty.application;

import com.serfira.contract.application.ActiveContractListingPort;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.Clock;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.job.application.JobRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
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

	/**
	 * Pauses between the {@link #MAX_ATTEMPTS} attempts of a per-contract step (CR-09, Addendum §5). Four
	 * gaps between five attempts, so exactly four entries — no unreachable trailing slot (T24/CR-13 lesson:
	 * array length == pauses actually taken == {@code MAX_ATTEMPTS - 1}). Indexed by {@code attempt - 1} on
	 * the retrying branch.
	 */
	static final long[] BACKOFF_MILLIS = {50L, 150L, 400L, 1000L};

	private static final String BILLING_AND_PENALTY_STEP = "billing+penalty";

	/** Keyset cursor for the first page: strictly below every generated id, so no untyped null (X-13). */
	private static final UUID ZERO_UUID = new UUID(0L, 0L);

	private static final Logger LOGGER = LoggerFactory.getLogger(DailyServicingOrchestrator.class);

	private final ActiveContractListingPort contracts;
	private final DailyServicingContractProcessor processor;
	private final JobRunService jobRuns;
	private final AuditContext auditContext;
	private final Clock clock;
	private final Sleeper sleeper;
	private final int batchSize;

	public DailyServicingOrchestrator(ActiveContractListingPort contracts,
			DailyServicingContractProcessor processor, JobRunService jobRuns,
			AuditContext auditContext, Clock clock, Sleeper sleeper,
			@Value("${serfira.jobs.daily-servicing.batch-size:500}") int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batch-size must be positive");
		}
		this.contracts = contracts;
		this.processor = processor;
		this.jobRuns = jobRuns;
		this.auditContext = auditContext;
		this.clock = clock;
		this.sleeper = sleeper;
		this.batchSize = batchSize;
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
		// CR-07 (b): any pre-existing RUNNING row of these three job names is a crash remnant — the
		// ShedLock guarantees no other run is live. Abandon them before starting this run's rows, so the
		// fresh rows (not yet RUNNING when this reads) are never mistaken for stale.
		jobRuns.abandonStaleRuns(List.of(BILLING_JOB_NAME, PENALTY_JOB_NAME, AGING_JOB_NAME));

		UUID billingRunId = jobRuns.start(BILLING_JOB_NAME, businessDate);
		UUID penaltyRunId = jobRuns.start(PENALTY_JOB_NAME, businessDate);
		UUID agingRunId = jobRuns.start(AGING_JOB_NAME, businessDate);
		StepCounters servicing = new StepCounters();
		StepCounters aging = new StepCounters();

		try {
			// CR-08: keyset (seek) paging through the contract port, one page of batchSize ids at a time.
			// ids are ascending, so the last processed id is the next page's cursor; a short page is the
			// last page. Each contract keeps its own transactions, exactly as before.
			UUID cursor = ZERO_UUID;
			List<UUID> page;
			do {
				page = contracts.findActiveContractIdsAfter(cursor, batchSize);
				for (UUID contractId : page) {
					ProcessingOutcome servicingOutcome = runWithRetry(contractId, BILLING_AND_PENALTY_STEP,
							() -> processor.process(contractId, businessDate));
					servicing.record(servicingOutcome);

					// SKIPPED means the contract was observed non-ACTIVE; ACTIVE is never re-entered
					// (DM §1.3), so aging would only be skipped again. A FAILED servicing step does not
					// stop aging.
					ProcessingOutcome agingOutcome = servicingOutcome == ProcessingOutcome.SKIPPED
							? ProcessingOutcome.SKIPPED
							: runWithRetry(contractId, AGING_JOB_NAME,
									() -> processor.age(contractId, businessDate));
					aging.record(agingOutcome);

					cursor = contractId;
				}
			} while (page.size() == batchSize);
		} catch (RuntimeException loopFailure) {
			// CR-07 (a): an exception escaping the loop (only infra calls can — runWithRetry absorbs every
			// per-contract RuntimeException into FAILED/SKIPPED) finalizes the three rows as FAILED with the
			// partial tallies, then rethrows so the failure is loud, never swallowed. failHard (not
			// complete) is used because an aborted run is not a clean completion: complete would read
			// COMPLETED if no contract had failed yet. Error/Throwable are deliberately not caught; CR-07 (b)
			// finalizes any row they leave RUNNING on the next locked run.
			jobRuns.failHard(billingRunId, servicing.processed, servicing.failed);
			jobRuns.failHard(penaltyRunId, servicing.processed, servicing.failed);
			jobRuns.failHard(agingRunId, aging.processed, aging.failed);
			throw loopFailure;
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
					// CR-09: back off before the next attempt. attempt is 1-based and the last attempt
					// never reaches here (guarded by attempt < MAX_ATTEMPTS), so index attempt - 1 is
					// always within BACKOFF_MILLIS (length == MAX_ATTEMPTS - 1).
					sleeper.sleep(BACKOFF_MILLIS[attempt - 1]);
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
