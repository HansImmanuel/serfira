package com.serfira.contract.domain.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of applying available credit to a contract's installments (task T14): the ordered lines,
 * the total actually applied, and the credit left unapplied because no due installment could absorb it.
 *
 * <p>{@code appliedTotal + unapplied = requestedAmount}. A result with {@code appliedTotal == 0} means
 * nothing was applicable (no due installment with recognized residual); the service turns that into a
 * 409 {@code CREDIT_NOT_APPLICABLE} and writes nothing (Addendum §2.4).
 */
public record CreditAllocationResult(List<CreditAllocationLine> lines, BigDecimal appliedTotal,
		BigDecimal unapplied) {

	private static final int MONEY_SCALE = 2;

	public CreditAllocationResult {
		Objects.requireNonNull(lines, "lines");
		Objects.requireNonNull(appliedTotal, "appliedTotal");
		Objects.requireNonNull(unapplied, "unapplied");
		lines = List.copyOf(lines);
		appliedTotal = appliedTotal.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		unapplied = unapplied.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	/** Total applied to a single installment across its components, {@code 0.00} if none. */
	public BigDecimal amountForInstallment(UUID installmentRef) {
		return lines.stream()
				.filter(line -> line.installmentRef().equals(installmentRef))
				.map(CreditAllocationLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}

	/** Total applied to a single component across all installments, {@code 0.00} if none. */
	public BigDecimal amountForComponent(CreditComponent component) {
		return lines.stream()
				.filter(line -> line.component() == component)
				.map(CreditAllocationLine::amount)
				.reduce(BigDecimal.ZERO, BigDecimal::add)
				.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
	}
}
