import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, apiFetch: vi.fn() };
});

import { periodLabel } from "@/components/contracts/installmentLabels";
import { PaymentResult } from "@/components/payments/PaymentResult";
import { apiFetch } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";
import type { ContractStatus, Installment } from "@/types/contract";
import type { Payment } from "@/types/payment";

import { renderWithQueryClient } from "../../test/utils";

function installment(id: string, periodNo: number): Installment {
  return {
    id,
    period_no: periodNo,
    due_date: "2026-12-01",
    principal_amount: "1000000.00",
    interest_amount: "200000.00",
    recognized_interest_amount: "200000.00",
    penalty_amount: "10000.00",
    paid_amount: "0.00",
    settled_amount: "0.00",
    written_off_amount: "0.00",
    status: "OVERDUE",
    outstanding: "1210000.00",
  };
}

const INSTALLMENTS = [installment("i1", 1), installment("i2", 2)];

/** `formatMoney` uses Intl's no-break space; Testing Library normalizes node text to a plain space. */
function rupiah(value: string): string {
  return formatMoney(value).replace(/\s/g, " ");
}

const PAYMENT: Payment = {
  id: "p1",
  payment_no: "PAY-202611-0007",
  contract_id: "c1",
  amount: "260000.00",
  channel: "CASH",
  paid_at: "2026-11-25T10:15:00+07:00",
  status: "POSTED",
  excess_amount: "50000.00",
  allocations: [
    { installment_id: "i1", allocation_type: "PENALTY", amount: "10000.00" },
    { installment_id: "i1", allocation_type: "INTEREST", amount: "200000.00" },
    { installment_id: null, allocation_type: "EXCESS", amount: "50000.00" },
  ],
};

describe("periodLabel", () => {
  it("names the period of a known installment", () => {
    expect(periodLabel(INSTALLMENTS, "i2")).toBe("Angsuran ke-2");
  });

  it("names a null installment as customer credit", () => {
    expect(periodLabel(INSTALLMENTS, null)).toBe("Kredit nasabah");
  });

  it("falls back to the short id when the installment is unknown or not loaded", () => {
    expect(periodLabel(INSTALLMENTS, "abcdef12-3456-4789-8abc-def012345678")).toBe("abcdef12");
    expect(periodLabel(undefined, "abcdef12-3456-4789-8abc-def012345678")).toBe("abcdef12");
  });
});

describe("PaymentResult", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    vi.mocked(apiFetch).mockResolvedValue(INSTALLMENTS);
  });

  function renderResult(payment: Payment = PAYMENT, contractStatus: ContractStatus | null = "ACTIVE") {
    const onRecordAnother = vi.fn();
    renderWithQueryClient(
      <PaymentResult
        contractId="c1"
        payment={payment}
        onRecordAnother={onRecordAnother}
        contractStatus={contractStatus}
      />,
    );
    return { onRecordAnother, user: userEvent.setup() };
  }

  it("lists allocation lines in API order with period labels", async () => {
    renderResult();

    await screen.findAllByText("Angsuran ke-1");
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows).toHaveLength(3);
    expect(within(rows[0]).getByText("Angsuran ke-1")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Denda")).toBeInTheDocument();
    expect(within(rows[0]).getByText(rupiah("10000.00"))).toBeInTheDocument();
    expect(within(rows[1]).getByText("Bunga")).toBeInTheDocument();
    expect(within(rows[1]).getByText(rupiah("200000.00"))).toBeInTheDocument();
    expect(within(rows[2]).getByText("Kredit nasabah")).toBeInTheDocument();
    expect(within(rows[2]).getByText("Kelebihan")).toBeInTheDocument();
  });

  it("warns about excess credit when excess_amount is positive", () => {
    renderResult();

    expect(
      screen.getByText(`Kelebihan ${rupiah("50000.00")} disimpan sebagai kredit nasabah`, { exact: false }),
    ).toBeInTheDocument();
  });

  it("shows no excess warning when the payment was fully applied", () => {
    renderResult({ ...PAYMENT, excess_amount: "0.00", allocations: PAYMENT.allocations.slice(0, 2) });

    expect(screen.queryByText(/disimpan sebagai kredit nasabah/)).not.toBeInTheDocument();
  });

  it("shows the server paid_at, the payment number and a way back to the contract", async () => {
    const { user, onRecordAnother } = renderResult();

    expect(screen.getByText("PAY-202611-0007")).toBeInTheDocument();
    expect(screen.getByText(formatDateTime(PAYMENT.paid_at))).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Kembali ke kontrak" })).toHaveAttribute("href", "/contracts/c1");

    await user.click(screen.getByRole("button", { name: "Catat pembayaran lain" }));
    expect(onRecordAnother).toHaveBeenCalledTimes(1);
  });

  it("drops the apply-credit advice and the record-another action once the contract is closed", () => {
    renderResult(PAYMENT, "CLOSED");

    expect(screen.getByText(/disimpan sebagai kredit nasabah/)).toBeInTheDocument();
    expect(screen.queryByText(/Terapkan dari tab Kredit/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Catat pembayaran lain" })).not.toBeInTheDocument();
    expect(screen.queryByText("Status kontrak belum dapat dipastikan")).not.toBeInTheDocument();
  });

  it("offers neither follow-up action while the contract status is unknown, and says why", () => {
    renderResult(PAYMENT, null);

    expect(screen.getByText("PAY-202611-0007")).toBeInTheDocument();
    expect(screen.getByText("Status kontrak belum dapat dipastikan")).toBeInTheDocument();
    expect(screen.getByText(/disimpan sebagai kredit nasabah/)).toBeInTheDocument();
    expect(screen.queryByText(/Terapkan dari tab Kredit/)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Catat pembayaran lain" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Kembali ke kontrak" })).toBeInTheDocument();
  });
});
