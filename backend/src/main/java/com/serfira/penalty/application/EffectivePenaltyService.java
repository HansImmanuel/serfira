package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentPenalty;
import com.serfira.contract.application.InstallmentPenaltyPort;
import com.serfira.contract.application.InstallmentPenaltySnapshot;
import com.serfira.payment.application.PenaltyAllocationPort;
import com.serfira.penalty.infrastructure.InstallmentAdjustmentTotal;
import com.serfira.penalty.infrastructure.PenaltyAdjustmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link EffectivePenaltyPort} (ADR-019 D1): reads each installment's gross penalty
 * through the {@code contract} seam ({@link InstallmentPenaltyPort}), {@code Σ adjustment} from the
 * {@code penalty}-owned {@code penalty_adjustment} table, and {@code Σ} active PENALTY allocation through
 * the {@code payment} seam ({@link PenaltyAllocationPort}), and returns the clamped effective penalty.
 *
 * <p>Read-only: {@code @Transactional(readOnly = true)}. The clamp lives in the pure {@link #effective}
 * helper so the invariant-9 formula can be golden-tested without Spring (same convention as
 * {@code PenaltyCalculator}).
 */
@Service
public class EffectivePenaltyService implements EffectivePenaltyPort {

	/** Money is scale-2 ({@code NUMERIC(19,2)}, TS §2.1). */
	private static final int MONEY_SCALE = 2;

	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private final InstallmentPenaltyPort contracts;
	private final PenaltyAdjustmentRepository adjustments;
	private final PenaltyAllocationPort paidPenalties;

	public EffectivePenaltyService(InstallmentPenaltyPort contracts, PenaltyAdjustmentRepository adjustments,
			PenaltyAllocationPort paidPenalties) {
		this.contracts = contracts;
		this.adjustments = adjustments;
		this.paidPenalties = paidPenalties;
	}

	@Override
	@Transactional(readOnly = true)
	public EffectivePenaltySnapshot loadEffectivePenalty(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		return toSnapshot(contractId, contracts.loadPenaltySnapshot(contractId));
	}

	@Override
	@Transactional(readOnly = true)
	public EffectivePenaltySnapshot loadEffectivePenaltyAnyStatus(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		return toSnapshot(contractId, contracts.loadPenaltySnapshotAnyStatus(contractId));
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, InstallmentEffectivePenalty> loadEffectivePenalty(Collection<UUID> contractIds) {
		Objects.requireNonNull(contractIds, "contractIds");
		if (contractIds.isEmpty()) {
			return Map.of();
		}
		List<InstallmentPenaltySnapshot> snapshots = contracts.loadPenaltySnapshots(contractIds);
		List<InstallmentPenalty> allInstallments = snapshots.stream()
				.flatMap(snapshot -> snapshot.installments().stream())
				.toList();
		Map<UUID, InstallmentEffectivePenalty> byInstallment = new HashMap<>();
		for (InstallmentEffectivePenalty effective : effectivesOf(allInstallments)) {
			byInstallment.put(effective.installmentId(), effective);
		}
		return byInstallment;
	}

	/**
	 * Builds a single contract's snapshot from its gross penalty view, reusing {@link #effectivesOf} so the
	 * invariant-9 formula and the Σ adjustment / Σ paid penalty reads have one implementation shared by the
	 * single-contract, any-status and bulk port methods.
	 */
	private EffectivePenaltySnapshot toSnapshot(UUID contractId, InstallmentPenaltySnapshot snapshot) {
		return new EffectivePenaltySnapshot(contractId, effectivesOf(snapshot.installments()));
	}

	/**
	 * Computes the effective penalty of each given installment: one Σ adjustment query and one Σ paid
	 * penalty read across the whole set, then the clamped formula per installment. Order follows the input.
	 */
	private List<InstallmentEffectivePenalty> effectivesOf(List<InstallmentPenalty> installments) {
		List<UUID> installmentIds = installments.stream().map(InstallmentPenalty::installmentId).toList();

		Map<UUID, BigDecimal> adjustmentByInstallment = new HashMap<>();
		Map<UUID, BigDecimal> paidPenaltyByInstallment = new HashMap<>();
		if (!installmentIds.isEmpty()) {
			for (InstallmentAdjustmentTotal total : adjustments.sumAdjustmentsByInstallmentIds(installmentIds)) {
				adjustmentByInstallment.put(total.installmentId(), total.adjustment());
			}
			paidPenaltyByInstallment.putAll(paidPenalties.penaltyAllocationsByInstallment(installmentIds));
		}

		List<InstallmentEffectivePenalty> effectives = new ArrayList<>(installments.size());
		for (InstallmentPenalty installment : installments) {
			BigDecimal gross = normalize(installment.penaltyAmount());
			BigDecimal adjustment = normalize(adjustmentByInstallment.getOrDefault(installment.installmentId(),
					ZERO_MONEY));
			BigDecimal paidPenalty = normalize(paidPenaltyByInstallment.getOrDefault(installment.installmentId(),
					ZERO_MONEY));
			effectives.add(new InstallmentEffectivePenalty(installment.installmentId(), gross, adjustment,
					paidPenalty, effective(gross, adjustment, paidPenalty)));
		}
		return effectives;
	}

	/**
	 * The invariant-9 formula: {@code effective = max(0, grossAccrued − adjustment − paidPenalty)}, clamped
	 * so a waiver can never drive effective penalty negative (ADR-019 Context; the V3/V16 caps enforce the
	 * same bound in the DB). Pure and {@code static} so the golden/negative-guard unit test needs no Spring.
	 *
	 * @param grossAccrued gross cumulative recognized penalty, scale-2, {@code >= 0}
	 * @param adjustment   Σ waive/reduce for the installment, scale-2, {@code >= 0}
	 * @param paidPenalty  Σ active PENALTY payment allocation for the installment, scale-2, {@code >= 0}
	 * @return the clamped effective penalty, scale-2, {@code >= 0}
	 */
	public static BigDecimal effective(BigDecimal grossAccrued, BigDecimal adjustment, BigDecimal paidPenalty) {
		BigDecimal difference = grossAccrued.subtract(adjustment).subtract(paidPenalty);
		return difference.signum() < 0 ? ZERO_MONEY : difference.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	private static BigDecimal normalize(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
