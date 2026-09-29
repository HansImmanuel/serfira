package com.serfira.penalty.application;

import com.serfira.contract.application.ActiveContractListingPort;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.shared.audit.AuditContext;
import com.serfira.shared.clock.FixedClock;
import com.serfira.shared.job.application.JobRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyServicingOrchestratorTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 3, 5);

	private final ActiveContractListingPort contracts = mock(ActiveContractListingPort.class);
	private final DailyServicingContractProcessor processor = mock(DailyServicingContractProcessor.class);
	private final JobRunService jobRuns = mock(JobRunService.class);
	private final AuditContext auditContext = new AuditContext();
	private final FixedClock clock = new FixedClock(TODAY);
	private final DailyServicingOrchestrator orchestrator = new DailyServicingOrchestrator(
			contracts, processor, jobRuns, auditContext, clock);

	@AfterEach
	void restoreSystemActor() {
		auditContext.resetToSystem();
	}

	@Test
	void retriesAtMostFiveTimesThenContinuesAndRecordsTheFailedContract() {
		UUID failedContract = UUID.randomUUID();
		UUID healthyContract = UUID.randomUUID();
		UUID billingRun = UUID.randomUUID();
		UUID penaltyRun = UUID.randomUUID();
		when(jobRuns.start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY)).thenReturn(billingRun);
		when(jobRuns.start(DailyServicingOrchestrator.PENALTY_JOB_NAME, TODAY)).thenReturn(penaltyRun);
		when(contracts.findActiveContractIds()).thenReturn(List.of(failedContract, healthyContract));
		when(contracts.isActive(failedContract)).thenReturn(true);
		doThrow(new ObjectOptimisticLockingFailureException(Object.class, failedContract))
				.when(processor).process(failedContract, TODAY);

		orchestrator.run(TODAY);

		verify(processor, org.mockito.Mockito.times(5)).process(failedContract, TODAY);
		verify(processor).process(healthyContract, TODAY);
		verify(jobRuns).complete(billingRun, 1, 1);
		verify(jobRuns).complete(penaltyRun, 1, 1);
	}

	@Test
	void deterministicFailureIsNotRetriedAndANonActiveRaceIsSkipped() {
		UUID deterministicFailure = UUID.randomUUID();
		UUID closedDuringRun = UUID.randomUUID();
		UUID billingRun = UUID.randomUUID();
		UUID penaltyRun = UUID.randomUUID();
		when(jobRuns.start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY)).thenReturn(billingRun);
		when(jobRuns.start(DailyServicingOrchestrator.PENALTY_JOB_NAME, TODAY)).thenReturn(penaltyRun);
		when(contracts.findActiveContractIds()).thenReturn(List.of(deterministicFailure, closedDuringRun));
		when(contracts.isActive(deterministicFailure)).thenReturn(true);
		when(contracts.isActive(closedDuringRun)).thenReturn(false);
		doThrow(new IllegalStateException("invalid data")).when(processor).process(deterministicFailure, TODAY);
		doThrow(new ContractStateException("no longer active")).when(processor).process(closedDuringRun, TODAY);

		orchestrator.run(TODAY);

		verify(processor).process(deterministicFailure, TODAY);
		verify(processor).process(closedDuringRun, TODAY);
		verify(jobRuns).complete(billingRun, 0, 1);
		verify(jobRuns).complete(penaltyRun, 0, 1);
	}

	@Test
	void bindsSystemForAllWritesAndRestoresTheCallingActor() {
		UUID contractId = UUID.randomUUID();
		UUID caller = UUID.randomUUID();
		UUID billingRun = UUID.randomUUID();
		UUID penaltyRun = UUID.randomUUID();
		auditContext.setActor(caller);
		when(jobRuns.start(DailyServicingOrchestrator.BILLING_JOB_NAME, TODAY)).thenAnswer(invocation -> {
			assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
			return billingRun;
		});
		when(jobRuns.start(DailyServicingOrchestrator.PENALTY_JOB_NAME, TODAY)).thenReturn(penaltyRun);
		when(contracts.findActiveContractIds()).thenReturn(List.of(contractId));
		doAnswer(invocation -> {
			assertThat(auditContext.actorId()).isEqualTo(AuditContext.SYSTEM_USER_ID);
			return null;
		}).when(processor).process(contractId, TODAY);

		orchestrator.run(TODAY);

		assertThat(auditContext.actorId()).isEqualTo(caller);
		verify(jobRuns).complete(billingRun, 1, 0);
		verify(jobRuns).complete(penaltyRun, 1, 0);
	}

	@Test
	void rejectsAFutureBusinessDateBeforeWritingRunRows() {
		LocalDate future = TODAY.plusDays(1);

		assertThatThrownBy(() -> orchestrator.run(future))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("after today");

		verify(jobRuns, never()).start(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
		verify(jobRuns, never()).complete(org.mockito.ArgumentMatchers.any(), anyInt(), anyInt());
	}
}
