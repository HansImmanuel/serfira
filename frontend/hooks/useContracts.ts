"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { PageResponse } from "@/types/api";
import type { ContractListItem, ContractStatus } from "@/types/contract";

export const CONTRACT_PAGE_SIZE = 20;

export interface ContractListFilters {
  status: ContractStatus | "ALL";
  query: string;
  page: number;
  size?: number;
}

/** The list request path (FE §2.2): status filter, free text over number/customer, newest first. */
export function contractListPath(filters: ContractListFilters): string {
  const params = new URLSearchParams();
  if (filters.status !== "ALL") {
    params.set("status", filters.status);
  }
  const query = filters.query.trim();
  if (query.length > 0) {
    params.set("q", query);
  }
  params.set("page", String(filters.page));
  params.set("size", String(filters.size ?? CONTRACT_PAGE_SIZE));
  params.set("sort", "created_at,desc");
  return `/contracts?${params.toString()}`;
}

export function useContracts(filters: ContractListFilters) {
  return useQuery({
    queryKey: ["contracts", filters],
    queryFn: () => apiFetch<PageResponse<ContractListItem>>(contractListPath(filters)),
    placeholderData: keepPreviousData,
  });
}
