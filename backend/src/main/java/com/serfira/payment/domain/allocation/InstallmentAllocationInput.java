package com.serfira.payment.domain.allocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The receivable of one installment as the allocation engine needs to see it (03_DOMAIN_MODEL.md §1.4,
 * §1.8; ADR-009).
 *
 * <p>Deliberately owned by the {@code payment} module and built from plain column values: the module
 * boundary forbids {@code payment} from importing {@code com.serfira.contract.*} (02_TECH_SPEC.md §1), and a
 * value record keeps the engine pure (no Spring, no JPA, no database). The adapter in story C3 maps
 * {@code installment} rows into this record; the derived formulas below mirror {@code InstallmentBalance}
 * and DM §1.4 exactly (<b>keep both in step</b>), and the guards mirror the deferred V3 triggers
 * {@code assert_payment_allocation_component_caps} / {@code assert_installment_amounts}.
 *
 * <p>{@code allocated*} fields are the allocations of <b>active</b> (POSTED) payments for the installment;
 * allocations of voided payments stay in the table as history but do not count (DM invariant 7, Addendum §6).
 * They are not derivable here — the caller (C3) queries them.
 *
 * @param installmentRef           installment the amounts belong to
 * @param periodNo                 1-based period number, used to break ties on identical due dates
 * @param dueDate                  due date; only installments with {@code due_date <= business date} are allocatable
 * @param principalAmount          scheduled principal (cap for PRINCIPAL allocations)
 * @param recognizedInterestAmount interest recognized/billed so far (cap for INTEREST allocations; scheduled
 *                                 but unrecognized interest is never allocatable)
 * @param penaltyAmount            gross cumulative recognized penalty
 * @param penaltyAdjustments       Σ penalty adjustments (waive/reduce), subtracted from the gross penalty
 * @param allocatedPenalty         active PENALTY allocations already applied
 * @param allocatedInterest        active INTEREST allocations already applied
 * @param allocatedPrincipal       active PRINCIPAL allocations already applied
 * @param settledAmount            settlement resolution (not a payment)
 * @param writtenOffAmount         write-off resolution (not a payment)
 */
public record InstallmentAllocationInput(UUID installmentRef, int periodNo, LocalDate dueDate,
		BigDecimal principalAmount, BigDecimal recognizedInterestAmount, BigDecimal penaltyAmount,
		BigDecimal penaltyAdjustments, BigDecimal allocatedPenalty, BigDecimal allocatedInterest,
		BigDecimal allocatedPrincipal, BigDecimal settledAmount, BigDecimal writtenOffAmount) {

	private static final int MONEY_SCALE = 2;

	public InstallmentAllocationInput {
		Objects.requireNonNull(installmentRef, "installmentRef");
		Objects.requireNonNull(dueDate, "dueDate");
		if (periodNo < 1) {
			throw new IllegalArgumentException("periodNo must be >= 1 but was " + periodNo);
		}
		principalAmount = money("principalAmount", principalAmount);
		recognizedInterestAmount = money("recognizedInterestAmount", recognizedInterestAmount);
		penaltyAmount = money("penaltyAmount", penaltyAmount);
		penaltyAdjustments = money("penaltyAdjustments", penaltyAdjustments);
		allocatedPenalty = money("allocatedPenalty", allocatedPenalty);
		allocatedInterest = money("allocatedInterest", allocatedInterest);
		allocatedPrincipal = money("allocatedPrincipal", allocatedPrincipal);
		settledAmount = money("settledAmount", settledAmount);
		writtenOffAmount = money("writtenOffAmount", writtenOffAmount);

		if (allocatedPrincipal.compareTo(principalAmount) > 0) {
			throw new IllegalArgumentException("installment " + installmentRef + " has principal allocations "
					+ allocatedPrincipal + " above principal " + principalAmount);
		}
		if (allocatedInterest.compareTo(recognizedInterestAmount) > 0) {
			throw new IllegalArgumentException("installment " + installmentRef + " has interest allocations "
					+ allocatedInterest + " above recognized interest " + recognizedInterestAmount);
		}
		if (penaltyAmount.subtract(penaltyAdjustments).signum() < 0) {
			throw new IllegalArgumentException("installment " + installmentRef + " has penalty adjustments "
					+ penaltyAdjustments + " above recognized penalty " + penaltyAmount);
		}
		if (allocatedPenalty.compareTo(penaltyAmount.subtract(penaltyAdjustments)) > 0) {
			throw new IllegalArgumentException("installment " + installmentRef + " has penalty allocations "
					+ allocatedPenalty + " above effective penalty " + penaltyAmount.subtract(penaltyAdjustments));
		}
		BigDecimal recognizedTotal = principalAmount.add(recognizedInterestAmount)
				.add(penaltyAmount.subtract(penaltyAdjustments));
		if (allocatedPenalty.add(allocatedInterest).add(allocatedPrincipal).add(settledAmount).add(writtenOffAmount)
				.compareTo(recognizedTotal) > 0) {
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

	/** Penalty still allocatable: effective penalty minus active penalty allocations (invariant 9). */
	public BigDecimal penaltyHeadroom() {
		return effectivePenalty().subtract(allocatedPenalty);
	}

	/**
	 * Interest still allocatable. Only interest that has been recognized/billed is receivable, so the
	 * scheduled-but-unrecognized part of {@code interest_amount} can never be allocated (TS §3, PRD §5A).
	 */
	public BigDecimal interestHeadroom() {
		return recognizedInterestAmount.subtract(allocatedInterest);
	}

	/** Principal still allocatable (cap {@code principal_amount}). */
	public BigDecimal principalHeadroom() {
		return principalAmount.subtract(allocatedPrincipal);
	}

	/** Amount resolved by active payments so far (payment only, excludes settlement and write-off). */
	public BigDecimal allocatedAmount() {
		return allocatedPenalty.add(allocatedInterest).add(allocatedPrincipal);
	}

	/** Amount resolved by payment, settlement, or write-off (DM §1.4 {@code resolved_amount}). */
	public BigDecimal resolvedAmount() {
		return allocatedAmount().add(settledAmount).add(writtenOffAmount);
	}

	/**
	 * Outstanding receivable — how much a new payment may still apply to. Equals
	 * {@code Σ component headroom − settled − written off}, which is exactly what keeps
	 * {@code paid + settled + written_off <= recognized_total} (invariant 3, V3 trigger).
	 */
	public BigDecimal allocationCapacity() {
		return recognizedTotal().subtract(resolvedAmount());
	}

	/** {@code false} when the installment is already fully resolved (PAID / SETTLED / WRITTEN_OFF). */
	public boolean hasCapacity() {
		return allocationCapacity().signum() > 0;
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
