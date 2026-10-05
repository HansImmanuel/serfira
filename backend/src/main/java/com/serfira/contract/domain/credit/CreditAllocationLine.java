package com.serfira.contract.domain.credit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of a credit application: {@code amount} of {@code installmentRef}'s {@code component} resolved
 * by applied credit (task T14, mirrors {@code payment}'s {@code AllocationLine}).
 *
 * <p>Unlike a payment allocation line there is no EXCESS variant — every line carries an installment and
 * a receivable component, because unapplied credit stays on the credit balance rather than becoming a new
 * excess (Addendum §2.4).
 */
public record CreditAllocationLine(CreditComponent component, UUID installmentRef, BigDecimal amount) {

	private static final int MONEY_SCALE = 2;

	public CreditAllocationLine {
		Objects.requireNonNull(component, "component");
		Objects.requireNonNull(installmentRef, "installmentRef");
		Objects.requireNonNull(amount, "amount");
		amount = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("a credit allocation line must be > 0 but was " + amount);
		}
	}
}
