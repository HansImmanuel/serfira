package com.serfira.contract.application;

import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link InstallmentAgingPort}: the {@code contract} module decides which installments are
 * in arrears ({@link Installment#markOverdue}) and writes the transition itself (DM §1.4).
 *
 * <p>{@code MANDATORY} rather than no annotation: the entity changes are only persisted by the caller's
 * persistence context, so a call outside a transaction would silently lose the transition. Failing loudly is
 * the safer contract; the daily job's aging transaction is the owner of the boundary.
 */
@Service
public class InstallmentAgingService implements InstallmentAgingPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(InstallmentAgingService.class);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public InstallmentAgingService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int markOverdueInstallments(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		Contract contract = contracts.findById(contractId).orElseThrow(() -> new ContractNotFoundException(contractId));
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and is not aged; only an ACTIVE contract is");
		}
		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(contractId);
		if (schedule.isEmpty()) {
			// ACTIVE without a schedule is corrupt data (V4 keeps a draft coherent, not an active one).
			throw new ContractStateException("contract " + contract.getContractNo()
					+ " is ACTIVE but has no schedule, so it has nothing to age");
		}

		int markedOverdue = 0;
		for (Installment installment : schedule) {
			if (installment.markOverdue(businessDate, contract.getGracePeriodDays())) {
				markedOverdue++;
			}
		}
		if (markedOverdue > 0) {
			// Flush now (instead of at COMMIT) so a coherence-constraint failure surfaces inside the aging step.
			installments.flush();
			LOGGER.info("Marked {} installment(s) of contract {} OVERDUE for business date {}",
					markedOverdue, contractId, businessDate);
		}
		return markedOverdue;
	}
}
