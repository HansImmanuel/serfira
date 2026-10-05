package com.serfira.contract.domain.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The receivable of one installment as the credit-apply engine needs to see it, built entirely from
 * {@code contract}-owned columns (03_DOMAIN_MODEL.md §1.4; task T14).
 *
 * <p><b>Why the per-component split is reconstructed, not read.</b> A payment records its component split
 * in {@code payment_allocation}, which {@code contract} must not read (02_TECH_SPEC.md §1), and a credit
 * application never writes {@code payment_allocation} at all — so the V3 per-component cap trigger does
 * not police credit. The only resolution figure {@code contract} owns is the lumped
 * {@code resolvedAmount = paid + settled + written_off} on the installment itself. Because every
 * resolution to date followed the same waterfall (PENALTY → INTEREST → PRINCIPAL), the engine replays
 * that lumped total against the current recognized component caps to derive how much of each component is
 * already consumed, then applies new credit to the residual. This bounds each component's journal credit
 * by its recognized receivable (so {@code PIUTANG_*} is never over-credited) and keeps the lumped
 * {@code paid + settled + written_off <= recognized_total} invariant (V3
 * {@code assert_installment_amounts}) satisfied — the one DB backstop a credit application does fire.
 *
 * <p>The reconstruction is conservative in a single direction: if penalty accrued after an earlier
 * payment, replay may attribute slightly more of the lumped total to penalty than the payment actually
 * did, so a component residual can be understated, never overstated. An understated residual only
 * declines to apply credit it is not certain is owed; it can never over-apply.
 *
 * @param installmentRef           installment the amounts belong to
 * @param periodNo                 1-based period number, used to break ties on identical due dates
 * @param dueDate                  due date; only {@code due_date <= business date} is applicable (ADR-009)
 * @param principalAmount          scheduled principal (cap for PRINCIPAL)
 * @param recognizedInterestAmount interest recognized/billed so far (cap for INTEREST; future scheduled
 *                                 interest is never applicable)
 * @param penaltyAmount            gross cumulative recognized penalty
 * @param penaltyAdjustments       Σ penalty adjustments (waive/reduce), subtracted from the gross penalty
 * @param resolvedAmount           lumped {@code paid + settled + written_off} already resolved
 */
public record CreditAllocationInput(UUID installmentRef, int periodNo, LocalDate dueDate,
		BigDecimal principalAmount, BigDecimal recognizedInterestAmount, BigDecimal penaltyAmount,
		BigDecimal penaltyAdjustments, BigDecimal resolvedAmount) {

	private static final int MONEY_SCALE = 2;

	public CreditAllocationInput {
		Objects.requireNonNull(installmentRef, "installmentRef");
		Objects.requireNonNull(dueDate, "dueDate");
		if (periodNo < 1) {
			throw new IllegalArgumentException("periodNo must be >= 1 but was " + periodNo);
		}
		principalAmount = money("principalAmount", principalAmount);
		recognizedInterestAmount = money("recognizedInterestAmount", recognizedInterestAmount);
		penaltyAmount = money("penaltyAmount", penaltyAmount);
		penaltyAdjustments = money("penaltyAdjustments", penaltyAdjustments);
		resolvedAmount = money("resolvedAmount", resolvedAmount);

		BigDecimal effectivePenalty = penaltyAmount.subtract(penaltyAdjustments);
		if (effectivePenalty.signum() < 0) {
			throw new IllegalArgumentException("installment " + installmentRef + " has penalty adjustments "
					+ penaltyAdjustments + " above recognized penalty " + penaltyAmount);
		}
		BigDecimal recognizedTotal = principalAmount.add(recognizedInterestAmount).add(effectivePenalty);
		if (resolvedAmount.compareTo(recognizedTotal) > 0) {
			throw new IllegalArgumentException("installment " + installmentRef
					+ " is already resolved beyond its recognized total " + recognizedTotal);
		}
	}

	/** Gross recognized penalty net of adjustments (DM §1.4, invariant 9). */
	public BigDecimal effectivePenalty() {
		return penaltyAmount.subtract(penaltyAdjustments);
	}

	/** Recognized receivable: principal + recognized interest + effective penalty (DM §1.4, invariant 13). */
	public BigDecimal recognizedTotal() {
		return principalAmount.add(recognizedInterestAmount).add(effectivePenalty());
	}

	/** Outstanding receivable this credit may still reduce: {@code recognizedTotal − resolvedAmount}. */
	public BigDecimal outstanding() {
		return recognizedTotal().subtract(resolvedAmount);
	}

	/**
	 * Residual of one component after replaying the lumped {@code resolvedAmount} through the waterfall
	 * (PENALTY → INTEREST → PRINCIPAL). See the record Javadoc for why the split is reconstructed.
	 */
	public BigDecimal residualFor(CreditComponent component) {
		BigDecimal penalty = effectivePenalty();
		BigDecimal consumedPenalty = resolvedAmount.min(penalty);
		BigDecimal afterPenalty = resolvedAmount.subtract(consumedPenalty);
		BigDecimal consumedInterest = afterPenalty.min(recognizedInterestAmount);
		BigDecimal afterInterest = afterPenalty.subtract(consumedInterest);
		BigDecimal consumedPrincipal = afterInterest.min(principalAmount);
		return switch (component) {
			case PENALTY -> penalty.subtract(consumedPenalty);
			case INTEREST -> recognizedInterestAmount.subtract(consumedInterest);
			case PRINCIPAL -> principalAmount.subtract(consumedPrincipal);
		};
	}

	private static BigDecimal money(String name, BigDecimal value) {
		Objects.requireNonNull(value, name);
		BigDecimal normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.signum() < 0) {
			throw new IllegalArgumentException(name + " must be >= 0 but was " + normalized);
		}
		return normalized;
	}
}
