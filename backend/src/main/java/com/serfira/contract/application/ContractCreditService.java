package com.serfira.contract.application;

import com.serfira.contract.domain.ContractCredit;
import com.serfira.contract.domain.ContractCreditStatus;
import com.serfira.contract.infrastructure.ContractCreditApplicationRepository;
import com.serfira.contract.infrastructure.ContractCreditRepository;
import com.serfira.contract.infrastructure.CreditAppliedTotal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Implementation of {@link ContractCreditPort}: the {@code contract} module books a durable credit when
 * the {@code payment} module reports an overpayment (04_GAPS_ADDENDUM.md §2.1, task T14).
 *
 * <p>{@code Propagation.MANDATORY}, like {@link InstallmentReceivableService}: it joins the payment's
 * transaction so the credit row and the EXCESS → {@code TITIPAN_NASABAH} journal commit together, and it
 * never starts a transaction of its own. No journal is posted here — the money event was already
 * accounted for by the payment path; this row is only the liability sub-ledger.
 *
 * <p>The {@code uk_contract_credit_source} unique constraint is the backstop against double-booking the
 * same excess (a retried payment). It is left to surface as a {@code DataIntegrityViolationException}:
 * the EXCESS allocation id is unique within a payment, so a hit is a programming error, not a race a
 * retry could clear — translating it to a domain exception would hide that.
 */
@Service
public class ContractCreditService implements ContractCreditPort {

	private static final Logger LOGGER = LoggerFactory.getLogger(ContractCreditService.class);

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

	private final ContractCreditRepository credits;
	private final ContractCreditApplicationRepository applications;

	public ContractCreditService(ContractCreditRepository credits,
			ContractCreditApplicationRepository applications) {
		this.credits = credits;
		this.applications = applications;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID recordExcessCredit(RecordExcessCreditCommand command) {
		Objects.requireNonNull(command, "command");
		ContractCredit credit = new ContractCredit(command.contractId(), command.sourcePaymentAllocationId(),
				command.amount());
		ContractCredit saved = credits.save(credit);
		LOGGER.info("Booked contract_credit {} (contract={}, amount={}) from EXCESS allocation {}",
				saved.getId(), command.contractId(), command.amount(), command.sourcePaymentAllocationId());
		return saved.getId();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY, readOnly = true)
	public BigDecimal availableCredit(UUID contractId) {
		Objects.requireNonNull(contractId, "contractId");
		List<ContractCredit> available =
				credits.findByContractIdAndStatusOrderByCreatedAtAsc(contractId, ContractCreditStatus.AVAILABLE);
		if (available.isEmpty()) {
			return ZERO_MONEY;
		}
		List<UUID> creditIds = available.stream().map(ContractCredit::getId).toList();
		Map<UUID, BigDecimal> appliedByCredit = new HashMap<>();
		for (CreditAppliedTotal total : applications.sumAppliedByCreditIds(creditIds)) {
			appliedByCredit.put(total.creditId(), total.applied());
		}
		BigDecimal balance = ZERO_MONEY;
		for (ContractCredit credit : available) {
			balance = balance.add(credit.getAmount()
					.subtract(appliedByCredit.getOrDefault(credit.getId(), ZERO_MONEY)));
		}
		return balance.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
