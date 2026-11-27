import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { PaymentHistoryTable } from "@/components/payments/PaymentHistoryTable";
import { formatDateTime } from "@/lib/format";
import type { Installment } from "@/types/contract";
import type { ContractPayment } from "@/types/payment";

function installment(id: string, period_no: number): Installment {
  return {
    id,
    period_no,
    due_date: "2026-12-01",
    principal_amount: "1000000.00",
    interest_amount: "100000.00",
    recognized_interest_amount: "100000.00",
    penalty_amount: "0.00",
    paid_amount: "0.00",
    settled_amount: "0.00",
    written_off_amount: "0.00",
    status: "PENDING",
    outstanding: "1100000.00",
  };
}

const INSTALLMENTS = [installment("inst-1", 1), installment("inst-2", 2)];

const POSTED: ContractPayment = {
  id: "pay-posted",
  payment_no: "PAY-0002",
  amount: "2500000.00",
  channel: "BANK_TRANSFER",
  paid_at: "2026-11-20T10:00:00+07:00",
  status: "POSTED",
  excess_amount: "50000.00",
  voided_at: null,
  void_reason: null,
  // Deliberately scrambled: the API gives allocations in no defined order.
  allocations: [
    { installment_id: null, allocation_type: "EXCESS", amount: "50000.00" },
    { installment_id: "inst-2", allocation_type: "PRINCIPAL", amount: "800000.00" },
    { installment_id: "inst-1", allocation_type: "PRINCIPAL", amount: "1000000.00" },
    { installment_id: "inst-1", allocation_type: "INTEREST", amount: "100000.00" },
    { installment_id: "inst-2", allocation_type: "INTEREST", amount: "100000.00" },
    { installment_id: "inst-1", allocation_type: "PENALTY", amount: "5000.00" },
  ],
};

const VOIDED: ContractPayment = {
  id: "pay-voided",
  payment_no: "PAY-0001",
  amount: "1000000.00",
  channel: "CASH",
  paid_at: "2026-11-10T09:00:00+07:00",
  status: "VOIDED",
  excess_amount: "0.00",
  voided_at: "2026-11-11T08:30:00+07:00",
  void_reason: "Salah input nominal",
  allocations: [],
};

describe("PaymentHistoryTable", () => {
  it("lists payments as returned with a VOIDED badge, the void time and reason", () => {
    render(<PaymentHistoryTable payments={[POSTED, VOIDED]} installments={INSTALLMENTS} highlightId={null} />);

    const rows = screen.getAllByRole("row").slice(1);
    expect(rows.map((row) => row.id)).toEqual(["payment-pay-posted", "payment-pay-voided"]);
    const voidedRow = rows[1]!;
    expect(within(voidedRow).getByText("VOIDED")).toBeInTheDocument();
    expect(within(voidedRow).getByText(new RegExp(formatDateTime(VOIDED.voided_at!)))).toBeInTheDocument();
    expect(within(voidedRow).getByText(/Salah input nominal/)).toBeInTheDocument();
    expect(within(rows[0]!).queryByText("VOIDED")).not.toBeInTheDocument();
  });

  it("highlights the linked payment row", () => {
    render(<PaymentHistoryTable payments={[POSTED, VOIDED]} installments={INSTALLMENTS} highlightId="pay-voided" />);

    const highlighted = document.querySelectorAll('[data-highlighted="true"]');
    expect(highlighted).toHaveLength(1);
    expect(highlighted[0]).toHaveAttribute("id", "payment-pay-voided");
  });

  it("expands allocation lines in period order with EXCESS last", async () => {
    render(<PaymentHistoryTable payments={[POSTED]} installments={INSTALLMENTS} highlightId={null} />);

    const toggle = screen.getByRole("button", { name: "Rincian" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");

    const lines = screen.getAllByRole("listitem").map((item) => item.textContent ?? "");
    const order = lines.map((text) => {
      const period = text.includes("Angsuran ke-1") ? "1" : text.includes("Angsuran ke-2") ? "2" : "X";
      const type = ["Denda", "Bunga", "Pokok", "Kelebihan"].find((label) => text.includes(label));
      return `${period}-${type}`;
    });
    expect(order).toEqual(["1-Denda", "1-Bunga", "1-Pokok", "2-Bunga", "2-Pokok", "X-Kelebihan"]);
  });

  it("shows the empty message", () => {
    render(<PaymentHistoryTable payments={[]} installments={INSTALLMENTS} highlightId={null} />);

    expect(screen.getByText("Belum ada pembayaran untuk kontrak ini.")).toBeInTheDocument();
  });
});
