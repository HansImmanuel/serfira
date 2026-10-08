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
 * @param penaltyAmount            the <b>effective</b> (remaining) penalty the customer still owes:
 *                                 {@code max(0, gross − Σ adjustment − Σ paid penalty)}, taken verbatim
 *                                 from the {@code penalty}-owned
 *                                 {@link com.serfira.penalty.application.EffectivePenaltyPort} at the
 *                                 query-service layer (ADR-019 D1/D4). The schedule row therefore agrees
 *                                 by construction with the payment-receivable path instead of reporting
 *                                 gross or recomputing a second effective-penalty formula
 * @param outstanding              recognized receivable not yet resolved, from
 *                                 {@link InstallmentBalance} netting the same Σ adjustment; it needs no
 *                                 separate paid-penalty term because {@code paidAmount} already nets the
 *                                 penalty the customer paid, so the two values are each correct and
 *                                 mutually consistent
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

	/**
	 * Convenience for a row with no adjustments and no paid penalty, mirroring
	 * {@link InstallmentBalance#of(Installment)}. The effective penalty then equals the gross recognized
	 * penalty, so it is derived from the installment itself.
	 */
	public static InstallmentResponse from(Installment installment) {
		return from(installment, BigDecimal.ZERO, installment.getPenaltyAmount());
	}

	/**
	 * Row whose {@code penaltyAmount} is the port's effective (remaining) penalty and whose
	 * {@code outstanding} is net of the installment's active penalty adjustments (ADR-019 D1/D4).
	 *
	 * <p>Two distinct inputs come from the {@code penalty}-owned
	 * {@link com.serfira.penalty.application.EffectivePenaltyPort}, unpacked by the caller so this DTO stays
	 * free of a cross-module type:
	 * <ul>
	 *   <li>{@code effectivePenalty} — {@code max(0, gross − Σ adjustment − Σ paid penalty)}, reported
	 *       verbatim as {@code penaltyAmount}. No second formula is computed here.</li>
	 *   <li>{@code penaltyAdjustments} — the Σ waive/reduce, fed to
	 *       {@link InstallmentBalance#of(Installment, BigDecimal)} for {@code outstanding}. The paid penalty
	 *       is deliberately NOT subtracted again there: {@code paidAmount} already nets it, so passing the
	 *       adjustment alone keeps {@code outstanding} correct and consistent with {@code penaltyAmount}.</li>
	 * </ul>
	 *
	 * @param penaltyAdjustments total active penalty adjustment for the installment, scale-2, {@code >= 0}
	 * @param effectivePenalty   the port's effective (remaining) penalty for the installment, scale-2,
	 *                           {@code >= 0}
	 */
	public static InstallmentResponse from(Installment installment, BigDecimal penaltyAdjustments,
			BigDecimal effectivePenalty) {
		InstallmentBalance balance = InstallmentBalance.of(installment, penaltyAdjustments);
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