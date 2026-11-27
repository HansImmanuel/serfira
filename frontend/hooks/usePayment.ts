"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { ContractPayment, Payment, PaymentPayload } from "@/types/payment";

export interface CreatePaymentCommand {
  payload: PaymentPayload;
  idempotencyKey: string;
}

/**
 * Posts a payment (FE §2.6). The caller owns the `Idempotency-Key` (one per form mount, ADR-017), so
 * a retry after a timeout replays the original payment instead of receiving the money twice.
 *
 * <p>The whole `["contract", id]` prefix is invalidated — detail, installments, credit and every
 * statement page/filter live under it — plus the contract list, once the call settles, success or not.
 * A payment whose response was lost (network error, 5xx) may still have committed, so no screen may keep
 * a balance from before it; the retry that follows reuses the same key and replays the stored payment.
 * The returned promise is awaited, so `mutateAsync` resolves only after the refetch has finished.
 */
export function useCreatePayment(contractId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ payload, idempotencyKey }: CreatePaymentCommand) =>
      apiFetch<Payment>("/payments", {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(payload),
      }),
    onSettled: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["contract", contractId] }),
        queryClient.invalidateQueries({ queryKey: ["contracts"] }),
      ]);
    },
  });
}

export const contractPaymentsKey = (id: string) => ["contract", id, "payments"] as const;

/**
 * The contract's payment history (Pembayaran tab). It lives under the `["contract", id]` prefix, so a
 * payment or any other mutation that invalidates the prefix refreshes it. Readable by
 * `ADMIN_OPERASIONAL`/`FINANCE` only (ADR-015), hence `enabled`.
 */
export function useContractPayments(contractId: string, enabled: boolean) {
  return useQuery({
    queryKey: contractPaymentsKey(contractId),
    queryFn: () => apiFetch<ContractPayment[]>(`/contracts/${contractId}/payments`),
    enabled: contractId.length > 0 && enabled,
  });
}
