package com.serfira.contract.domain.credit;

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
 * Pure calculation engine for applying available customer credit to recognized receivable (task T14,
 * 04_GAPS_ADDENDUM.md §2.2/§2.4, ADR-009).
 *
 * <p>Mirrors {@code PaymentAllocationEngine} exactly so credit and payment resolve a contract the same
 * way:
 * <ul>
 *   <li>Only installments whose {@code due_date <= businessDate} are applicable — future receivable is
 *       never credited (Addendum §2.4).</li>
 *   <li>Oldest due installment first, ties broken by {@code period_no}; inside one installment
 *       {@code PENALTY → INTEREST → PRINCIPAL}.</li>
 *   <li>Each component is capped by its residual ({@link CreditAllocationInput#residualFor}) and each
 *       installment by its outstanding receivable, so applying credit never drives
 *       {@code paid + settled + written_off} above {@code recognized_total} (invariant 3).</li>
 *   <li>Credit that cannot be applied is left as {@code unapplied} — it is <b>not</b> turned into a new
 *       excess (the EXCESS concept belongs to payment, not credit).</li>
 * </ul>
 *
 * <p>No rounding happens here: inputs are scale-2 money and outputs are sums and differences of them
 * (ADR-009 decision 7). Stateless and free of Spring, JPA and {@code Clock} — the business date is a
 * parameter, keeping the engine deterministic under test (same shape as {@code PaymentAllocationEngine}).
 */
public final class CreditApplicationEngine {

	private static final int MONEY_SCALE = 2;
	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE);
	private static final List<CreditComponent> WATERFALL =
			List.of(CreditComponent.PENALTY, CreditComponent.INTEREST, CreditComponent.PRINCIPAL);

	private CreditApplicationEngine() {
	}

	/**
	 * Applies up to {@code creditAmount} of available credit against the installments of a contract.
	 *
	 * @param creditAmount amount of available credit to apply; scale-2 money, {@code > 0}
	 * @param businessDate business date of the application (Asia/Jakarta, from the injectable clock);
	 *                     decides which installments are due
	 * @param installments the contract's installments, in any order
	 * @return ordered lines plus the applied and unapplied totals; the lines always total
	 *         {@code appliedTotal}, and {@code appliedTotal + unapplied = creditAmount}
	 * @throws NullPointerException     if an argument or a list element is null
	 * @throws IllegalArgumentException if the amount is not positive, or an installment is listed twice
	 * @throws ArithmeticException      if an amount is not representable as scale-2 money
	 */
	public static CreditAllocationResult allocate(BigDecimal creditAmount, LocalDate businessDate,
			List<CreditAllocationInput> installments) {
		Objects.requireNonNull(businessDate, "businessDate");
		Objects.requireNonNull(installments, "installments");
		BigDecimal total = positiveMoney(creditAmount);

		BigDecimal unapplied = total;
		List<CreditAllocationLine> lines = new ArrayList<>();
		for (CreditAllocationInput installment : dueInstallments(installments, businessDate)) {
			unapplied = applyTo(installment, unapplied, lines);
			if (unapplied.signum() == 0) {
				break;
			}
		}
		return new CreditAllocationResult(lines, total.subtract(unapplied), unapplied);
	}

	/** Due installments only (Addendum §2.4) in application order (oldest due first, tie-break period). */
	private static List<CreditAllocationInput> dueInstallments(List<CreditAllocationInput> installments,
			LocalDate businessDate) {
		Set<UUID> refs = new HashSet<>();
		List<CreditAllocationInput> due = new ArrayList<>(installments.size());
		for (CreditAllocationInput installment : installments) {
			Objects.requireNonNull(installment, "installments must not contain null");
			if (!refs.add(installment.installmentRef())) {
				throw new IllegalArgumentException("installment " + installment.installmentRef() + " is listed twice");
			}
			if (!installment.dueDate().isAfter(businessDate)) {
				due.add(installment);
			}
		}
		due.sort(Comparator.comparing(CreditAllocationInput::dueDate)
				.thenComparingInt(CreditAllocationInput::periodNo));
		return due;
	}

	/** Applies as much of {@code remaining} as one installment can absorb, appending the lines produced. */
	private static BigDecimal applyTo(CreditAllocationInput installment, BigDecimal remaining,
			List<CreditAllocationLine> lines) {
		BigDecimal capacity = installment.outstanding();
		for (CreditComponent component : WATERFALL) {
			if (remaining.signum() == 0 || capacity.signum() == 0) {
				break;
			}
			BigDecimal amount = installment.residualFor(component).min(remaining).min(capacity);
			if (amount.signum() <= 0) {
				continue;
			}
			lines.add(new CreditAllocationLine(component, installment.installmentRef(), amount));
			remaining = remaining.subtract(amount);
			capacity = capacity.subtract(amount);
		}
		return remaining;
	}

	private static BigDecimal positiveMoney(BigDecimal value) {
		Objects.requireNonNull(value, "creditAmount");
		BigDecimal normalized = value.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (normalized.signum() <= 0) {
			throw new IllegalArgumentException("creditAmount must be > 0 but was " + normalized);
		}
		return normalized;
	}
}
