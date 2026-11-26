package com.serfira.reporting.api;

import com.serfira.reporting.application.OutstandingMetrics;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The outstanding report (PRD R-1, 06_FRONTEND_SPEC.md §2.10): the management metrics as ADR-026 defines them.
 * Money is an exact decimal string (ADR-024). A ratio is a decimal-string fraction at scale 4 ({@code "0.0150"}
 * = 1.5%), or JSON {@code null} when its denominator is zero (ADR-026 D7).
 *
 * @param asOf                business date the report was computed for (today only; ADR-013 A-6)
 * @param totalOutstanding    ADR-026 D2; equals the aging report's portfolio {@code total_outstanding}
 * @param accountsReceivable  ADR-026 D3: outstanding of installments already due, grace included
 * @param npl                 ADR-026 D4: whole-contract non-performing loans
 * @param collection          ADR-026 D5: month-to-date collection rate on the due-date cohort
 * @param settlementCash      ADR-026 D6: settlement cash received month to date
 * @param activeContractCount ACTIVE contracts with outstanding; equals the aging report's
 *                            {@code contracts.total_elements}
 */
public record OutstandingReportResponse(
		LocalDate asOf,
		BigDecimal totalOutstanding,
		BigDecimal accountsReceivable,
		NplSummary npl,
		CollectionSummary collection,
		SettlementCashSummary settlementCash,
		long activeContractCount) {

	public static OutstandingReportResponse from(OutstandingMetrics metrics) {
		return new OutstandingReportResponse(
				metrics.asOf(),
				metrics.totalOutstanding(),
				metrics.accountsReceivable(),
				new NplSummary(metrics.nplContractCount(), metrics.nplOutstanding(), metrics.nplRatio()),
				new CollectionSummary(metrics.periodFrom(), metrics.periodTo(), metrics.collected(), metrics.due(),
						metrics.collectionRate()),
				new SettlementCashSummary(metrics.periodFrom(), metrics.periodTo(), metrics.settlementCash()),
				metrics.activeContractCount());
	}

	/**
	 * @param contractCount ACTIVE contracts with an owed installment more than 90 days past due
	 * @param outstanding   Σ outstanding of every installment of those contracts, undue principal included
	 * @param ratio         {@code outstanding / total_outstanding}, or {@code null} when nothing is outstanding
	 */
	public record NplSummary(
			long contractCount,
			BigDecimal outstanding,
			@Schema(nullable = true, description = "Scale-4 fraction; null when total_outstanding is zero")
			BigDecimal ratio) {
	}

	/**
	 * @param periodFrom first day of {@code as_of}'s month
	 * @param periodTo   {@code as_of}
	 * @param collected  Σ POSTED payment allocation (penalty, interest, principal) on installments due in the period
	 * @param due        Σ over those installments of recognized total net of penalty adjustments, minus settled
	 * @param rate       {@code collected / due}, or {@code null} when nothing fell due
	 */
	public record CollectionSummary(
			LocalDate periodFrom,
			LocalDate periodTo,
			BigDecimal collected,
			BigDecimal due,
			@Schema(nullable = true, description = "Scale-4 fraction; null when due is zero")
			BigDecimal rate) {
	}

	/**
	 * @param periodFrom first day of {@code as_of}'s month
	 * @param periodTo   {@code as_of}
	 * @param amount     Σ {@code cash_received} of settlements executed in the period ({@code credit_used} excluded)
	 */
	public record SettlementCashSummary(LocalDate periodFrom, LocalDate periodTo, BigDecimal amount) {
	}
}
