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
	 * The base a late-payment penalty accrues on (02_TECH_SPEC.md §4.3, PRD D-1): "saldo tagihan
	 * pokok+bunga yang belum dibayar", read as the installment's principal plus its <b>recognized</b>
	 * interest minus everything that has resolved against it.
	 *
	 * <pre>
	 * penaltyBase = max(0, principalAmount + recognizedInterestAmount − paid − settled − writtenOff)
	 * </pre>
	 *
	 * <p>Only billed interest counts (PRD §5C: future scheduled interest is never a receivable), and the
	 * installment's own penalty is deliberately excluded so a penalty never accrues on an unpaid penalty.
	 * Zero means nothing is outstanding and the day is not chargeable.
	 *
	 * <p>Documented conservatism (ADR-012 decision 2): the component split of a payment is not part of this
	 * aggregate, so denda already paid also reduces this base — the owed pokok+bunga can therefore be
	 * understated by at most the denda paid, never overstated.
	 *
	 * @param installment installment whose penalty base is needed; must not be null
	 * @return scale-2 money, {@code >= 0}
	 */
	public static BigDecimal penaltyBase(Installment installment) {
		Objects.requireNonNull(installment, "installment");
		BigDecimal base = installment.getPrincipalAmount()
				.add(installment.getRecognizedInterestAmount())
				.subtract(installment.getPaidAmount())
				.subtract(installment.getSettledAmount())
				.subtract(installment.getWrittenOffAmount());
		return base.signum() > 0 ? base : BigDecimal.ZERO;
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