package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentAgingPort;
import com.serfira.contract.application.InstallmentBillingPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-contract transaction boundaries of the daily job (Addendum §5/§7.3).
 *
 * <ul>
 *   <li>{@link #process}: interest is billed before penalty uses that recognized receivable as its base. A
 *       failure in either step rolls both steps and their journals back together.</li>
 *   <li>{@link #age}: the aging step runs in its own transaction after {@link #process} (ADR-013
 *       implementation note T6). It writes no money, and its condition does not depend on the penalty, so a
 *       billing/penalty failure must not stop it and an aging failure must not undo recognized money.</li>
 * </ul>
 */
@Service
public class DailyServicingContractProcessor {

	private final InstallmentBillingPort billing;
	private final PenaltyAccrualPort penalty;
	private final InstallmentAgingPort aging;

	public DailyServicingContractProcessor(InstallmentBillingPort billing, PenaltyAccrualPort penalty,
			InstallmentAgingPort aging) {
		this.billing = billing;
		this.penalty = penalty;
		this.aging = aging;
	}

	@Transactional
	public void process(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		billing.billDueInterest(contractId, businessDate);
		penalty.accrueDuePenalty(contractId, businessDate);
	}

	@Transactional
	public void age(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		aging.markOverdueInstallments(contractId, businessDate);
	}
}
