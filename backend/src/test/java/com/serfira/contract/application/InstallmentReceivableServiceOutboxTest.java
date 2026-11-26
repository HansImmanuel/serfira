package com.serfira.contract.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.serfira.contract.application.event.ContractClosedPayload;
import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.shared.outbox.OutboxAggregateType;
import com.serfira.shared.outbox.OutboxEventType;
import com.serfira.shared.outbox.OutboxPayload;
import com.serfira.shared.outbox.OutboxWriter;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * T38 / ADR-027: the MATURITY close has one site, so {@code ContractClosed} has one emission point. Payment,
 * credit application and a penalty waiver that clears the last amount ({@code InstallmentStatusRecomputeService})
 * all reach it through {@code closeWhenEveryInstallmentIsPaid}.
 */
class InstallmentReceivableServiceOutboxTest {

	private static final OffsetDateTime RESOLVED_AT = OffsetDateTime.parse("2026-02-28T10:00:00+07:00");

	private final ContractRepository contracts = mock(ContractRepository.class);
	private final OutboxWriter outbox = mock(OutboxWriter.class);
	private final InstallmentReceivableService service = new InstallmentReceivableService(contracts,
			mock(InstallmentRepository.class), mock(EffectivePenaltyPort.class), outbox);

	private final UUID contractId = UUID.randomUUID();
	private final Contract contract = mock(Contract.class);

	@Test
	void closingTheLastPaidInstallmentWritesContractClosed() {
		givenContract(true, true);

		service.closeWhenEveryInstallmentIsPaid(contractId, List.of(installment(InstallmentStatus.PAID)), RESOLVED_AT);

		ArgumentCaptor<OutboxPayload> payload = ArgumentCaptor.forClass(OutboxPayload.class);
		verify(outbox).append(eq(OutboxAggregateType.CONTRACT), eq(contractId), eq(OutboxEventType.ContractClosed),
				payload.capture());
		assertThat(payload.getValue()).isEqualTo(new ContractClosedPayload(OutboxPayload.SCHEMA_VERSION, contractId,
				"MF-202602-0001", ClosedReason.MATURITY, RESOLVED_AT));
	}

	@Test
	void anAlreadyClosedContractWritesNothing() {
		givenContract(false, false);

		service.closeWhenEveryInstallmentIsPaid(contractId, List.of(installment(InstallmentStatus.PAID)), RESOLVED_AT);

		verifyNoInteractions(outbox);
	}

	@Test
	void aCloseThatDoesNotTransitionWritesNothing() {
		givenContract(true, false);

		service.closeWhenEveryInstallmentIsPaid(contractId, List.of(installment(InstallmentStatus.PAID)), RESOLVED_AT);

		verifyNoInteractions(outbox);
	}

	@Test
	void anUnpaidScheduleWritesNothing() {
		givenContract(true, true);

		service.closeWhenEveryInstallmentIsPaid(contractId,
				List.of(installment(InstallmentStatus.PAID), installment(InstallmentStatus.PARTIALLY_PAID)),
				RESOLVED_AT);

		verifyNoInteractions(outbox);
	}

	private void givenContract(boolean active, boolean closes) {
		when(contracts.findById(contractId)).thenReturn(Optional.of(contract));
		when(contract.isActive()).thenReturn(active);
		when(contract.close(any(), any())).thenReturn(closes);
		when(contract.getId()).thenReturn(contractId);
		when(contract.getContractNo()).thenReturn("MF-202602-0001");
		when(contract.getClosedReason()).thenReturn(ClosedReason.MATURITY);
		when(contract.getClosedAt()).thenReturn(RESOLVED_AT);
	}

	private static Installment installment(InstallmentStatus status) {
		Installment installment = mock(Installment.class);
		when(installment.getStatus()).thenReturn(status);
		return installment;
	}
}
