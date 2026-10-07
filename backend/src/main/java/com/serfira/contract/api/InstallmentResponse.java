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
 * @param penaltyAmount            the <b>effective</b> (remaining) penalty: the gross recognized penalty
 *                                 net of active waivers/reductions (ADR-019 D4). The Σ adjustment comes
 *                                 from the {@code penalty}-owned {@link com.serfira.penalty.application.EffectivePenaltyPort}
 *                                 at the query-service layer, so the schedule row agrees by construction
 *                                 with the payment-receivable snapshot instead of reporting gross
 * @param outstanding              recognized receivable not yet resolved, from
 *                                 {@link InstallmentBalance} netting the same Σ adjustment, so the API
 *                                 and the allocation logic share one definition
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

	/** Convenience for a row with no penalty adjustments, mirroring {@link InstallmentBalance#of(Installment)}. */
	public static InstallmentResponse from(Installment installment) {
		return from(installment, BigDecimal.ZERO);
	}

	/**
	 * Row whose {@code penaltyAmount} and {@code outstanding} are net of the installment's active penalty
	 * adjustments (ADR-019 D4). {@code penaltyAdjustments} is the Σ waive/reduce the caller read from
	 * {@link com.serfira.penalty.application.EffectivePenaltyPort}; both the reported penalty and the
	 * outstanding subtract it through the same {@link InstallmentBalance#of(Installment, BigDecimal)}
	 * definition, so no second effective-penalty formula is introduced.
	 *
	 * @param penaltyAdjustments total active penalty adjustment for the installment, scale-2, {@code >= 0}
	 */
	public static InstallmentResponse from(Installment installment, BigDecimal penaltyAdjustments) {
		InstallmentBalance balance = InstallmentBalance.of(installment, penaltyAdjustments);
		BigDecimal effectivePenalty = installment.getPenaltyAmount().subtract(penaltyAdjustments);
		if (effectivePenalty.signum() < 0) {
			effectivePenalty = BigDecimal.ZERO.setScale(2);
		}
		return new InstallmentResponse(
				installment.getId(),
				installment.getPeriodNo(),
				installment.getDueDate(),
				installment.getPrincipalAmount(),
				installment.getInterestAmount(),
				installment.getRecognizedInterestAmount(),
				effectivePenalty,
				installment.getPaidAmount(),
				installment.getSettledAmount(),
				installment.getWrittenOffAmount(),
				installment.getStatus(),
				balance.outstanding());
	}
}