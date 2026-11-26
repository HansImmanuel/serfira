"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { AgingReport, OutstandingReport } from "@/types/report";

export const REPORT_PAGE_SIZE = 20;

/**
 * `GET /reports/aging` for one page of per-contract rows. `as_of` is never sent: the backend defaults it
 * to its own today (Asia/Jakarta) and rejects any other date (ADR-013 A-6), so a browser in another zone
 * cannot ask for the wrong day.
 */
export function agingReportPath(page: number): string {
  const params = new URLSearchParams({ page: String(page), size: String(REPORT_PAGE_SIZE) });
  return `/reports/aging?${params.toString()}`;
}

/**
 * The aging report, one page at a time. The previous response is kept as placeholder data so the
 * page-independent values (`portfolio`, `as_of`) stay on screen while another page loads; the table must
 * render rows as skeletons while `isPlaceholderData`, so another page's rows are never shown as this one's.
 */
export function useAgingReport(page: number, enabled = true) {
  return useQuery({
    queryKey: ["reports", "aging", page],
    queryFn: () => apiFetch<AgingReport>(agingReportPath(page)),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** The outstanding report's metrics (T36, ADR-026), for today. */
export function useOutstandingReport(enabled = true) {
  return useQuery({
    queryKey: ["reports", "outstanding"],
    queryFn: () => apiFetch<OutstandingReport>("/reports/outstanding"),
    enabled,
  });
}
