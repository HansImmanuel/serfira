package com.serfira.reporting.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The management metrics of PRD R-1 on one business date, as ADR-026 defines them. Money is scale-2; a ratio is
 * a scale-4 fraction rounded HALF_UP, or {@code null} when its denominator is zero (ADR-026 D7).
 *
 * @param asOf                the business date (today only; ADR-013 A-6)
 * @param totalOutstanding    D2: Σ outstanding of every installment of every ACTIVE contract
 * @param accountsReceivable  D3: Σ outstanding of ACTIVE-contract installments with {@code due_date <= as_of}
 * @param nplContractCount    D4: ACTIVE contracts with an owed installment more than 90 days past due
 * @param nplOutstanding      D4: Σ outstanding of every installment of the NPL contracts
 * @param nplRatio            D4: {@code nplOutstanding / totalOutstanding}, or {@code null}
 * @param periodFrom          D5: first day of {@code asOf}'s month
 * @param periodTo            D5: {@code asOf}
 * @param collected           D5: Σ POSTED non-EXCESS payment allocation on the cohort
 * @param due                 D5: Σ over the cohort of {@code recognized_total − settled_amount}
 * @param collectionRate      D5: {@code collected / due}, or {@code null}
 * @param settlementCash      D6: Σ {@code cash_received} of settlements executed in the period
 * @param activeContractCount ACTIVE contracts with outstanding; equals the aging report's row count
 */
public record OutstandingMetrics(
		LocalDate asOf,
		BigDecimal totalOutstanding,
		BigDecimal accountsReceivable,
		long nplContractCount,
		BigDecimal nplOutstanding,
		BigDecimal nplRatio,
		LocalDate periodFrom,
		LocalDate periodTo,
		BigDecimal collected,
		BigDecimal due,
		BigDecimal collectionRate,
		BigDecimal settlementCash,
		long activeContractCount) {
}
