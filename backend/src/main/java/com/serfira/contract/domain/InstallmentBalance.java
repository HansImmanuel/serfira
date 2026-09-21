package com.serfira.contract.domain;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Receivable view of one installment (03_DOMAIN_MODEL.md §1.4, §3 invariant 13):
 *
 * <pre>
 * recognizedTotal = principalAmount + recognizedInterestAmount + (penaltyAmount − penaltyAdjustments)
 * resolvedAmount  = paidAmount + settledAmount + writtenOffAmount
 * outstanding     = recognizedTotal − resolvedAmount
 * </pre>
 *
 * <p>Future scheduled interest is deliberately NOT part of the recognized total: only interest that
 * has been billed/re-recognized ({@code recognizedInterestAmount}) is receivable
 * (02_TECH_SPEC.md §5, 04_GAPS_ADDENDUM.md §12).
 *
 * <p>{@code outstanding} is guaranteed non-negative by the DB-level invariant (V3
 * {@code trg_installment_amounts_deferred}); a negative value therefore indicates corrupted data and
 * is left visible rather than hidden (the consistency job, story L-3, reports it).
 *
 * <p>All amounts are scale-2 money and are never rounded here — rounding belongs to the calculation
 * that produced them.
 */
public record InstallmentBalance(BigDecimal recognizedTotal, BigDecimal resolvedAmount, BigDecimal outstanding) {

	/** Balance with no penalty adjustments applied (no waiver exists yet — story E5 adds them). */
	public static InstallmentBalance of(Installment installment) {
		return of(installment, BigDecimal.ZERO);
	}

	/**
	 * Balance net of penalty adjustments. This overload is the documented E5 extension point; today
	 * it is exercised by unit tests only, because no waiver flow exists yet.
	 *
	 * @param penaltyAdjustments total active penalty adjustment for the installment, subtracted from
	 *                           the recognized penalty (invariant 9)
	 */
	public static InstallmentBalance of(Installment installment, BigDecimal penaltyAdjustments) {
		Objects.requireNonNull(installment, "installment");
		Objects.requireNonNull(penaltyAdjustments, "penaltyAdjustments");
		if (penaltyAdjustments.signum() < 0) {
			throw new IllegalArgumentException("penaltyAdjustments must be >= 0 but was " + penaltyAdjustments);
		}
		BigDecimal recognizedTotal = installment.getPrincipalAmount()
				.add(installment.getRecognizedInterestAmount())
				.add(installment.getPenaltyAmount().subtract(penaltyAdjustments));
		BigDecimal resolvedAmount = installment.getPaidAmount()
				.add(installment.getSettledAmount())
				.add(installment.getWrittenOffAmount());
		return new InstallmentBalance(recognizedTotal, resolvedAmount, recognizedTotal.subtract(resolvedAmount));
	}
}