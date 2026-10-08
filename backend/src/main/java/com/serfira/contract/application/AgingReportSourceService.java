package com.serfira.contract.application;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentAging;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@code contract}-module implementation of {@link AgingReportSourcePort}: reads every ACTIVE contract's
 * schedule and emits a snapshot per installment that still owes money, with its days-past-due and
 * outstanding computed by the module's own domain rules ({@link InstallmentAging},
 * {@link InstallmentBalance}).
 *
 * <p>Read-only: the report never writes. One query fetches all ACTIVE installments with their contract, so
 * DPD (needs {@code grace_period_days}) and the per-contract row (needs {@code contract_no}) are available
 * without a lazy walk. Keyset batching is deliberately out of scope here — it is a daily-job concern (T26);
 * the report is a bounded read served from one snapshot.
 *
 * <p>{@code outstanding} is netted against active penalty adjustments (ADR-019 D4): the Σ waive/reduce per
 * installment comes from the {@code penalty}-owned {@link EffectivePenaltyPort} (edge {@code contract →
 * penalty}, 02_TECH_SPEC.md §1) in one batch call for all ACTIVE contracts (ADR-019 F3). A
 * fully-waived-but-otherwise-paid installment therefore nets to zero outstanding and no longer lands in an
 * aging bucket, agreeing with the payment-receivable snapshot instead of reporting gross penalty.
 */
@Service
@Transactional(readOnly = true)
public class AgingReportSourceService implements AgingReportSourcePort {

	private static final Logger LOGGER = LoggerFactory.getLogger(AgingReportSourceService.class);

	private final InstallmentRepository installments;
	private final EffectivePenaltyPort effectivePenalty;

	public AgingReportSourceService(InstallmentRepository installments, EffectivePenaltyPort effectivePenalty) {
		this.installments = installments;
		this.effectivePenalty = effectivePenalty;
	}

	@Override
	public List<InstallmentAgingSnapshot> findOutstandingInstallments(LocalDate asOf) {
		Objects.requireNonNull(asOf, "asOf");
		List<Installment> active = installments.findActiveContractInstallments();
		Map<UUID, BigDecimal> adjustments = penaltyAdjustmentsByInstallment(active);
		List<InstallmentAgingSnapshot> snapshots = new ArrayList<>(active.size());
		for (Installment installment : active) {
			BigDecimal adjustment = adjustments.getOrDefault(installment.getId(), BigDecimal.ZERO);
			BigDecimal outstanding = InstallmentBalance.of(installment, adjustment).outstanding();
			if (outstanding.signum() == 0) {
				continue;
			}
			if (outstanding.signum() < 0) {
				// A negative outstanding means an over-resolved installment, i.e. corrupted data that the
				// DB-level invariant (V3 trg_installment_amounts_deferred) should have blocked. InstallmentBalance
				// keeps it visible on purpose, so the aging report must not silently drop it: it cannot sit in a
				// bucket (a bucket amount is > 0), but we log it with safe identifiers (never PII) so it is
				// traceable, matching the consistency job's "report, do not hide" stance (story L-3).
				LOGGER.warn("Skipping installment {} of contract {} from aging: outstanding is negative ({}), "
						+ "which indicates corrupted data", installment.getId(), installment.getContract().getId(),
						outstanding);
				continue;
			}
			int daysPastDue = InstallmentAging.daysPastDue(installment.getDueDate(),
					installment.getContract().getGracePeriodDays(), asOf);
			snapshots.add(new InstallmentAgingSnapshot(installment.getContract().getId(),
					installment.getContract().getContractNo(), daysPastDue, outstanding));
		}
		return List.copyOf(snapshots);
	}

	/**
	 * Σ penalty adjustments per installment across the given ACTIVE installments, sourced through the
	 * {@code penalty}-owned {@link EffectivePenaltyPort} (ADR-019 D1/D4, edge {@code contract → penalty}) in
	 * ONE batch call (ADR-019 F3) rather than once per contract: the bulk method resolves the effective
	 * penalty of every ACTIVE contract at once. Only {@code adjustment} is needed here — {@code outstanding}
	 * nets it through {@link InstallmentBalance#of(Installment, BigDecimal)}, and the paid penalty is already
	 * in {@code paidAmount}, so subtracting it again would double-count.
	 */
	private Map<UUID, BigDecimal> penaltyAdjustmentsByInstallment(List<Installment> active) {
		Set<UUID> contractIds = active.stream()
				.map(installment -> installment.getContract().getId())
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, BigDecimal> totals = new HashMap<>();
		for (InstallmentEffectivePenalty effective : effectivePenalty.loadEffectivePenalty(contractIds).values()) {
			totals.put(effective.installmentId(), effective.adjustment());
		}
		return totals;
	}
}
