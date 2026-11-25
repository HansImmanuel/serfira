import { QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, apiFetch: vi.fn() };
});

import { useCreatePayment } from "@/hooks/usePayment";
import { ApiError, apiFetch } from "@/lib/api";
import type { Payment, PaymentPayload } from "@/types/payment";

import { createTestQueryClient } from "../test/utils";

const PAYLOAD: PaymentPayload = { contract_id: "c1", amount: "1500000.00", channel: "CASH" };

const PAYMENT: Payment = {
  id: "p1",
  payment_no: "PAY-202611-0001",
  contract_id: "c1",
  amount: "1500000.00",
  channel: "CASH",
  paid_at: "2026-11-25T10:00:00+07:00",
  status: "POSTED",
  excess_amount: "0.00",
  allocations: [{ installment_id: "i1", allocation_type: "PRINCIPAL", amount: "1500000.00" }],
};

const SEEDED_KEYS = [
  ["contract", "c1"],
  ["contract", "c1", "installments"],
  ["contract", "c1", "credit"],
  ["contract", "c1", "statement", 0, { refType: null, from: null, to: null }],
  ["contracts", { page: 0 }],
] as const;

function setup() {
  const queryClient = createTestQueryClient();
  for (const key of SEEDED_KEYS) {
    queryClient.setQueryData(key, { seeded: true });
  }
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  const hook = renderHook(() => useCreatePayment("c1"), { wrapper });
  return { queryClient, hook };
}

describe("useCreatePayment", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
  });

  it("posts to /payments with the Idempotency-Key header and JSON body", async () => {
    vi.mocked(apiFetch).mockResolvedValue(PAYMENT);
    const { hook } = setup();

    await act(() => hook.result.current.mutateAsync({ payload: PAYLOAD, idempotencyKey: "key-1" }));

    expect(apiFetch).toHaveBeenCalledWith("/payments", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: JSON.stringify(PAYLOAD),
    });
  });

  it("invalidates the contract prefix and the contract list on success", async () => {
    vi.mocked(apiFetch).mockResolvedValue(PAYMENT);
    const { hook, queryClient } = setup();

    await act(() => hook.result.current.mutateAsync({ payload: PAYLOAD, idempotencyKey: "key-1" }));

    for (const key of SEEDED_KEYS) {
      expect(queryClient.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(true);
    }
  });

  it("does not invalidate anything when the payment is rejected", async () => {
    vi.mocked(apiFetch).mockRejectedValue(new ApiError("CONTRACT_STATE_INVALID", "Kontrak tidak aktif", 409));
    const { hook, queryClient } = setup();

    await act(async () => {
      await expect(
        hook.result.current.mutateAsync({ payload: PAYLOAD, idempotencyKey: "key-1" }),
      ).rejects.toBeInstanceOf(ApiError);
    });

    for (const key of SEEDED_KEYS) {
      expect(queryClient.getQueryState(key)?.isInvalidated, JSON.stringify(key)).toBe(false);
    }
  });
});
