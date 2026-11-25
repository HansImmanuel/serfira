"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { CreateContractPayload, Contract, CreditBalance, Installment } from "@/types/contract";

const contractKey = (id: string) => ["contract", id] as const;

export function useContract(id: string) {
  return useQuery({
    queryKey: contractKey(id),
    queryFn: () => apiFetch<Contract>(`/contracts/${id}`),
    enabled: id.length > 0,
  });
}

/** Schedule rows exist only after activation; a DRAFT contract returns an empty list anyway. */
export function useInstallments(id: string, enabled = true) {
  return useQuery({
    queryKey: [...contractKey(id), "installments"],
    queryFn: () => apiFetch<Installment[]>(`/contracts/${id}/installments`),
    enabled: id.length > 0 && enabled,
  });
}

/** Credit is readable by `ADMIN_OPERASIONAL`/`FINANCE` only (ADR-015 matcher table). */
export function useCredit(id: string, enabled = true) {
  return useQuery({
    queryKey: [...contractKey(id), "credit"],
    queryFn: () => apiFetch<CreditBalance>(`/contracts/${id}/credit`),
    enabled: id.length > 0 && enabled,
  });
}

/**
 * Activation sends no body: the effective start date is the contract's `planned_start_date`
 * (ADR-006, FE §2.4). It is idempotent by state, so no `Idempotency-Key` is involved.
 */
export function useActivateContract(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiFetch<Contract>(`/contracts/${id}/activate`, { method: "POST" }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: contractKey(id) }),
        queryClient.invalidateQueries({ queryKey: [...contractKey(id), "installments"] }),
        queryClient.invalidateQueries({ queryKey: ["contracts"] }),
      ]);
    },
  });
}

export interface CreateContractCommand {
  payload: CreateContractPayload;
  idempotencyKey: string;
}

export function useCreateContract() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ payload, idempotencyKey }: CreateContractCommand) =>
      apiFetch<Contract>("/contracts", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(payload),
      }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
    },
  });
}
