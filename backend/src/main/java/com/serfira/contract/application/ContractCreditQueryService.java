package com.serfira.contract.application;

import com.serfira.contract.api.CreditApplicationHistoryItem;
import com.serfira.contract.api.CreditBalanceResponse;
import com.serfira.contract.domain.ContractCredit;
import com.serfira.contract.domain.ContractCreditApplication;
import com.serfira.contract.domain.ContractCreditStatus;
import com.serfira.contract.infrastructure.ContractCreditApplicationRepository;
import com.serfira.contract.infrastructure.ContractCreditRepository;
import com.serfira.contract.infrastructure.ContractRepository;
import com.serfira.contract.infrastructure.CreditAppliedTotal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Read side of contract credit (task T14, Addendum §2.5): the credit balance and application history for
 * {@code GET /api/v1/contracts/{id}/credit}.
 *
 * <p>{@code readOnly = true} (TS §2.3): this never mutates. Available balance is derived the same way the
 * command path derives it — {@code amount − Σ applications} over AVAILABLE credits — so the two views
 * agree.
 */
@Service
@Transactional(readOnly = true)
public class ContractCreditQueryService {

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private final ContractRepository contracts;
	private final ContractCreditRepository credits;
	private final ContractCreditApplicationRepository applications;

	public ContractCreditQueryService(ContractRepository contracts, ContractCreditRepository credits,
			ContractCreditApplicationRepository applications) {
		this.contracts = contracts;
		this.credits = credits;
		this.applications = applications;
	}

	/**
	 * The contract's credit standing and history.
	 *
	 * @param contractId contract to read
	 * @return total credit, available balance and every application made, oldest first
	 * @throws ContractNotFoundException if no contract has that id (404)
	 */
	public CreditBalanceResponse credit(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		if (!contracts.existsById(contractId)) {
			throw new ContractNotFoundException(contractId);
		}

		List<ContractCredit> all = credits.findByContractIdOrderByCreatedAtAsc(contractId);
		BigDecimal totalCredit = all.stream()
				.map(ContractCredit::getAmount)
				.reduce(ZERO_MONEY, BigDecimal::add)
				.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

		List<UUID> creditIds = all.stream().map(ContractCredit::getId).toList();
		Map<UUID, BigDecimal> appliedByCredit = appliedByCredit(creditIds);
		BigDecimal availableBalance = ZERO_MONEY;
		for (ContractCredit credit : all) {
			if (credit.getStatus() == ContractCreditStatus.AVAILABLE) {
				availableBalance = availableBalance.add(credit.getAmount()
						.subtract(appliedByCredit.getOrDefault(credit.getId(), ZERO_MONEY)));
			}
		}
		availableBalance = availableBalance.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);

		List<CreditApplicationHistoryItem> history = creditIds.isEmpty()
				? List.of()
				: applications.findByCreditIdInOrderByAppliedAtAsc(creditIds).stream()
						.map(ContractCreditQueryService::toHistory)
						.toList();
		return new CreditBalanceResponse(totalCredit, availableBalance, history);
	}

	private Map<UUID, BigDecimal> appliedByCredit(List<UUID> creditIds) {
		Map<UUID, BigDecimal> applied = new HashMap<>();
		if (creditIds.isEmpty()) {
			return applied;
		}
		for (CreditAppliedTotal total : applications.sumAppliedByCreditIds(creditIds)) {
			applied.put(total.creditId(), total.applied());
		}
		return applied;
	}

	private static CreditApplicationHistoryItem toHistory(ContractCreditApplication application) {
		return new CreditApplicationHistoryItem(application.getId(), application.getCreditId(),
				application.getInstallmentId(), application.getAmount(), application.getAppliedAt());
	}
}
