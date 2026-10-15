package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractCreditApplicationRepository;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.CreditResolvedInstallmentTotal;
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
	private final ContractCreditApplicationRepository creditApplications;

	public PaymentVoidService(ContractRepository contracts, InstallmentRepository installments,
			ContractCreditApplicationRepository creditApplications) {
		this.contracts = contracts;
		this.installments = installments;
		this.creditApplications = creditApplications;
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
		return new VoidEligibility(contract.getStatus(), contract.getClosedReason(), affected);
	}

	@Override
	public void applyVoidRecomputation(UUID contractId, Map<UUID, BigDecimal> recomputedPaidByInstallment,
			Map<UUID, OffsetDateTime> earliestResolvingByInstallment, OffsetDateTime voidedAt) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(recomputedPaidByInstallment, "recomputedPaidByInstallment");
		Objects.requireNonNull(earliestResolvingByInstallment, "earliestResolvingByInstallment");
		Objects.requireNonNull(voidedAt, "voidedAt");

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		Map<UUID, Installment> byId = new HashMap<>();
		for (Installment installment : schedule) {
			byId.put(installment.getId(), installment);
		}
		// Credit applied to these installments through contract_credit_application (task T14) also resolved
		// part of them and survives the void. Add its amount back (so the absolute recompute does not drop
		// surviving credit resolution and wrongly inflate the outstanding balance) and let its earliest
		// applied instant stand in for paid_at when no POSTED payment resolves the installment any more, so
		// ck_installment_paid_coherence stays satisfied (ADR-020 D1.3).
		Map<UUID, CreditResolvedInstallmentTotal> creditFunded =
				creditFundedByInstallment(recomputedPaidByInstallment.keySet());
		for (Map.Entry<UUID, BigDecimal> recomputed : recomputedPaidByInstallment.entrySet()) {
			UUID installmentId = recomputed.getKey();
			Installment installment = byId.get(installmentId);
			if (installment == null) {
				throw new IllegalStateException("installment " + installmentId
						+ " does not belong to contract " + contractId);
			}
			BigDecimal fromPayments = recomputed.getValue() == null ? ZERO : recomputed.getValue();
			CreditResolvedInstallmentTotal credit = creditFunded.get(installmentId);
			BigDecimal fromCredit = credit == null ? ZERO : credit.applied();
			OffsetDateTime resolvedAt = earliestResolvingInstant(
					earliestResolvingByInstallment.get(installmentId),
					credit == null ? null : credit.earliestAppliedAt());
			installment.resetPaidAmountForVoid(fromPayments.add(fromCredit), resolvedAt);
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

	/**
	 * Surviving customer credit applied to each of the given installments — amount and earliest instant
	 * (ADR-020 D1.3). A void never touches {@code contract_credit_application} rows, so every application
	 * still counts; this is the credit-funded portion of {@code paid_amount} the absolute recompute must
	 * preserve, with a {@code paid_at} candidate for an installment resolved only by credit.
	 */
	private Map<UUID, CreditResolvedInstallmentTotal> creditFundedByInstallment(Set<UUID> installmentIds) {
		Map<UUID, CreditResolvedInstallmentTotal> funded = new HashMap<>();
		if (installmentIds.isEmpty()) {
			return funded;
		}
		for (CreditResolvedInstallmentTotal total : creditApplications.sumAppliedByInstallmentIds(installmentIds)) {
			funded.put(total.installmentId(), total);
		}
		return funded;
	}

	/**
	 * The earlier of the two surviving resolving instants (a remaining POSTED payment's {@code paid_at} and
	 * the earliest surviving credit application's {@code applied_at}), or {@code null} when neither resolves
	 * the installment. Used as the installment's recomputed {@code paid_at} so it reflects the first thing
	 * that still resolves it after the void.
	 */
	private static OffsetDateTime earliestResolvingInstant(OffsetDateTime fromPayment, OffsetDateTime fromCredit) {
		if (fromPayment == null) {
			return fromCredit;
		}
		if (fromCredit == null) {
			return fromPayment;
		}
		return fromPayment.isBefore(fromCredit) ? fromPayment : fromCredit;
	}
}
