package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Pure calculation engine for payment allocation (PRD §3 and §4.3 P-2/P-3, 03_DOMAIN_MODEL.md §1.8,
 * ADR-009).
 *
 * <p>Rules implemented here:
 * <ul>
 *   <li>Only installments whose {@code due_date <= businessDate} are allocatable — the balance of a future
 *       installment is never touched (PRD skenario 4, Addendum §2).</li>
 *   <li>Oldest due installment first, ties broken by {@code period_no}; inside one installment
 *       {@code PENALTY → INTEREST → PRINCIPAL}.</li>
 *   <li>Each component is capped by the same headroom the database enforces at COMMIT (V3
 *       {@code assert_payment_allocation_component_caps}) and each installment by its outstanding
 *       receivable, so {@code paid + settled + written_off <= recognized_total} (invariant 3).</li>
 *   <li>Whatever cannot be applied to a due installment becomes exactly one EXCESS line — customer credit,
 *       never a line with zero amount (invariant 6, PRD P-4).</li>
 * </ul>
 *
 * <p>No rounding happens here: inputs are scale-2 money and the outputs are sums and differences of them
 * (ADR-009 decision 7), so an amount that cannot be represented at scale 2 fails loudly instead of being
 * silently rounded.
 *
 * <p>Stateless and free of Spring, JPA, and {@code Clock} — the business date is a parameter, which keeps
 * behaviour deterministic under test (same shape as {@code ScheduleEngine}).
 */
public final class PaymentAllocationEngine {

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);
	private static final List<AllocationType> WATERFALL =
			List.of(AllocationType.PENALTY, AllocationType.INTEREST, AllocationType.PRINCIPAL);

	/**
	 * Allocates one payment against the installments of a contract.
	 *
	 * @param paymentAmount amount received; scale-2 money, {@code > 0}
	 * @param businessDate  business date of the payment ({@code paid_at} in Asia/Jakarta, taken from the
	 *                      injectable clock — TS §2.0); decides which installments are due
	 * @param installments  receivable snapshot of the contract's installments, in any order
	 * @return ordered allocation lines plus the excess; the lines always total {@code paymentAmount}
	 * @throws NullPointerException     if an argument or a list element is {@code null}
	 * @throws IllegalArgumentException if the amount is not positive, an installment is listed twice, or the
	 *                                  snapshot itself violates a receivable invariant
	 * @throws ArithmeticException      if an amount is not representable as scale-2 money
	 */
	public AllocationResult allocate(BigDecimal paymentAmount, LocalDate businessDate,
			List<InstallmentAllocationInput> installments) {
		Objects.requireNonNull(businessDate, "businessDate");
		Objects.requireNonNull(installments, "installments");
		BigDecimal total = positiveMoney("paymentAmount", paymentAmount);

		BigDecimal unallocated = total;
		List<AllocationLine> lines = new ArrayList<>();
		for (InstallmentAllocationInput installment : dueInstallments(installments, businessDate)) {
			unallocated = applyTo(installment, unallocated, lines);
			if (unallocated.signum() == 0) {
				break;
			}
		}

		BigDecimal excess = unallocated.signum() > 0 ? unallocated : ZERO;
		if (excess.signum() > 0) {
			lines.add(new AllocationLine(AllocationType.EXCESS, null, excess));
		}
		return new AllocationResult(lines, excess, total);
	}

	/** Due installments (ADR-009 decision 1) in allocation order (decision 2). */
	private static List<InstallmentAllocationInput> dueInstallments(List<InstallmentAllocationInput> installments,
			LocalDate businessDate) {
		Set<UUID> refs = new HashSet<>();
		Set<String> periods = new HashSet<>();
		List<InstallmentAllocationInput> due = new ArrayList<>(installments.size());
		for (InstallmentAllocationInput installment : installments) {
			Objects.requireNonNull(installment, "installments must not contain null");
			if (!refs.add(installment.installmentRef())) {
				throw new IllegalArgumentException("installment " + installment.installmentRef() + " is listed twice");
			}
			if (!periods.add(installment.dueDate() + "/" + installment.periodNo())) {
				throw new IllegalArgumentException("period " + installment.periodNo() + " due "
						+ installment.dueDate() + " is listed twice");
			}
			if (!installment.dueDate().isAfter(businessDate)) {
				due.add(installment);
			}
		}
		due.sort(Comparator.comparing(InstallmentAllocationInput::dueDate)
				.thenComparingInt(InstallmentAllocationInput::periodNo));
		return due;
	}

	/** Applies as much of {@code remaining} as one installment can absorb, appending the lines produced. */
	private static BigDecimal applyTo(InstallmentAllocationInput installment, BigDecimal remaining,
			List<AllocationLine> lines) {
		BigDecimal capacity = installment.allocationCapacity();
		for (AllocationType type : WATERFALL) {
			if (remaining.signum() == 0 || capacity.signum() == 0) {
				break;
			}
			BigDecimal amount = headroom(installment, type).min(remaining).min(capacity);
			if (amount.signum() == 0) {
				continue;
			}
			lines.add(new AllocationLine(type, installment.installmentRef(), amount));
			remaining = remaining.subtract(amount);
			capacity = capacity.subtract(amount);
		}
		return remaining;
	}

	private static BigDecimal headroom(InstallmentAllocationInput installment, AllocationType type) {
		return switch (type) {
			case PENALTY -> installment.penaltyHeadroom();
			case INTEREST -> installment.interestHeadroom();
			case PRINCIPAL -> installment.principalHeadroom();
			case EXCESS -> throw new IllegalArgumentException("EXCESS is not an installment component");
		};
	}

	private static BigDecimal positiveMoney(String name, BigDecimal value) {
		Objects.requireNonNull(value, name);
		BigDecimal normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException(name + " must be > 0 but was " + normalized);
		}
		return normalized;
	}
}
