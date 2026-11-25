import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, apiFetch: vi.fn() };
});

vi.mock("@/lib/idempotency", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/idempotency")>();
  return { ...actual, newIdempotencyKey: vi.fn() };
});

import { RecordPaymentForm } from "@/components/payments/RecordPaymentForm";
import { ApiError, apiFetch } from "@/lib/api";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { Payment } from "@/types/payment";

import { renderWithQueryClient } from "../../test/utils";

const PAYMENT: Payment = {
  id: "p1",
  payment_no: "PAY-202611-0001",
  contract_id: "c1",
  amount: "1500000.00",
  channel: "BANK_TRANSFER",
  paid_at: "2026-11-25T10:00:00+07:00",
  status: "POSTED",
  excess_amount: "0.00",
  allocations: [{ installment_id: "i1", allocation_type: "PRINCIPAL", amount: "1500000.00" }],
};

function renderForm() {
  const onPosted = vi.fn();
  const onRestart = vi.fn();
  renderWithQueryClient(<RecordPaymentForm contractId="c1" onPosted={onPosted} onRestart={onRestart} />);
  return { onPosted, onRestart, user: userEvent.setup() };
}

function sentKey(callIndex: number): string | undefined {
  const options = vi.mocked(apiFetch).mock.calls[callIndex]?.[1];
  return options?.headers?.["Idempotency-Key"];
}

describe("RecordPaymentForm", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  it("shows an inline error and does not submit for an invalid amount", async () => {
    const { user } = renderForm();

    await user.type(screen.getByLabelText("Jumlah Bayar"), "0");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    expect(await screen.findByText(/Jumlah bayar harus lebih dari 0/)).toBeInTheDocument();
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it("submits contract_id, the normalized amount and the channel only", async () => {
    vi.mocked(apiFetch).mockResolvedValue(PAYMENT);
    const { user, onPosted } = renderForm();

    await user.type(screen.getByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("combobox", { name: "Channel" }));
    await user.click(await screen.findByRole("option", { name: "Transfer bank" }));
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    await vi.waitFor(() => expect(onPosted).toHaveBeenCalledWith(PAYMENT));
    expect(vi.mocked(apiFetch).mock.calls[0]?.[1]?.body).toBe(
      JSON.stringify({ contract_id: "c1", amount: "1500000.00", channel: "BANK_TRANSFER" }),
    );
  });

  it("reuses the same Idempotency-Key when retrying after a failure", async () => {
    vi.mocked(apiFetch).mockRejectedValueOnce(new TypeError("Failed to fetch")).mockResolvedValueOnce(PAYMENT);
    const { user, onPosted } = renderForm();

    await user.type(screen.getByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));
    expect(await screen.findByText("Failed to fetch")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    await vi.waitFor(() => expect(onPosted).toHaveBeenCalledWith(PAYMENT));
    expect(apiFetch).toHaveBeenCalledTimes(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
  });

  it.each([
    ["CONFLICT", /Formulir ini sudah dipakai untuk pembayaran dengan isian berbeda/],
    ["IDEMPOTENCY_KEY_EXPIRED", /Kunci formulir ini sudah tidak berlaku, dan pembayaran sebelumnya mungkin sudah tercatat/],
  ])(
    "on %s shows the matching key message and a restart action instead of retrying",
    async (code, message) => {
      vi.mocked(apiFetch).mockRejectedValue(new ApiError(code, "Idempotency key conflict", 409));
      const { user, onRestart } = renderForm();

      await user.type(screen.getByLabelText("Jumlah Bayar"), "1500000");
      await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

      expect(await screen.findByText(message)).toBeInTheDocument();
      expect(screen.getByText(/Periksa rekening koran kontrak lebih dulu/)).toBeInTheDocument();
      // An expired key does not prove the body differed, so only CONFLICT may claim "different values".
      if (code === "IDEMPOTENCY_KEY_EXPIRED") {
        expect(screen.queryByText(/isian berbeda/)).not.toBeInTheDocument();
      }
      expect(screen.getByRole("button", { name: "Simpan Pembayaran" })).toBeDisabled();

      await user.click(screen.getByRole("button", { name: "Mulai form baru" }));
      expect(onRestart).toHaveBeenCalledTimes(1);
    },
  );

  it("disables submit while the payment is pending", async () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}));
    const { user } = renderForm();

    await user.type(screen.getByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    expect(await screen.findByRole("button", { name: "Menyimpan…" })).toBeDisabled();
  });
});
