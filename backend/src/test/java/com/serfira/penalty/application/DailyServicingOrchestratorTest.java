package com.serfira.penalty.application;

import com.serfira.contract.application.ActiveContractListingPort;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.concurrency.Sleeper;
import com.serfira.shared.job.application.JobRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyServicingOrchestratorTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 3, 5);
	private static final UUID ZERO_UUID = new UUID(0L, 0L);
	private static final int BATCH_SIZE = 1000;

	private final ActiveContractListingPort contracts = mock(ActiveContractListingPort.class);
	private final DailyServicingContractProcessor processor = mock(DailyServicingContractProcessor.class);
	private final JobRunService jobRuns = mock(JobRunService.class);
	private final AuditContext auditContext = new AuditContext();
	private final FixedClock clock = new FixedClock(TODAY);
	private final RecordingSleeper sleeper = new RecordingSleeper();
	private final DailyServicingOrchestrator orchestrator = new DailyServicingOrchestrator(
			contracts, processor, jobRuns, auditContext, clock, sleeper, BATCH_SIZE);

	private final UUID billingRun = UUID.randomUUID();
	private final UUID penaltyRun = UUID.randomUUID();
	private final UUID agingRun = UUID.randomUUID();

	@BeforeEach
	void stubRunRows() {
		when(jobRuns.start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY)).thenReturn(billingRun);
		when(jobRuns.start(DailyServicingOrchestrator.PENALTY_JOB_NAME, TODAY)).thenReturn(penaltyRun);
		when(jobRuns.start(DailyServicingOrchestrator.AGING_JOB_NAME, TODAY)).thenReturn(agingRun);
	}

	@AfterEach
	void restoreSystemActor() {
		auditContext.resetToSystem();
	}

	/**
	 * Stubs the keyset port so a single page of {@code ids} is returned on the first page, then an empty
	 * page once the cursor has advanced past the last id. {@code BATCH_SIZE} is large, so the whole list is
	 * one page and all existing single-page expectations hold.
	 */
	private void stubActiveContracts(List<UUID> ids) {
		when(contracts.findActiveContractIdsAfter(ZERO_UUID, BATCH_SIZE)).thenReturn(ids);
		if (!ids.isEmpty()) {
			UUID last = ids.get(ids.size() - 1);
			when(contracts.findActiveContractIdsAfter(last, BATCH_SIZE)).thenReturn(List.of());
		}
	}

	@Test
	void retriesAtMostFiveTimesThenContinuesAndRecordsTheFailedContract() {
		UUID failedContract = UUID.randomUUID();
		UUID healthyContract = UUID.randomUUID();
		stubActiveContracts(List.of(failedContract, healthyContract));
		when(contracts.isActive(failedContract)).thenReturn(true);
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, failedContract))
				.when(processor).process(failedContract, TODAY);

		orchestrator.run(TODAY);

		verify(processor, times(5)).process(failedContract, TODAY);
		verify(processor).process(healthyContract, TODAY);
		verify(jobRuns).complete(billingRun, 1, 1);
		verify(jobRuns).complete(penaltyRun, 1, 1);
		// Aging still runs for the contract whose billing + penalty failed (ADR-013 implementation note T6).
		verify(processor).age(failedContract, TODAY);
		verify(processor).age(healthyContract, TODAY);
		verify(jobRuns).complete(agingRun, 2, 0);
	}

	@Test
	void deterministicFailureIsNotRetriedAndANonActiveRaceIsSkipped() {
		UUID deterministicFailure = UUID.randomUUID();
		UUID closedDuringRun = UUID.randomUUID();
		stubActiveContracts(List.of(deterministicFailure, closedDuringRun));
		when(contracts.isActive(deterministicFailure)).thenReturn(true);
		when(contracts.isActive(closedDuringRun)).thenReturn(false);
		doThrow(new IllegalStateException("invalid data")).when(processor).process(deterministicFailure, TODAY);
		doThrow(new ContractStateException("no longer active")).when(processor).process(closedDuringRun, TODAY);

		orchestrator.run(TODAY);

		verify(processor).process(deterministicFailure, TODAY);
		verify(processor).process(closedDuringRun, TODAY);
		verify(jobRuns).complete(billingRun, 0, 1);
		verify(jobRuns).complete(penaltyRun, 0, 1);
		// A contract already observed non-ACTIVE is not aged: it can never become ACTIVE again.
		verify(processor).age(deterministicFailure, TODAY);
		verify(processor, never()).age(closedDuringRun, TODAY);
		verify(jobRuns).complete(agingRun, 1, 0);
	}

	@Test
	void agingRunsAfterBillingAndPenaltyForEachContract() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		stubActiveContracts(List.of(first, second));

		orchestrator.run(TODAY);

		InOrder order = inOrder(processor);
		order.verify(processor).process(first, TODAY);
		order.verify(processor).age(first, TODAY);
		order.verify(processor).process(second, TODAY);
		order.verify(processor).age(second, TODAY);
		verify(jobRuns).complete(billingRun, 2, 0);
		verify(jobRuns).complete(penaltyRun, 2, 0);
		verify(jobRuns).complete(agingRun, 2, 0);
	}

	@Test
	void agingConflictsAreRetriedAtMostFiveTimesAndCountedOnTheAgingRowOnly() {
		UUID conflicted = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		stubActiveContracts(List.of(conflicted, healthy));
		when(contracts.isActive(conflicted)).thenReturn(true);
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, conflicted))
				.when(processor).age(conflicted, TODAY);

		orchestrator.run(TODAY);

		verify(processor).process(conflicted, TODAY);
		verify(processor, times(5)).age(conflicted, TODAY);
		verify(processor).age(healthy, TODAY);
		// Recognized money is committed separately and stays counted as processed.
		verify(jobRuns).complete(billingRun, 2, 0);
		verify(jobRuns).complete(penaltyRun, 2, 0);
		verify(jobRuns).complete(agingRun, 1, 1);
	}

	@Test
	void aTransientAgingConflictIsRecoveredByARetry() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));
		when(contracts.isActive(contractId)).thenReturn(true);
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.doNothing()
				.when(processor).age(contractId, TODAY);

		orchestrator.run(TODAY);

		verify(processor, times(2)).age(contractId, TODAY);
		verify(jobRuns).complete(agingRun, 1, 0);
	}

	@Test
	void aContractClosedBetweenServicingAndAgingIsSkippedNotFailed() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));
		when(contracts.isActive(contractId)).thenReturn(false);
		doThrow(new ContractStateException("no longer active")).when(processor).age(contractId, TODAY);

		orchestrator.run(TODAY);

		verify(processor).age(contractId, TODAY);
		verify(jobRuns).complete(billingRun, 1, 0);
		verify(jobRuns).complete(agingRun, 0, 0);
	}

	@Test
	void aDeterministicAgingFailureIsNotRetried() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));
		when(contracts.isActive(contractId)).thenReturn(true);
		doThrow(new ContractStateException("active without schedule")).when(processor).age(contractId, TODAY);

		orchestrator.run(TODAY);

		verify(processor).age(contractId, TODAY);
		verify(jobRuns).complete(agingRun, 0, 1);
	}

	@Test
	void bindsSystemForAllWritesAndRestoresTheCallingActor() {
		UUID contractId = UUID.randomUUID();
		UUID caller = UUID.randomUUID();
		auditContext.setActor(caller);
		when(jobRuns.start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY)).thenAnswer(invocation -> {
			assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
			return billingRun;
		});
		stubActiveContracts(List.of(contractId));
		doAnswer(invocation -> {
			assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
			return null;
		}).when(processor).process(contractId, TODAY);
		doAnswer(invocation -> {
			assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
			return null;
		}).when(processor).age(contractId, TODAY);

		orchestrator.run(TODAY);

		assertThat(auditContext.actorId()).isEqualTo(caller);
		verify(processor).age(contractId, TODAY);
		verify(jobRuns).complete(billingRun, 1, 0);
		verify(jobRuns).complete(penaltyRun, 1, 0);
		verify(jobRuns).complete(agingRun, 1, 0);
	}

	@Test
	void rejectsAFutureBusinessDateBeforeWritingRunRows() {
		LocalDate future = TODAY.plusDays(1);

		assertThatThrownBy(() -> orchestrator.run(future))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("after today");

		verify(jobRuns, never()).start(anyString(), any());
		verify(jobRuns, never()).complete(any(), anyInt(), anyInt());
	}

	// --- CR-07 / CR-08 / CR-09 ------------------------------------------------------------------------

	@Test
	void rejectsANonPositiveBatchSize() {
		assertThatThrownBy(() -> new DailyServicingOrchestrator(
				contracts, processor, jobRuns, auditContext, clock, sleeper, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("batch-size");
	}

	@Test
	void backoffArrayHasExactlyMaxAttemptsMinusOneEntries() {
		assertThat(DailyServicingOrchestrator.BACKOFF_MILLIS)
				.hasSize(DailyServicingOrchestrator.MAX_ATTEMPTS - 1);
	}

	@Test
	void pausesOnTheDocumentedBackoffSequenceWhenARetryEventuallySucceeds() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));
		when(contracts.isActive(contractId)).thenReturn(true);
		// Fail retryably on attempts 1-4, succeed on attempt 5: four pauses are taken.
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.doNothing()
				.when(processor).process(contractId, TODAY);

		orchestrator.run(TODAY);

		assertThat(sleeper.pauses).containsExactly(50L, 150L, 400L, 1000L);
		verify(processor, times(5)).process(contractId, TODAY);
		verify(jobRuns).complete(billingRun, 1, 0);
	}

	@Test
	void pausesFourTimesWhenAllFiveAttemptsFail() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));
		when(contracts.isActive(contractId)).thenReturn(true);
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, contractId))
				.when(processor).process(contractId, TODAY);

		orchestrator.run(TODAY);

		// Pauses are taken after attempts 1-4 (guarded by attempt < MAX_ATTEMPTS); attempt 5 does not pause.
		assertThat(sleeper.pauses).containsExactly(50L, 150L, 400L, 1000L);
		verify(processor, times(5)).process(contractId, TODAY);
		verify(jobRuns).complete(billingRun, 0, 1);
	}

	@Test
	void doesNotPauseWhenNothingIsRetried() {
		UUID contractId = UUID.randomUUID();
		stubActiveContracts(List.of(contractId));

		orchestrator.run(TODAY);

		assertThat(sleeper.pauses).isEmpty();
	}

	@Test
	void abandonsStaleRunsBeforeStartingTheRunsRows() {
		stubActiveContracts(List.of());

		orchestrator.run(TODAY);

		InOrder order = inOrder(jobRuns);
		order.verify(jobRuns).abandonStaleRuns(List.of(
				DailyServicingOrchestrator.BILLING_JOB_NAME,
				DailyServicingOrchestrator.PENALTY_JOB_NAME,
				DailyServicingOrchestrator.AGING_JOB_NAME));
		order.verify(jobRuns).start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY);
	}

	@Test
	void iteratesContractsPageByPageAcrossBatches() {
		UUID a = new UUID(0L, 1L);
		UUID b = new UUID(0L, 2L);
		UUID c = new UUID(0L, 3L);
		DailyServicingOrchestrator paged = new DailyServicingOrchestrator(
				contracts, processor, jobRuns, auditContext, clock, sleeper, 2);
		when(contracts.findActiveContractIdsAfter(ZERO_UUID, 2)).thenReturn(List.of(a, b));
		when(contracts.findActiveContractIdsAfter(b, 2)).thenReturn(List.of(c));

		paged.run(TODAY);

		verify(processor).process(a, TODAY);
		verify(processor).process(b, TODAY);
		verify(processor).process(c, TODAY);
		// cursor advances by last id of each page: no page is read twice, none skipped.
		verify(contracts).findActiveContractIdsAfter(ZERO_UUID, 2);
		verify(contracts).findActiveContractIdsAfter(b, 2);
		verify(jobRuns).complete(billingRun, 3, 0);
		verify(jobRuns).complete(agingRun, 3, 0);
	}

	@Test
	void readsOneMorePageWhenTheLastPageIsExactlyFull() {
		UUID a = new UUID(0L, 1L);
		UUID b = new UUID(0L, 2L);
		DailyServicingOrchestrator paged = new DailyServicingOrchestrator(
				contracts, processor, jobRuns, auditContext, clock, sleeper, 2);
		when(contracts.findActiveContractIdsAfter(ZERO_UUID, 2)).thenReturn(List.of(a, b));
		when(contracts.findActiveContractIdsAfter(b, 2)).thenReturn(List.of());

		paged.run(TODAY);

		verify(contracts).findActiveContractIdsAfter(b, 2);
		verify(jobRuns).complete(billingRun, 2, 0);
	}

	@Test
	void finalizesAllThreeRowsAsFailedWhenAnInfraCallEscapesTheLoop() {
		UUID first = new UUID(0L, 1L);
		DailyServicingOrchestrator paged = new DailyServicingOrchestrator(
				contracts, processor, jobRuns, auditContext, clock, sleeper, 1);
		RuntimeException infraFailure = new RuntimeException("db down");
		when(contracts.findActiveContractIdsAfter(ZERO_UUID, 1)).thenReturn(List.of(first));
		when(contracts.findActiveContractIdsAfter(first, 1)).thenThrow(infraFailure);

		assertThatThrownBy(() -> paged.run(TODAY)).isSameAs(infraFailure);

		verify(jobRuns).failHard(eq(billingRun), anyInt(), anyInt());
		verify(jobRuns).failHard(eq(penaltyRun), anyInt(), anyInt());
		verify(jobRuns).failHard(eq(agingRun), anyInt(), anyInt());
		verify(jobRuns, never()).complete(any(), anyInt(), anyInt());
	}

	/** Fake {@link Sleeper} that records requested pause durations instead of blocking (CR-09). */
	private static final class RecordingSleeper implements Sleeper {

		private final List<Long> pauses = new ArrayList<>();

		@Override
		public void sleep(long millis) {
			pauses.add(millis);
		}
	}
}
