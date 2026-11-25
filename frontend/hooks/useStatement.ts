"use client";

import { useQuery } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { PageResponse } from "@/types/api";
import type { LedgerRefType, StatementRow } from "@/types/contract";

export const STATEMENT_PAGE_SIZE = 20;

/** Server-side statement filters; dates are inclusive Asia/Jakarta business dates (`YYYY-MM-DD`). */
export interface StatementFilters {
  refType: LedgerRefType | null;
  from: string | null;
  to: string | null;
}

export const NO_STATEMENT_FILTERS: StatementFilters = { refType: null, from: null, to: null };

/** `GET /contracts/{id}/statement` with paging first and only the filters that are set. */
export function statementPath(id: string, page: number, filters: StatementFilters): string {
  const params = new URLSearchParams({ page: String(page), size: String(STATEMENT_PAGE_SIZE) });
  if (filters.refType !== null) {
    params.set("ref_type", filters.refType);
  }
  if (filters.from !== null) {
    params.set("from", filters.from);
  }
  if (filters.to !== null) {
    params.set("to", filters.to);
  }
  return `/contracts/${id}/statement?${params.toString()}`;
}

/**
 * Ledger-literal statement page (FE §2.7). Filtering and paging are the API's (`ref_type`, `from`,
 * `to`, `page`, `size`), never done in the browser.
 *
 * <p>Previous results are deliberately not kept as placeholder data: this is a financial ledger, and rows
 * from another filter or page must never stand in for the one being loaded. Each new key therefore renders
 * as pending until its own response arrives.
 *
 * <p>The key stays under `["contract", id]`, so a payment or credit application invalidates every
 * cached page and filter. `enabled` lets the caller suppress the request for a role the matcher
 * table denies; the page then renders an access-denied state instead of provoking a 403.
 */
export function useStatement(id: string, page: number, filters: StatementFilters, enabled = true) {
  return useQuery({
    queryKey: ["contract", id, "statement", page, filters],
    queryFn: () => apiFetch<PageResponse<StatementRow>>(statementPath(id, page, filters)),
    enabled: id.length > 0 && enabled,
  });
}
