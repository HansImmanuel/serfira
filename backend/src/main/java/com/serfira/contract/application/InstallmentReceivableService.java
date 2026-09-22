package com.serfira.contract.application;

import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link InstallmentReceivablePort}: the {@code contract} module's answer to
 * &quot;what does this contract still owe, and record that money has arrived&quot; (ADR-010).
 *
 * <p>Deliberately not {@code @Transactional}: both methods run inside the caller's transaction (the
 * payment use case owns the boundary) and must see exactly the state the caller read. Starting a
 * transaction here would let a caller resolve money against a contract while its own write is still
 * uncommitted.
 *
 * <p>Applying a resolution also owns the <b>maturity close</b> (DM §3 invariant 17, ADR-011): when the
 * resolution leaves every installment paid, the module closes its own contract row as {@code MATURITY}.
 * That keeps the contract's state machine here instead of in the paying module, exactly like the
 * status derivation above.
 */
@Service
public class InstallmentReceivableService implements InstallmentReceivablePort {

	private static final BigDecimal NO_ADJUSTMENTS = BigDecimal.ZERO;

	private static final Logger LOGGER = LoggerFactory.getLogger(InstallmentReceivableService.class);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public InstallmentReceivableService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	public ContractReceivableSnapshot loadReceivableSnapshot(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		Contract contract = contracts.findById(contractId).orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and cannot receive money; only an ACTIVE contract can");
		}
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		if (schedule.isEmpty()) {
			// ACTIVE without a schedule is corrupt data (V4 keeps a draft coherent, not an active one).
			throw new ContractStateException("contract " + contract.getContractNo()
					+ " is ACTIVE but has no schedule, so it has no receivable to resolve");
		}
		Map<UUID, BigDecimal> adjustments = penaltyAdjustmentsByInstallment(schedule);

		List<InstallmentReceivable> receivables = schedule.stream()
				.map(installment -> new InstallmentReceivable(installment.getId(), installment.getPeriodNo(),
						installment.getDueDate(), installment.getPrincipalAmount(),
						installment.getRecognizedInterestAmount(), installment.getPenaltyAmount(),
						adjustments.getOrDefault(installment.getId(), NO_ADJUSTMENTS),
						installment.getSettledAmount(), installment.getWrittenOffAmount()))
				.toList();
		return new ContractReceivableSnapshot(contract.getId(), contract.getContractNo(), contract.getStatus(),
				receivables);
	}

	@Override
	public void applyPaymentResolution(UUID contractId, Map<UUID, BigDecimal> resolvedByInstallment,
			OffsetDateTime paidAt) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(resolvedByInstallment, "resolvedByInstallment");
		Objects.requireNonNull(paidAt, "paidAt");
		if (resolvedByInstallment.isEmpty()) {
			return;
		}

		Map<UUID, Installment> byId = new HashMap<>();
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		for (Installment installment : schedule) {
			byId.put(installment.getId(), installment);
		}
		for (Map.Entry<UUID, BigDecimal> resolution : resolvedByInstallment.entrySet()) {
			Installment installment = byId.get(resolution.getKey());
			if (installment == null) {
				// A caller resolved money against an installment outside this contract: a programming error,
				// never something to absorb silently.
				throw new IllegalStateException("installment " + resolution.getKey()
						+ " does not belong to contract " + contractId);
			}
			installment.applyPayment(resolution.getValue(), paidAt);
		}
		closeWhenEveryInstallmentIsPaid(contractId, schedule, paidAt);
		// Flush now (instead of at COMMIT) so a broken resolution or an incoherent close fails inside the
		// payment use case, with the payment and its allocations rolled back together.
		installments.flush();
	}

	/**
	 * Auto-closes the contract when this resolution left <b>every</b> installment {@code PAID} (DM §3
	 * invariant 17): the final regular payment ends the contract with {@code closed_reason=MATURITY}.
	 *
	 * <p>Deliberately status-based, not outstanding-based. A payment that resolved a due installment
	 * before its interest was billed (pre-C4 data) leaves an unrecognized remainder that the customer's
	 * overpayment covers as {@code TITIPAN_NASABAH} credit, and Addendum §13 explicitly blesses closing a
	 * matured contract while AVAILABLE credit stays a separate liability. {@code closed_at} is the
	 * resolving event's instant, so the close and the resolution share one business instant.
	 *
	 * <p>{@code SETTLED} / {@code WRITTEN_OFF} installments deliberately do <b>not</b> count here: those
	 * flows own their own closing reason ({@code SETTLEMENT} in E2), and this step must never rewrite
	 * their decision. A contract that is no longer ACTIVE is left untouched for the same reason.
	 */
	private void closeWhenEveryInstallmentIsPaid(UUID contractId, List<Installment> schedule,
			OffsetDateTime resolvedAt) {
		if (schedule.isEmpty() || !allPaid(schedule)) {
			return;
		}
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			return;
		}
		if (contract.close(ClosedReason.MATURITY, resolvedAt)) {
			LOGGER.info("Auto-closed contract {} as MATURITY (all {} installments paid)",
					contract.getContractNo(), schedule.size());
		}
	}

	private static boolean allPaid(List<Installment> schedule) {
		return schedule.stream().allMatch(installment -> installment.getStatus() == InstallmentStatus.PAID);
	}

	private Map<UUID, BigDecimal> penaltyAdjustmentsByInstallment(List<Installment> schedule) {
		Collection<UUID> installmentIds = schedule.stream().map(Installment::getId).toList();
		Map<UUID, BigDecimal> totals = new HashMap<>();
		for (Object[] row : installments.sumPenaltyAdjustmentsByInstallmentIds(installmentIds)) {
			totals.put((UUID) row[0], (BigDecimal) row[1]);
		}
		return totals;
	}
}
