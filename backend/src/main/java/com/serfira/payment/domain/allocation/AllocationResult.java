package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of one payment allocation (ADR-009): the ordered lines of {@link AllocationLine} plus the excess
 * that could not be applied to any due installment.
 *
 * <p>The invariant enforced here is domain model invariant 6 — a posted payment always has at least one
 * allocation and {@code Σ allocations = payment amount} — expressed as
 * {@code Σ lines = totalAllocated}. That makes "money silently lost" or "money double-counted" impossible
 * before the result ever reaches the database.
 *
 * @param lines          allocation lines in allocation order (oldest due installment first, EXCESS last)
 * @param excessAmount   amount held as customer credit; {@code > 0} exactly when the result has one EXCESS line
 * @param totalAllocated payment amount the lines add up to
 */
public record AllocationResult(List<AllocationLine> lines, BigDecimal excessAmount,
		BigDecimal totalAllocated) {

	public AllocationResult {
		Objects.requireNonNull(lines, "lines");
		Objects.requireNonNull(excessAmount, "excessAmount");
		Objects.requireNonNull(totalAllocated, "totalAllocated");
		if (lines.isEmpty() || lines.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("an allocation result needs at least one non-null line "
					+ "(invariant 6) but had " + lines.size());
		}
		lines = List.copyOf(lines);

		BigDecimal lineTotal = BigDecimal.ZERO;
		BigDecimal excessLines = BigDecimal.ZERO;
		for (AllocationLine line : lines) {
			lineTotal = lineTotal.add(line.amount());
			if (line.type() == AllocationType.EXCESS) {
				excessLines = excessLines.add(line.amount());
			}
		}
		if (lineTotal.compareTo(totalAllocated) != 0) {
			throw new IllegalArgumentException("allocation lines total " + lineTotal
					+ " but the payment amount is " + totalAllocated);
		}
		if (excessLines.compareTo(excessAmount) != 0) {
			throw new IllegalArgumentException("EXCESS lines total " + excessLines
					+ " but excessAmount is " + excessAmount);
		}
	}

	/** Builds a result from its lines, deriving the excess and the total (invariant 6). */
	public static AllocationResult of(List<AllocationLine> lines) {
		BigDecimal total = BigDecimal.ZERO;
		BigDecimal excess = BigDecimal.ZERO;
		for (AllocationLine line : lines) {
			total = total.add(line.amount());
			if (line.type() == AllocationType.EXCESS) {
				excess = excess.add(line.amount());
			}
		}
		return new AllocationResult(lines, excess, total);
	}

	/** Lines applied to one installment, in allocation order (empty when the payment did not reach it). */
	public List<AllocationLine> linesForInstallment(UUID installmentRef) {
		return lines.stream().filter(line -> installmentRef.equals(line.installmentRef())).toList();
	}

	/** Amount allocated to one component of one installment; {@code 0.00} when there is no such line. */
	public BigDecimal amountFor(UUID installmentRef, AllocationType type) {
		return lines.stream()
				.filter(line -> line.type() == type && installmentRef.equals(line.installmentRef()))
				.map(AllocationLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}

	/** Total allocated to one installment across components; {@code 0.00} when the payment did not reach it. */
	public BigDecimal amountForInstallment(UUID installmentRef) {
		return linesForInstallment(installmentRef).stream()
				.map(AllocationLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(2, RoundingMode.UNNECESSARY);
	}
}
