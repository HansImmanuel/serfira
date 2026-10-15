package com.serfira.penalty.domain.consistency;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One failed reconciliation check for one contract (F1, Addendum §7.1/§7.2): the drift the daily
 * consistency step records as a {@code reconciliation_exception} and never fixes.
 *
 * <p>Produced only when {@code expected != actual}; a check that balances yields no discrepancy. The three
 * amounts map directly to the {@code reconciliation_exception} columns of the same name, scale-2 money,
 * sign preserved so an operator can see the direction of the drift.
 *
 * @param type     which reconciliation check failed
 * @param expected the figure the schedule/business state says is correct, scale-2
 * @param actual   the figure actually found (ledger balance, allocation sum, …), scale-2
 * @param diff     {@code expected − actual}, scale-2; non-zero by construction
 */
public record ConsistencyDiscrepancy(ConsistencyCheckType type, BigDecimal expected, BigDecimal actual,
		BigDecimal diff) {

	public ConsistencyDiscrepancy {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(expected, "expected");
		Objects.requireNonNull(actual, "actual");
		Objects.requireNonNull(diff, "diff");
	}
}
