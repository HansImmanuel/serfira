package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link SettlementReceivablePort}: the {@code contract} module's answer to "price this
 * contract for an early settlement" (E1, task T12, ADR-018).
 *
 * <p>Deliberately not {@code @Transactional}: it runs inside the settlement quote use case's transaction
 * (ADR-018 D8 requires billing + accrual to have happened first, in that same transaction) and must see
 * exactly the receivables the caller just recognized. Starting a transaction here would hide the
 * caller's uncommitted billing/accrual.
 *
 * <p>Reads only the installment columns; the settlement module derives the component split (unpaid
 * principal, unpaid billed interest, future interest), so the receivable definition stays in one place.
 */
@Service
public class SettlementReceivableService implements SettlementReceivablePort {

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public SettlementReceivableService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	public SettlementContractSnapshot loadSettlementSnapshot(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		Contract contract = contracts.findById(contractId)
				.orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and cannot be settled; only an ACTIVE contract can");
		}
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		if (schedule.isEmpty()) {
			// ACTIVE without a schedule is corrupt data (V4 keeps a draft coherent, not an active one).
			throw new ContractStateException("contract " + contract.getContractNo()
					+ " is ACTIVE but has no schedule, so it cannot be settled");
		}

		List<SettlementInstallment> rows = schedule.stream()
				.map(SettlementReceivableService::toSettlementInstallment)
				.toList();
		return new SettlementContractSnapshot(contract.getId(), contract.getContractNo(), contract.getVersion(),
				contract.getStartDate(), rows);
	}

	private static SettlementInstallment toSettlementInstallment(Installment installment) {
		BigDecimal resolved = installment.getPaidAmount()
				.add(installment.getSettledAmount())
				.add(installment.getWrittenOffAmount());
		boolean resolvedOutsidePayment = installment.getStatus() == InstallmentStatus.SETTLED
				|| installment.getStatus() == InstallmentStatus.WRITTEN_OFF;
		return new SettlementInstallment(installment.getId(), installment.getPeriodNo(), installment.getDueDate(),
				resolvedOutsidePayment, installment.getPrincipalAmount(), installment.getInterestAmount(),
				installment.getRecognizedInterestAmount(), resolved);
	}
}
