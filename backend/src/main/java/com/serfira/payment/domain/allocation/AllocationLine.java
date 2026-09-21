package com.serfira.payment.domain.allocation;

import com.serfira.payment.domain.AllocationType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One allocation row produced by {@link PaymentAllocationEngine} — the domain form of a
 * {@code payment_allocation} row (03_DOMAIN_MODEL.md §1.8).
 *
 * <p>The guards here mirror the database constraints of the same table, so an invalid line can never be
 * built in the first place: {@code ck_payment_allocation_amount} ({@code amount > 0}, which is why the
 * engine never emits a zero line) and {@code ck_payment_allocation_excess}
 * ({@code (type = 'EXCESS') = (installment_id IS NULL)}).
 *
 * <p>Amounts are scale-2 money; a value that cannot be represented at scale 2 (sub-cent money) is rejected
 * rather than silently rounded (ADR-009 decision 7).
 *
 * @param type           component this line resolves
 * @param installmentRef installment the amount is applied to; {@code null} exactly for {@link AllocationType#EXCESS}
 * @param amount         scale-2 amount, always {@code > 0}
 */
public record AllocationLine(AllocationType type, UUID installmentRef, BigDecimal amount) {

	private static final int MONEY_SCALE = 2;

	public AllocationLine {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(amount, "amount");
		amount = amount.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
		if (amount.signum() <= 0) {
			throw new IllegalArgumentException("allocation amount must be > 0 but was " + amount);
		}
		if ((type == AllocationType.EXCESS) != (installmentRef == null)) {
			throw new IllegalArgumentException("only an EXCESS allocation may omit the installment, but got "
					+ type + " with installmentRef = " + installmentRef);
		}
	}
}
