import { screen } from "@testing-library/react";
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

vi.mock("@/lib/idempotency", () => ({ newIdempotencyKey: vi.fn() }));

import { RecordPaymentView } from "@/components/payments/RecordPaymentView";
import { RequireWrite } from "@/components/shared/RequireWrite";
import { ApiError, apiFetch } from "@/lib/api";
import { newIdempotencyKey } from "@/lib/idempotency";
import { clearSession, setSession } from "@/lib/session";
import type { Contract, ContractStatus } from "@/types/contract";
import type { Payment } from "@/types/payment";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

function contract(status: ContractStatus): Contract {
  return {
    id: "c1",
    contract_no: "MF-202611-0001",
    customer: { id: "cu1", full_name: "Budi Santoso", nik: "3174********0001", phone: "0812****7890", address: null },
    asset: { id: "a1", asset_type: "MOTORCYCLE", brand: "Honda", model: "Vario 160", serial_no: null, plate_no: null },
    asset_price: "20000000.00",
    principal: "16000000.00",
    down_payment: "4000000.00",
    tenor_months: 12,
    status,
    outstanding: status === "DRAFT" ? null : "16000000.00",
    planned_start_date: "2026-11-01",
    start_date: status === "DRAFT" ? null : "2026-11-01",
    created_at: "2026-11-01T09:00:00+07:00",
    interest_scheme: "FLAT",
    interest_rate: "0.0150",
    grace_period_days: 3,
    penalty_rate_daily: "0.0010",
    closed_at: null,
    closed_reason: null,
    version: 0,
  };
}

const PAYMENT: Payment = {
  id: "p1",
  payment_no: "PAY-202611-0001",
  contract_id: "c1",
  amount: "1500000.00",
  channel: "CASH",
  paid_at: "2026-11-25T10:00:00+07:00",
  status: "POSTED",
  excess_amount: "0.00",
  allocations: [],
};

function mockApi(status: ContractStatus, payment: () => Promise<Payment> = async () => PAYMENT) {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    if (path === "/payments") {
      return (await payment()) as never;
    }
    if (String(path).endsWith("/installments")) {
      return [] as never;
    }
    return contract(status) as never;
  });
}

function paymentKeys(): (string | undefined)[] {
  return vi
    .mocked(apiFetch)
    .mock.calls.filter(([path]) => path === "/payments")
    .map(([, options]) => options?.headers?.["Idempotency-Key"]);
}

describe("RecordPaymentView", () => {
  beforeEach(() => {
    clearSession();
    setSession({ access_token: accessTokenWithRoles(["ADMIN_OPERASIONAL"]), refresh_token: "r1" }, "admin");
    vi.mocked(apiFetch).mockReset();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  it("renders the form for an ACTIVE contract", async () => {
    mockApi("ACTIVE");
    renderWithQueryClient(<RecordPaymentView contractId="c1" />);

    expect(await screen.findByRole("heading", { name: "Catat Pembayaran" })).toBeInTheDocument();
    expect(screen.getByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.getByLabelText("Jumlah Bayar")).toBeInTheDocument();
  });

  it.each<ContractStatus>(["DRAFT", "CLOSED", "TERMINATED"])("shows no form for a %s contract", async (status) => {
    mockApi(status);
    renderWithQueryClient(<RecordPaymentView contractId="c1" />);

    expect(
      await screen.findByText("Pembayaran hanya dapat dicatat untuk kontrak berstatus ACTIVE."),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("Jumlah Bayar")).not.toBeInTheDocument();
  });

  it("shows the result after posting, then a fresh form with a new key", async () => {
    mockApi("ACTIVE");
    const user = userEvent.setup();
    renderWithQueryClient(<RecordPaymentView contractId="c1" />);

    await user.type(await screen.findByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));
    expect(await screen.findByText("Pembayaran tercatat")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Catat pembayaran lain" }));
    const amount = await screen.findByLabelText("Jumlah Bayar");
    expect(amount).toHaveValue("");
    await user.type(amount, "2000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    await screen.findByText("Pembayaran tercatat");
    expect(paymentKeys()).toEqual(["key-1", "key-2"]);
  });

  it('remounts the form with a new key after "Mulai form baru"', async () => {
    let attempt = 0;
    mockApi("ACTIVE", async () => {
      attempt += 1;
      if (attempt === 1) {
        throw new ApiError("CONFLICT", "Idempotency key conflict", 409);
      }
      return PAYMENT;
    });
    const user = userEvent.setup();
    renderWithQueryClient(<RecordPaymentView contractId="c1" />);

    await user.type(await screen.findByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));
    await user.click(await screen.findByRole("button", { name: "Mulai form baru" }));

    await user.type(screen.getByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));

    await screen.findByText("Pembayaran tercatat");
    expect(paymentKeys()).toEqual(["key-1", "key-2"]);
  });
});

describe("RecordPaymentView after the payment changes the contract", () => {
  beforeEach(() => {
    clearSession();
    setSession({ access_token: accessTokenWithRoles(["ADMIN_OPERASIONAL"]), refresh_token: "r1" }, "admin");
    vi.mocked(apiFetch).mockReset();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  function mockContractAfterPayment(afterPayment: () => Contract) {
    let paid = false;
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      if (path === "/payments") {
        paid = true;
        return PAYMENT as never;
      }
      if (String(path).endsWith("/installments")) {
        return [] as never;
      }
      return (paid ? afterPayment() : contract("ACTIVE")) as never;
    });
  }

  async function pay() {
    const user = userEvent.setup();
    renderWithQueryClient(<RecordPaymentView contractId="c1" />);
    await user.type(await screen.findByLabelText("Jumlah Bayar"), "1500000");
    await user.click(screen.getByRole("button", { name: "Simpan Pembayaran" }));
  }

  it("still shows the result when the final payment closes the contract", async () => {
    mockContractAfterPayment(() => contract("CLOSED"));
    await pay();

    expect(await screen.findByText("Pembayaran tercatat")).toBeInTheDocument();
    expect(screen.getByText("PAY-202611-0001")).toBeInTheDocument();
    expect(screen.queryByText("Pembayaran hanya dapat dicatat untuk kontrak berstatus ACTIVE.")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Catat pembayaran lain" })).not.toBeInTheDocument();
  });

  it("still shows the result when the contract refetch fails after posting", async () => {
    mockContractAfterPayment(() => {
      throw new ApiError("INTERNAL_ERROR", "Server error", 500);
    });
    await pay();

    expect(await screen.findByText("Pembayaran tercatat")).toBeInTheDocument();
    expect(screen.queryByText("Kontrak tidak dapat dimuat")).not.toBeInTheDocument();
  });
});

describe("RequireWrite message", () => {
  beforeEach(() => {
    clearSession();
    setSession({ access_token: accessTokenWithRoles(["FINANCE"]), refresh_token: "r1" }, "finance");
  });

  it("shows the caller's message and back link to a read-only role", () => {
    renderWithQueryClient(
      <RequireWrite message="Peran Anda tidak dapat mencatat pembayaran." backHref="/contracts/c1" backLabel="Kembali ke kontrak">
        <p>form</p>
      </RequireWrite>,
    );

    expect(screen.getByText("Peran Anda tidak dapat mencatat pembayaran.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Kembali ke kontrak" })).toHaveAttribute("href", "/contracts/c1");
    expect(screen.queryByText("form")).not.toBeInTheDocument();
  });

  it("keeps the contract-create defaults when no message is given", () => {
    renderWithQueryClient(
      <RequireWrite>
        <p>form</p>
      </RequireWrite>,
    );

    expect(screen.getByText("Peran Anda tidak dapat membuat kontrak.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Kembali ke daftar kontrak" })).toHaveAttribute("href", "/contracts");
  });
});
