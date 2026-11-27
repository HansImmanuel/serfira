"use client";

import { useMutation, useQueryClient, type QueryClient } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { SettlementExecution, SettlementQuote } from "@/types/settlement";

/**
 * The whole `["contract", id]` prefix (detail, installments, credit, payments, statement) plus the contract
 * list, once a settlement call settles, success or not.
 */
async function invalidateContract(queryClient: QueryClient, contractId: string) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ["contract", contractId] }),
    queryClient.invalidateQueries({ queryKey: ["contracts"] }),
  ]);
}

/**
 * Prices an early settlement (`ADMIN_OPERASIONAL` only, ADR-015). There is no `Idempotency-Key`: each call
 * is a new quote. The quote is not read-only — the server bills due interest and accrues due penalty before
 * pricing (ADR-018 D8) — so the contract prefix is invalidated as for any write (T39 decision 9).
 */
export function useSettlementQuote(contractId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      apiFetch<SettlementQuote>("/settlements/quote", {
        method: "POST",
        body: JSON.stringify({ contract_id: contractId }),
      }),
    onSettled: () => invalidateContract(queryClient, contractId),
  });
}

export interface ExecuteSettlementCommand {
  quoteId: string;
  idempotencyKey: string;
}

/** Mutation key of the execute call, so the dialog can refuse to close while one is in flight. */
export const executeSettlementKey = (contractId: string) => ["settlements", "execute", contractId] as const;

/**
 * Executes a quote (ADR-018 D4/D5). The caller owns the `Idempotency-Key`, one per quote (T39 decision 7):
 * a retry after a lost response replays the stored settlement instead of settling twice. A settlement whose
 * response was lost may still have closed the contract, so the prefix is invalidated whatever the outcome.
 */
export function useExecuteSettlement(contractId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: executeSettlementKey(contractId),
    mutationFn: ({ quoteId, idempotencyKey }: ExecuteSettlementCommand) =>
      apiFetch<SettlementExecution>("/settlements", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify({ quote_id: quoteId }),
      }),
    onSettled: () => invalidateContract(queryClient, contractId),
  });
}
