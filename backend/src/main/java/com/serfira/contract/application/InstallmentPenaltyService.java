package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link InstallmentPenaltyPort}: the {@code contract} module's answer to "what does this
 * contract still owe in pokok+bunga, and record that its penalty grew" (ADR-012).
 *
 * <p>Deliberately not {@code @Transactional}: both methods run inside the caller's transaction (the penalty
 * module's accrual step owns the boundary) and must see exactly the state the caller read. Starting a
 * transaction here would let the penalty module write accrual rows against an installment state that is
 * still uncommitted.
 *
 * <p>The penalty base is derived here, next to the receivable it belongs to
 * ({@link InstallmentBalance#penaltyBase(Installment)}), so there is exactly one definition of it — the same
 * reason {@link InstallmentReceivableService} exposes raw columns rather than a second derived copy.
 */
@Service
public class InstallmentPenaltyService implements InstallmentPenaltyPort {

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public InstallmentPenaltyService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	public InstallmentPenaltySnapshot loadPenaltySnapshot(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		Contract contract = contracts.findById(contractId).orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and accrues no penalty; only an ACTIVE contract does");
		}
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		if (schedule.isEmpty()) {
			// ACTIVE without a schedule is corrupt data (V4 keeps a draft coherent, not an active one).
			throw new ContractStateException("contract " + contract.getContractNo()
					+ " is ACTIVE but has no schedule, so it has no penalty to accrue");
		}
		return toSnapshot(contract, schedule);
	}

	@Override
	public InstallmentPenaltySnapshot loadPenaltySnapshotAnyStatus(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		Contract contract = contracts.findById(contractId).orElseThrow(() -> new ContractNotFoundException(contractId));
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		if (schedule.isEmpty()) {
			// Reading effective penalty of a contract with no schedule is a caller bug: a DRAFT is branched
			// out before this call, and any contract with a schedule keeps it after close.
			throw new ContractStateException("contract " + contract.getContractNo()
					+ " has no schedule, so it has no penalty to report");
		}
		return toSnapshot(contract, schedule);
	}

	@Override
	public List<InstallmentPenaltySnapshot> loadPenaltySnapshots(Collection<UUID> contractIds) {
		Objects.requireNonNull(contractIds, "contractIds");
		if (contractIds.isEmpty()) {
			return List.of();
		}
		Map<UUID, Contract> contractsById = new HashMap<>();
		for (Contract contract : contracts.findAllById(contractIds)) {
			contractsById.put(contract.getId(), contract);
		}
		Map<UUID, List<Installment>> scheduleByContract = new HashMap<>();
		for (Installment installment : installments.findByContractIdInOrderByContractIdAscPeriodNoAsc(contractIds)) {
			scheduleByContract.computeIfAbsent(installment.getContract().getId(), id -> new ArrayList<>())
					.add(installment);
		}
		List<InstallmentPenaltySnapshot> snapshots = new ArrayList<>(scheduleByContract.size());
		for (Map.Entry<UUID, List<Installment>> entry : scheduleByContract.entrySet()) {
			Contract contract = contractsById.get(entry.getKey());
			if (contract != null) {
				snapshots.add(toSnapshot(contract, entry.getValue()));
			}
		}
		return snapshots;
	}

	/** Maps a contract and its loaded schedule to the penalty snapshot, the shared body of all read methods. */
	private static InstallmentPenaltySnapshot toSnapshot(Contract contract, List<Installment> schedule) {
		List<InstallmentPenalty> penaltyView = schedule.stream()
				.map(installment -> new InstallmentPenalty(installment.getId(), installment.getPeriodNo(),
						installment.getDueDate(), InstallmentBalance.penaltyBase(installment),
						installment.getPenaltyAmount(), installment.getStatus()))
				.toList();
		return new InstallmentPenaltySnapshot(contract.getId(), contract.getContractNo(),
				contract.getGracePeriodDays(), contract.getPenaltyRateDaily(), penaltyView);
	}

	@Override
	public void applyPenaltyAccrual(UUID contractId, Map<UUID, BigDecimal> accruedByInstallment) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(accruedByInstallment, "accruedByInstallment");
		if (accruedByInstallment.isEmpty()) {
			return;
		}

		Map<UUID, Installment> byId = new HashMap<>();
		for (Installment installment : installments.findByContractIdOrderByPeriodNo(contractId)) {
			byId.put(installment.getId(), installment);
		}
		for (Map.Entry<UUID, BigDecimal> accrual : accruedByInstallment.entrySet()) {
			Installment installment = byId.get(accrual.getKey());
			if (installment == null) {
				// A caller accrued a penalty against an installment outside this contract: a programming
				// error, never something to absorb silently.
				throw new IllegalStateException("installment " + accrual.getKey()
						+ " does not belong to contract " + contractId);
			}
			installment.accruePenalty(accrual.getValue());
		}
		// Flush now (instead of at COMMIT) so a broken amount fails inside the accrual use case, with the
		// accrual rows and its journal rolled back together.
		installments.flush();
	}
}
