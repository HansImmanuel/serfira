package com.serfira.contract.application;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.penalty.application.EffectivePenaltyPort;
import com.serfira.penalty.application.InstallmentEffectivePenalty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code contract}-module implementation of {@link ReceivableReconciliationPort} (ADR-001): sums the
 * contract's installment outstanding, netting active penalty adjustments through the {@code penalty}-owned
 * {@link EffectivePenaltyPort} — the same single "outstanding" formula ({@link InstallmentBalance}) the
 * receivable snapshot (T15) and the aging report (T30) use, so the consistency check compares the same
 * definition the write paths produce (ADR-019 D4).
 *
 * <p>{@code MANDATORY} propagation: it joins the consistency step's read-only transaction. The
 * {@link EffectivePenaltyPort#loadEffectivePenalty(UUID)} call rejects a non-ACTIVE or schedule-less
 * contract with {@code ContractStateException} (409), which the daily orchestrator treats as "skip, no
 * longer ACTIVE" rather than a failure — the same handling every per-contract step gets.
 */
@Service
public class ReceivableReconciliationService implements ReceivableReconciliationPort {

	private final InstallmentRepository installments;
	private final EffectivePenaltyPort effectivePenalty;

	public ReceivableReconciliationService(InstallmentRepository installments,
			EffectivePenaltyPort effectivePenalty) {
		this.installments = installments;
		this.effectivePenalty = effectivePenalty;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public BigDecimal receivableResidual(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		// Rejects a non-ACTIVE / schedule-less contract (409), the orchestrator's skip signal.
		Map<UUID, BigDecimal> adjustments = adjustmentsByInstallment(contractId);
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		BigDecimal residual = BigDecimal.ZERO;
		for (Installment installment : schedule) {
			BigDecimal adjustment = adjustments.getOrDefault(installment.getId(), BigDecimal.ZERO);
			// Deliberately not clamped: a negative outstanding is corrupted data the consistency check must
			// still see (Addendum §7.3 "report, do not fix"), so the raw residual flows into the sum.
			residual = residual.add(InstallmentBalance.of(installment, adjustment).outstanding());
		}
		return residual;
	}

	/**
	 * Per-installment {@code Σ} active penalty adjustment for the contract, read once through the
	 * {@code penalty}-owned port — the same source the aging report uses, so there is no second adjustment
	 * query in {@code contract} (ADR-019 D4).
	 */
	private Map<UUID, BigDecimal> adjustmentsByInstallment(UUID contractId) {
		Map<UUID, BigDecimal> totals = new HashMap<>();
		for (InstallmentEffectivePenalty effective : effectivePenalty.loadEffectivePenalty(contractId).installments()) {
			totals.put(effective.installmentId(), effective.adjustment());
		}
		return totals;
	}
}
