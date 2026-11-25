"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { apiFetch } from "@/lib/api";
import type { Payment, PaymentPayload } from "@/types/payment";

export interface CreatePaymentCommand {
  payload: PaymentPayload;
  idempotencyKey: string;
}

/**
 * Posts a payment (FE §2.6). The caller owns the `Idempotency-Key` (one per form mount, ADR-017), so
 * a retry after a timeout replays the original payment instead of receiving the money twice.
 *
 * <p>On success the whole `["contract", id]` prefix is invalidated — detail, installments, credit and
 * every statement page/filter live under it — plus the contract list, so no screen keeps a balance
 * from before the payment.
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
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["contract", contractId] }),
        queryClient.invalidateQueries({ queryKey: ["contracts"] }),
      ]);
    },
  });
}
