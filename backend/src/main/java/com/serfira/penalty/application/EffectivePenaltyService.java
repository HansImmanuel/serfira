package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentPenalty;
import com.serfira.contract.application.InstallmentPenaltyPort;
import com.serfira.contract.application.InstallmentPenaltySnapshot;
import com.serfira.penalty.infrastructure.InstallmentAdjustmentTotal;
import com.serfira.penalty.infrastructure.PenaltyAdjustmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link EffectivePenaltyPort} (ADR-019 D1): reads each installment's gross penalty
 * through the {@code contract} seam ({@link InstallmentPenaltyPort}) and {@code Σ adjustment} from the
 * {@code penalty}-owned {@code penalty_adjustment} table, and returns the clamped effective penalty.
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

	public EffectivePenaltyService(InstallmentPenaltyPort contracts, PenaltyAdjustmentRepository adjustments) {
		this.contracts = contracts;
		this.adjustments = adjustments;
	}

	@Override
	@Transactional(readOnly = true)
	public EffectivePenaltySnapshot loadEffectivePenalty(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		InstallmentPenaltySnapshot snapshot = contracts.loadPenaltySnapshot(contractId);
		List<UUID> installmentIds = snapshot.installments().stream().map(InstallmentPenalty::installmentId).toList();

		Map<UUID, BigDecimal> adjustmentByInstallment = new HashMap<>();
		if (!installmentIds.isEmpty()) {
			for (InstallmentAdjustmentTotal total : adjustments.sumAdjustmentsByInstallmentIds(installmentIds)) {
				adjustmentByInstallment.put(total.installmentId(), total.adjustment());
			}
		}

		List<InstallmentEffectivePenalty> effectives = new ArrayList<>(snapshot.installments().size());
		for (InstallmentPenalty installment : snapshot.installments()) {
			BigDecimal gross = normalize(installment.penaltyAmount());
			BigDecimal adjustment = normalize(adjustmentByInstallment.getOrDefault(installment.installmentId(),
					ZERO_MONEY));
			effectives.add(new InstallmentEffectivePenalty(installment.installmentId(), gross, adjustment,
					effective(gross, adjustment)));
		}
		return new EffectivePenaltySnapshot(contractId, effectives);
	}

	/**
	 * The invariant-9 formula: {@code effective = max(0, grossAccrued − adjustment)}, clamped so a waiver can
	 * never drive effective penalty negative (ADR-019 D1; the V3/V15 caps enforce the same bound in the DB).
	 * Pure and {@code static} so the golden/negative-guard unit test needs no Spring.
	 *
	 * @param grossAccrued gross cumulative recognized penalty, scale-2, {@code >= 0}
	 * @param adjustment   Σ waive/reduce for the installment, scale-2, {@code >= 0}
	 * @return the clamped effective penalty, scale-2, {@code >= 0}
	 */
	public static BigDecimal effective(BigDecimal grossAccrued, BigDecimal adjustment) {
		BigDecimal difference = grossAccrued.subtract(adjustment);
		return difference.signum() < 0 ? ZERO_MONEY : difference.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	private static BigDecimal normalize(BigDecimal value) {
		return value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
