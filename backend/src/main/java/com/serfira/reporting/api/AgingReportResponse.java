package com.serfira.reporting.api;

import com.serfira.shared.api.PageResponse;

import java.time.LocalDate;

/**
 * The aging report (PRD D-2, 06_FRONTEND_SPEC.md §2.9): the portfolio total split across the five
 * delinquency buckets, plus a page of per-contract rows.
 *
 * <p>The portfolio buckets are the sum over every ACTIVE contract (not only the current page), so
 * {@code portfolio.total_outstanding} is the whole book's outstanding regardless of paging. Each page row
 * carries one contract's own breakdown. At both levels {@code Σ bucket = outstanding} (ADR-013 A-7).
 *
 * @param asOf      business date the report was computed for (today only; ADR-013 A-6)
 * @param portfolio portfolio-wide buckets and total across all ACTIVE contracts
 * @param contracts one page of per-contract rows
 */
public record AgingReportResponse(
		LocalDate asOf,
		AgingBucketsResponse portfolio,
		PageResponse<ContractAgingRow> contracts) {
}
