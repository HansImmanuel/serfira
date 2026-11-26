package com.serfira.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The {@code settlement} module's read seam for settlement cash (T36, ADR-026 D6/D8): the outstanding report's
 * separate settlement-cash metric. {@code reporting} never reads the {@code settlement} table itself.
 */
public interface SettlementCashPort {

	/**
	 * {@code Σ cash_received} of the settlements whose {@code executed_at}, as a business date in the
	 * application zone (Asia/Jakarta), falls in {@code [from, to]} (both inclusive). {@code credit_used} is not
	 * cash and is excluded.
	 *
	 * @param from first business date, inclusive
	 * @param to   last business date, inclusive; not before {@code from}
	 * @return scale-2 money, {@code >= 0}
	 */
	BigDecimal sumCashReceived(LocalDate from, LocalDate to);
}
