package com.serfira.payment.application;

import com.serfira.contract.application.ContractLookupPort;
import com.serfira.payment.api.ContractPaymentResponse;
import com.serfira.payment.domain.Payment;
import com.serfira.payment.domain.PaymentAllocation;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import com.serfira.payment.infrastructure.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The payment history of one contract (task T39 Decisions 1, 3, 4): every payment, POSTED and VOIDED,
 * newest first, with its allocations loaded in a single {@code IN} query (no N+1).
 */
@Service
public class PaymentHistoryQueryService {

	private final ContractLookupPort contracts;
	private final PaymentRepository payments;
	private final PaymentAllocationRepository allocations;

	public PaymentHistoryQueryService(ContractLookupPort contracts, PaymentRepository payments,
			PaymentAllocationRepository allocations) {
		this.contracts = contracts;
		this.payments = payments;
		this.allocations = allocations;
	}

	/**
	 * @throws com.serfira.contract.application.ContractNotFoundException for an unknown contract (404), so a
	 *         missing contract is never mistaken for one without payments
	 */
	@Transactional(readOnly = true)
	public List<ContractPaymentResponse> forContract(UUID contractId) {
		contracts.requireExists(contractId);
		List<Payment> history = payments.findByContractIdOrderByPaidAtDescPaymentNoDesc(contractId);
		if (history.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<PaymentAllocation>> byPayment = allocations
				.findByPaymentIdIn(history.stream().map(Payment::getId).toList()).stream()
				.collect(Collectors.groupingBy(allocation -> allocation.getPayment().getId()));
		return history.stream()
				.map(payment -> ContractPaymentResponse.from(payment,
						byPayment.getOrDefault(payment.getId(), List.of())))
				.toList();
	}
}
