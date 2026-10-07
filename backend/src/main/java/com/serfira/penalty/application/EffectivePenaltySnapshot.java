package com.serfira.penalty.application;

import java.util.List;
import java.util.UUID;

/**
 * A contract's effective penalty per installment (ADR-019 D1): the single application-level source of the
 * invariant-9 formula, consumed by {@code contract} totals, settlement and reporting instead of each
 * reading the {@code penalty}-owned {@code penalty_adjustment} table directly.
 *
 * @param contractId   contract the installments belong to
 * @param installments effective penalty per installment, in the schedule's period order
 */
public record EffectivePenaltySnapshot(UUID contractId, List<InstallmentEffectivePenalty> installments) {

	public EffectivePenaltySnapshot {
		installments = List.copyOf(installments);
	}
}
