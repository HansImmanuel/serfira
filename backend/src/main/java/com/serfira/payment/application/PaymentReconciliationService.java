package com.serfira.payment.application;

import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import com.serfira.payment.infrastructure.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * {@code payment}-module implementation of {@link PaymentReconciliationPort} (ADR-001): wraps the two
 * contract-scoped POSTED aggregates ({@link PaymentRepository#sumAmountByContractAndStatus} and
 * {@link PaymentAllocationRepository#sumAmountByContractAndStatus}) so the consistency check reads them
 * without touching {@code payment}/{@code payment_allocation}.
 *
 * <p>{@code MANDATORY} propagation, like the other cross-module read ports: it joins the consistency step's
 * read-only transaction and never opens one of its own.
 */
@Service
public class PaymentReconciliationService implements PaymentReconciliationPort {

	private final PaymentRepository payments;
	private final PaymentAllocationRepository allocations;

	public PaymentReconciliationService(PaymentRepository payments, PaymentAllocationRepository allocations) {
		this.payments = payments;
		this.allocations = allocations;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public PaymentReconciliationSnapshot loadReconciliationSnapshot(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		return new PaymentReconciliationSnapshot(
				payments.sumAmountByContractAndStatus(contractId, PaymentStatus.POSTED),
				allocations.sumAmountByContractAndStatus(contractId, PaymentStatus.POSTED));
	}
}
