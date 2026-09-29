package com.serfira.penalty.application;

import com.serfira.contract.application.InstallmentBillingPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic servicing boundary for one contract: interest is billed before penalty uses that recognized
 * receivable as its base. A failure in either step rolls both steps and their journals back together.
 */
@Service
public class DailyServicingContractProcessor {

	private final InstallmentBillingPort billing;
	private final PenaltyAccrualPort penalty;

	public DailyServicingContractProcessor(InstallmentBillingPort billing, PenaltyAccrualPort penalty) {
		this.billing = billing;
		this.penalty = penalty;
	}

	@Transactional
	public void process(UUID contractId, LocalDate businessDate) {
		Objects.requireNonNull(contractId, "contractId");
		Objects.requireNonNull(businessDate, "businessDate");
		billing.billDueInterest(contractId, businessDate);
		penalty.accrueDuePenalty(contractId, businessDate);
	}
}
