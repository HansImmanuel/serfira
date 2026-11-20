"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { PageResponse } from "@/types/api";
import type { StatementRow } from "@/types/contract";

export const STATEMENT_PAGE_SIZE = 20;

/** Ledger-literal statement page (FE §2.7). Filters and ref-type wiring land with T33. */
export function useStatement(id: string, page: number) {
  return useQuery({
    queryKey: ["contract", id, "statement", page],
    queryFn: () =>
      apiFetch<PageResponse<StatementRow>>(
        `/contracts/${id}/statement?page=${page}&size=${STATEMENT_PAGE_SIZE}`,
      ),
    placeholderData: keepPreviousData,
    enabled: id.length > 0,
  });
}
