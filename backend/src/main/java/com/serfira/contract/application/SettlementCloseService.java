package com.serfira.contract.application;

import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStateException;
import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentStatus;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.InstallmentRepository;
import com.serfira.shared.error.StaleSettlementQuoteException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link SettlementClosePort}: the {@code contract} module's own SETTLED-close path (E2,
 * task T13, ADR-018 D10).
 *
 * <p>{@code Propagation.MANDATORY}, like {@link SettlementReceivableService} and
 * {@link ContractCreditService}: it joins the settlement use case's transaction so the installment
 * settlement and the contract closure commit with the settlement row and its journal, and never starts a
 * transaction of its own. It posts no journal (the settlement module owns the single SETTLEMENT entry,
 * ADR-018 D1/D4).
 *
 * <p>Order of guards mirrors the quote/payment contract reads: load (404 if absent), assert the live
 * {@code version} equals the quote's snapshot (else the quote is stale — the same figure ADR-018 D9's
 * component revalidation guards, enforced here so the optimistic lock and the snapshot agree), assert
 * ACTIVE, then settle every still-open installment the settlement cleared and close the contract. A
 * fully-resolved installment ({@code PAID}/{@code SETTLED}/{@code WRITTEN_OFF}) is left untouched; only
 * {@code PENDING}/{@code PARTIALLY_PAID}/{@code OVERDUE} rows move to {@code SETTLED}.
 */
@Service
public class SettlementCloseService implements SettlementClosePort {

	private static final Logger LOGGER = LoggerFactory.getLogger(SettlementCloseService.class);

	private final ContractRepository contracts;
	private final InstallmentRepository installments;

	public SettlementCloseService(ContractRepository contracts, InstallmentRepository installments) {
		this.contracts = contracts;
		this.installments = installments;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public SettlementCloseResult closeBySettlement(SettlementCloseCommand command) {
		Objects.requireNonNull(command, "command");
		Contract contract = contracts.findById(command.contractId())
				.orElseThrow(() -> new ContractNotFoundException(command.contractId()));
		if (contract.getVersion() != command.expectedContractVersion()) {
			// The contract moved on since the quote was priced: the quote is stale (ADR-018 D9). Reject
			// before any installment is settled so the execution writes nothing.
			throw new StaleSettlementQuoteException("contract " + contract.getContractNo()
					+ " changed since the quote was priced (expected version "
					+ command.expectedContractVersion() + ", found " + contract.getVersion() + ")");
		}
		if (!contract.isActive()) {
			throw new ContractStateException("contract " + contract.getContractNo() + " is " + contract.getStatus()
					+ " and cannot be settled; only an ACTIVE contract can");
		}

		List<Installment> schedule = installments.findByContractIdOrderByPeriodNo(command.contractId());
		Map<UUID, BigDecimal> settledByInstallment = new LinkedHashMap<>();
		for (Installment installment : schedule) {
			if (isTerminalResolution(installment.getStatus())) {
				// Already PAID/SETTLED/WRITTEN_OFF: nothing left for the settlement to resolve here.
				continue;
			}
			BigDecimal settledAmount = command.settledByInstallment().get(installment.getId());
			if (settledAmount == null || settledAmount.signum() <= 0) {
				// An open installment with nothing outstanding to settle (e.g. a future period with no
				// recognized receivable). Leave it to the contract-wide close below; it carries no money.
				continue;
			}
			installment.settle(settledAmount, command.executedAt());
			settledByInstallment.put(installment.getId(), settledAmount);
		}

		boolean closed = contract.close(ClosedReason.SETTLEMENT, command.executedAt());
		LOGGER.info("Closed contract {} by SETTLEMENT ({} installment(s) settled, closed={})",
				contract.getContractNo(), settledByInstallment.size(), closed);
		return new SettlementCloseResult(settledByInstallment);
	}

	private static boolean isTerminalResolution(InstallmentStatus status) {
		return status == InstallmentStatus.PAID
				|| status == InstallmentStatus.SETTLED
				|| status == InstallmentStatus.WRITTEN_OFF;
	}
}
