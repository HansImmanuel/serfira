package com.serfira.contract.api;

import com.serfira.contract.domain.Installment;
import com.serfira.contract.domain.InstallmentBalance;
import com.serfira.contract.domain.InstallmentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of a contract's schedule (PRD S-4, DM §1.4, FE §2.5).
 *
 * <p>The resolution amounts are exposed separately so the client can show paid vs settled vs
 * written-off without conflating them (PRD §5A, TS §6).
 *
 * @param recognizedInterestAmount interest actually billed/re-recognized — the only interest that is
 *                                 receivable yet (future scheduled interest is not)
 * @param outstanding              recognized receivable not yet resolved, from
 *                                 {@link InstallmentBalance} so the API and the allocation logic
 *                                 share one definition
 */
public record InstallmentResponse(
		UUID id,
		int periodNo,
		LocalDate dueDate,
		BigDecimal principalAmount,
		BigDecimal interestAmount,
		BigDecimal recognizedInterestAmount,
		BigDecimal penaltyAmount,
		BigDecimal paidAmount,
		BigDecimal settledAmount,
		BigDecimal writtenOffAmount,
		InstallmentStatus status,
		BigDecimal outstanding) {

	public static InstallmentResponse from(Installment installment) {
		InstallmentBalance balance = InstallmentBalance.of(installment);
		return new InstallmentResponse(
				installment.getId(),
				installment.getPeriodNo(),
				installment.getDueDate(),
				installment.getPrincipalAmount(),
				installment.getInterestAmount(),
				installment.getRecognizedInterestAmount(),
				installment.getPenaltyAmount(),
				installment.getPaidAmount(),
				installment.getSettledAmount(),
				installment.getWrittenOffAmount(),
				installment.getStatus(),
				balance.outstanding());
	}
}