package com.serfira.contract.application;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * {@code contract}-module implementation of {@link CollectionCohortSourcePort} (T36, ADR-026 D5): one bounded
 * range read on {@code installment.due_date}, then the recognized total of each installment through
 * {@link InstallmentBalance}, with penalty netted against active adjustments (ADR-019 D4).
 *
 * <p>Adjustments are read through the {@code penalty}-owned {@link EffectivePenaltyPort} in one batch, and only
 * for the contracts whose cohort installments carry a penalty at all: an adjustment can never exceed the gross
 * penalty (invariant 9, V16), so an installment without penalty has no adjustment to net.
 */
@Service
@Transactional(readOnly = true)
public class CollectionCohortSourceService implements CollectionCohortSourcePort {

	private final InstallmentRepository installments;
	private final EffectivePenaltyPort effectivePenalty;

	public CollectionCohortSourceService(InstallmentRepository installments, EffectivePenaltyPort effectivePenalty) {
		this.installments = installments;
		this.effectivePenalty = effectivePenalty;
	}

	@Override
	public List<CohortReceivableSnapshot> findCohort(LocalDate from, LocalDate to) {
		Objects.requireNonNull(from, "from");
		Objects.requireNonNull(to, "to");
		if (to.isBefore(from)) {
			throw new IllegalArgumentException("to (" + to + ") must not be before from (" + from + ")");
		}
		List<Installment> cohort = installments.findDueBetweenOnNonDraftContracts(from, to);
		Map<UUID, InstallmentEffectivePenalty> penalties = effectivePenalty.loadEffectivePenalty(
				contractsWithPenalty(cohort));
		List<CohortReceivableSnapshot> snapshots = new ArrayList<>(cohort.size());
		for (Installment installment : cohort) {
			InstallmentEffectivePenalty penalty = penalties.get(installment.getId());
			BigDecimal adjustment = penalty == null ? BigDecimal.ZERO : penalty.adjustment();
			snapshots.add(new CohortReceivableSnapshot(installment.getId(),
					InstallmentBalance.of(installment, adjustment).recognizedTotal(),
					installment.getSettledAmount()));
		}
		return List.copyOf(snapshots);
	}

	private static Set<UUID> contractsWithPenalty(List<Installment> cohort) {
		Set<UUID> contractIds = new LinkedHashSet<>();
		for (Installment installment : cohort) {
			if (installment.getPenaltyAmount().signum() > 0) {
				contractIds.add(installment.getContract().getId());
			}
		}
		return contractIds;
	}
}
