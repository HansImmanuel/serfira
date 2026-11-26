"use client";

import { useQuery } from "@tanstack/react-query";

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
 * The aging report, one page at a time. No placeholder data across pages: like the statement, a financial
 * table never shows another page's rows while its own are loading.
 */
export function useAgingReport(page: number, enabled = true) {
  return useQuery({
    queryKey: ["reports", "aging", page],
    queryFn: () => apiFetch<AgingReport>(agingReportPath(page)),
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
