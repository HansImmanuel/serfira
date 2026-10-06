package com.serfira.payment.application;

import com.serfira.payment.domain.AllocationType;
import com.serfira.payment.domain.PaymentStatus;
import com.serfira.payment.infrastructure.ActiveAllocationTotal;
import com.serfira.payment.infrastructure.PaymentAllocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link PenaltyAllocationPort} (ADR-001, ADR-019): wraps the existing
 * {@link PaymentAllocationRepository#sumActiveByInstallmentIds(PaymentStatus, Collection)} aggregate,
 * keeping only {@link AllocationType#PENALTY} rows, so {@code penalty} can read the paid-penalty term
 * without ever touching {@code payment_allocation}.
 *
 * <p>Reads POSTED allocations only (the active view); see {@link PenaltyAllocationPort} for the one-line
 * POSTED-vs-all divergence from the V3/V16 DB caps, benign until payment void (T16) exists.
 */
@Service
public class PenaltyAllocationService implements PenaltyAllocationPort {

	private final PaymentAllocationRepository allocations;

	public PenaltyAllocationService(PaymentAllocationRepository allocations) {
		this.allocations = allocations;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public Map<UUID, BigDecimal> penaltyAllocationsByInstallment(Collection<UUID> installmentIds) {
		Objects.requireNonNull(installmentIds, "installmentIds");
		if (installmentIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, BigDecimal> penaltyByInstallment = new HashMap<>();
		for (ActiveAllocationTotal total : allocations.sumActiveByInstallmentIds(PaymentStatus.POSTED, installmentIds)) {
			if (total.type() == AllocationType.PENALTY) {
				penaltyByInstallment.put(total.installmentId(), total.amount());
			}
		}
		return penaltyByInstallment;
	}
}
