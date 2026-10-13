package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link PaymentVoidPort}: the {@code contract} module's half of a payment void (E4,
 * task T16, ADR-020). It reads the eligibility snapshot the void guard needs (D5) and applies the
 * installment recomputation + contract reopen the void performs (D1.3).
 *
 * <p>Deliberately not {@code @Transactional} (mirrors {@link InstallmentReceivableService} and
 * {@link InstallmentStatusRecomputeService}): both methods join the void use case's transaction so the
 * versioned installment and contract writes commit or roll back together with {@code payment}'s reversal
 * journal and status flip. Starting a transaction here would let the recompute see state the caller has
 * not committed.
 *
 * <p><b>Why absolute amounts and a reopen, not {@link InstallmentReceivablePort#applyPaymentResolution}.</b>
 * That mutator only ever <i>raises</i> {@code paid_amount} and only ever <i>closes</i> a contract. A void
 * lowers resolution and may undo a {@code MATURITY} auto-close, so it needs the demotion-capable
 * {@link Installment#resetPaidAmountForVoid} and {@link Contract#reopenFromMaturity}. The recompute is
 * driven by absolute totals the caller derives from the <b>remaining</b> POSTED payments, so there is one
 * authoritative paid amount per installment rather than a fragile sequence of deltas.
 */
@Service
public class PaymentVoidService implements PaymentVoidPort {

	private static final BigDecimal ZERO = BigDecimal.ZERO;

	private static final Logger LOGGER = LoggerFactory.getLogger(PaymentVoidService.class);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public PaymentVoidService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	public VoidEligibility checkVoidEligibility(UUID contractId, Set<UUID> installmentIds) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(installmentIds, "installmentIds");
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		List<VoidAffectedInstallment> affected = schedule.stream()
				.filter(installment -> installmentIds.contains(installment.getId()))
				.map(installment -> new VoidAffectedInstallment(installment.getId(), installment.getStatus()))
				.toList();
		return new VoidEligibility(contract.getStatus(), affected);
	}

	@Override
	public void applyVoidRecomputation(UUID contractId, Map<UUID, BigDecimal> recomputedPaidByInstallment,
			OffsetDateTime voidedAt) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(recomputedPaidByInstallment, "recomputedPaidByInstallment");
		Objects.requireNonNull(voidedAt, "voidedAt");

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		Map<UUID, Installment> byId = new HashMap<>();
		for (Installment installment : schedule) {
			byId.put(installment.getId(), installment);
		}
		for (Map.Entry<UUID, BigDecimal> recomputed : recomputedPaidByInstallment.entrySet()) {
			Installment installment = byId.get(recomputed.getKey());
			if (installment == null) {
				throw new IllegalStateException("installment " + recomputed.getKey()
						+ " does not belong to contract " + contractId);
			}
			installment.resetPaidAmountForVoid(recomputed.getValue() == null ? ZERO : recomputed.getValue());
		}
		reopenIfNoLongerMatured(contractId, schedule);
		// Flush now (as InstallmentReceivableService does) so a broken recomputation or an incoherent reopen
		// fails inside the void use case, rolling back the payment status flip and the reversal journal too.
		installments.flush();
	}

	/**
	 * Reverses the {@code MATURITY} auto-close (invariant 17) when the void demoted any installment below
	 * {@code PAID}: a contract that was matured by the now-voided final payment returns to {@code ACTIVE}.
	 * A contract that is still fully paid (the void only reduced an over-resolved installment that stayed
	 * {@code PAID}) is left closed. The reopen is a no-op on an already-{@code ACTIVE} contract.
	 */
	private void reopenIfNoLongerMatured(UUID contractId, List<Installment> schedule) {
		if (schedule.isEmpty() || allPaid(schedule)) {
			return;
		}
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		if (contract.isActive()) {
			return;
		}
		if (contract.reopenFromMaturity()) {
			LOGGER.info("Reopened contract {} to ACTIVE after a payment void undid its MATURITY close",
					contract.getContractNo());
		}
	}

	private static boolean allPaid(List<Installment> schedule) {
		return schedule.stream().allMatch(installment -> installment.getStatus() == InstallmentStatus.PAID);
	}
}
