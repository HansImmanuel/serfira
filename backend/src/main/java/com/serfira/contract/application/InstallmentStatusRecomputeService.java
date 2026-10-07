package com.serfira.contract.application;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import com.serfira.shared.clock.Clock;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link InstallmentStatusRecomputePort}: the {@code contract} module re-derives one
 * installment's resolution status after a penalty waiver and runs the maturity close (E5, task T15,
 * ADR-019 D3, DM §3 invariant 17).
 *
 * <p>Deliberately not {@code @Transactional} (mirrors {@link InstallmentReceivableService} and
 * {@link InstallmentPenaltyService}): the method joins the waiver use case's transaction so the versioned
 * installment write, the contract close, and the waiver's own adjustment row + journal all commit or roll
 * back together. Starting a transaction here would let the recompute see state the caller has not committed.
 *
 * <p>The Σ adjustment is read through the {@code penalty}-owned {@link EffectivePenaltyPort}, the identical
 * source the F2 cap uses, so the recompute's remaining-penalty view can never disagree with the cap. The
 * close reuses {@link InstallmentReceivableService#closeWhenEveryInstallmentIsPaid} so the MATURITY rule
 * stays defined in exactly one place.
 *
 * <p><b>Concurrency (F4).</b> Every waiver forces an optimistic version increment on the target installment
 * ({@link LockModeType#OPTIMISTIC_FORCE_INCREMENT}), <em>regardless of whether the status changed</em>. That
 * is what serializes concurrent waivers on the same installment: two writers that each pass the in-memory
 * cap pre-check (neither sees the other's uncommitted row under READ COMMITTED, and the DEFERRABLE V16
 * trigger does not take a row lock) both schedule a version UPDATE against the same version, so the second
 * to commit fails with an {@code OptimisticLockException} and {@code PenaltyAdjustmentRetryingService}
 * retries it against the now-committed state, where the cap correctly rejects the over-remaining waiver. The
 * status recompute itself only moves a non-terminal status and never re-stamps {@code paid_at}.
 */
@Service
public class InstallmentStatusRecomputeService implements InstallmentStatusRecomputePort {

	private static final BigDecimal NO_ADJUSTMENTS = BigDecimal.ZERO;

	private static final Logger LOGGER = LoggerFactory.getLogger(InstallmentStatusRecomputeService.class);

	private final InstallmentRepository installments;
	private final EffectivePenaltyPort effectivePenalty;
	private final InstallmentReceivableService receivables;
	private final Clock clock;

	@PersistenceContext
	private EntityManager entityManager;

	public InstallmentStatusRecomputeService(InstallmentRepository installments,
			EffectivePenaltyPort effectivePenalty, InstallmentReceivableService receivables, Clock clock) {
		this.installments = installments;
		this.effectivePenalty = effectivePenalty;
		this.receivables = receivables;
		this.clock = clock;
	}

	@Override
	public void recomputeAfterPenaltyAdjustment(UUID contractId, UUID installmentId) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(installmentId, "installmentId");

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		Installment target = schedule.stream()
				.filter(installment -> installment.getId().equals(installmentId))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("installment " + installmentId
						+ " does not belong to contract " + contractId));

		// Force a version bump even when the status does not change, so two concurrent waivers on the same
		// installment collide on @Version and exactly one commits (F4). The DEFERRABLE V16 cap trigger does
		// not serialize writers under READ COMMITTED, so this optimistic guard is the real serialization.
		entityManager.lock(target, LockModeType.OPTIMISTIC_FORCE_INCREMENT);

		Map<UUID, BigDecimal> adjustments = penaltyAdjustmentsByInstallment(contractId);
		boolean changed = target.recomputeResolutionStatus(
				adjustments.getOrDefault(installmentId, NO_ADJUSTMENTS));
		if (changed) {
			LOGGER.info("Recomputed installment {} to {} after a penalty adjustment", installmentId,
					target.getStatus());
			OffsetDateTime resolvedAt = clock.now();
			receivables.closeWhenEveryInstallmentIsPaid(contractId, schedule, resolvedAt);
		}
		// Deliberately no flush here: the caller (PenaltyAdjustmentService.adjust) flushes its whole
		// persistence context — which includes this installment's forced version increment, the status write
		// and any contract close — right after this call, so everything still surfaces inside the waiver use
		// case. Not holding the installment's row lock until that single flush is what keeps two concurrent
		// waivers from deadlocking: each schedules its UPDATE, and the second to flush/commit loses the
		// @Version race cleanly instead of blocking on the other's row lock (F4).
	}

	/**
	 * Σ penalty adjustments per installment, sourced through the {@code penalty}-owned
	 * {@link EffectivePenaltyPort} (ADR-019 D1, the same source as the F2 cap), so the status recompute and
	 * the cap can never disagree on the remaining penalty.
	 */
	private Map<UUID, BigDecimal> penaltyAdjustmentsByInstallment(UUID contractId) {
		Map<UUID, BigDecimal> totals = new HashMap<>();
		for (InstallmentEffectivePenalty installment : effectivePenalty.loadEffectivePenalty(contractId).installments()) {
			totals.put(installment.installmentId(), installment.adjustment());
		}
		return totals;
	}
}
