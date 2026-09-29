package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentBillingPort;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyServicingContractProcessorTest {

	private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 3, 5);

	private final InstallmentBillingPort billing = mock(InstallmentBillingPort.class);
	private final PenaltyAccrualPort penalty = mock(PenaltyAccrualPort.class);
	private final DailyServicingContractProcessor processor = new DailyServicingContractProcessor(billing, penalty);

	@Test
	void billsBeforeAccruingPenalty() {
		UUID contractId = UUID.randomUUID();
		when(penalty.accrueDuePenalty(contractId, BUSINESS_DATE)).thenReturn(2);

		processor.process(contractId, BUSINESS_DATE);

		InOrder order = inOrder(billing, penalty);
		order.verify(billing).billDueInterest(contractId, BUSINESS_DATE);
		order.verify(penalty).accrueDuePenalty(contractId, BUSINESS_DATE);
	}

	@Test
	void neverCallsAccrualWhenBillingFails() {
		UUID contractId = UUID.randomUUID();
		RuntimeException billingFailure = new IllegalStateException("billing failed");
		org.mockito.Mockito.doThrow(billingFailure).when(billing).billDueInterest(contractId, BUSINESS_DATE);

		assertThatThrownBy(() -> processor.process(contractId, BUSINESS_DATE)).isSameAs(billingFailure);

		verify(penalty, never()).accrueDuePenalty(contractId, BUSINESS_DATE);
	}
}
