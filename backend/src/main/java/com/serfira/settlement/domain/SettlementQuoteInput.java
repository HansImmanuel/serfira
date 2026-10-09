package com.serfira.settlement.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Pure input to {@link SettlementQuoteEngine} (E1, task T12, ADR-018). Everything the engine needs to
 * price a quote, with no Spring, JPA or Clock dependency: the business date to price for, the config
 * constants, and the per-installment amounts.
 *
 * @param settlementDate the business date the quote prices for (drives the active-period split, D7)
 * @param contractStartDate the contract's effective start date; the ACT/30 window of the first period
 *                       begins here (every later period's window begins at the preceding due date)
 * @param rebateRate     {@code SETTLEMENT_REBATE_RATE} fraction (e.g. 0.5000 for 50%), {@code >= 0}
 * @param adminFee       {@code SETTLEMENT_ADMIN_FEE}, scale-2 money, {@code >= 0}
 * @param availableCredit the contract's available customer credit, scale-2 money, {@code >= 0}; snapshotted
 *                        into the quote, not consumed by it (consumption is T13)
 * @param installments   the full schedule in period order, future installments included
 */
public record SettlementQuoteInput(LocalDate settlementDate, LocalDate contractStartDate, BigDecimal rebateRate,
		BigDecimal adminFee, BigDecimal availableCredit, List<SettlementInstallmentInput> installments) {

	public SettlementQuoteInput {
		installments = List.copyOf(installments);
	}
}
