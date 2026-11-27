import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

let searchParams = new URLSearchParams();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/contracts/c1",
  useSearchParams: () => searchParams,
}));

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

vi.mock("@/lib/idempotency", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/idempotency")>();
  return { ...actual, newIdempotencyKey: vi.fn() };
});

vi.mock("sonner", () => ({ toast: { success: vi.fn() } }));

import { toast } from "sonner";

import { ContractDetailView } from "@/components/contracts/ContractDetailView";
import { ApiError, apiFetch } from "@/lib/api";
import { newIdempotencyKey } from "@/lib/idempotency";
import { clearSession, setSession } from "@/lib/session";
import type { Contract, CreditBalance } from "@/types/contract";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const CONTRACT: Contract = {
  id: "11111111-1111-4111-8111-111111111111",
  contract_no: "MF-202611-0001",
  customer: {
    id: "22222222-2222-4222-8222-222222222222",
    full_name: "Budi Santoso",
    nik: "3174********0001",
    phone: "0812****7890",
    address: "Jakarta",
  },
  asset: {
    id: "33333333-3333-4333-8333-333333333333",
    asset_type: "MOTORCYCLE",
    brand: "Honda",
    model: "Vario 160",
    serial_no: null,
    plate_no: null,
  },
  asset_price: "20000000.00",
  principal: "16000000.00",
  down_payment: "4000000.00",
  tenor_months: 12,
  status: "ACTIVE",
  outstanding: "16000000.00",
  planned_start_date: "2026-11-01",
  start_date: "2026-11-01",
  created_at: "2026-11-01T09:00:00+07:00",
  interest_scheme: "FLAT",
  interest_rate: "0.0150",
  grace_period_days: 3,
  penalty_rate_daily: "0.0010",
  closed_at: null,
  closed_reason: null,
  version: 0,
};

const CREDIT: CreditBalance = { total_credit: "0.00", available_balance: "0.00", applications: [] };

function signInAs(roles: string[]) {
  setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
}

const PAYMENT = {
  id: "pay-1",
  payment_no: "PAY-0001",
  amount: "1000000.00",
  channel: "CASH",
  paid_at: "2026-11-20T10:00:00+07:00",
  status: "POSTED",
  excess_amount: "0.00",
  voided_at: null,
  void_reason: null,
  allocations: [],
};

function mockApi() {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    if (String(path).endsWith("/payments")) {
      return [PAYMENT] as never;
    }
    if (String(path).endsWith("/credit")) {
      return CREDIT as never;
    }
    if (String(path).endsWith("/installments")) {
      return [] as never;
    }
    return CONTRACT as never;
  });
}

describe("ContractDetailView role gating", () => {
  beforeEach(() => {
    clearSession();
    searchParams = new URLSearchParams();
    vi.mocked(apiFetch).mockReset();
    mockApi();
  });

  it("hides the credit and ledger tabs, the statement link and their queries from MANAJEMEN", async () => {
    signInAs(["MANAJEMEN"]);

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Jadwal" })).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Kredit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Ledger" })).not.toBeInTheDocument();
    expect(screen.queryByText("Rekening koran")).not.toBeInTheDocument();
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).endsWith("/credit"))).toBe(false);
  });

  it("offers both tabs to FINANCE and loads credit only when the tab is opened", async () => {
    signInAs(["FINANCE"]);

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByRole("tab", { name: "Kredit" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Ledger" })).toBeInTheDocument();
    expect(screen.getByText("Rekening koran")).toBeInTheDocument();
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).endsWith("/credit"))).toBe(false);

    await userEvent.click(screen.getByRole("tab", { name: "Kredit" }));

    expect(await screen.findByText("Saldo tersedia")).toBeInTheDocument();
    expect(apiFetch).toHaveBeenCalledWith(`/contracts/${CONTRACT.id}/credit`);
  });
});

describe("ContractDetailView Pembayaran tab", () => {
  beforeEach(() => {
    clearSession();
    searchParams = new URLSearchParams();
    vi.mocked(apiFetch).mockReset();
    mockApi();
  });

  it("shows the Pembayaran tab for FINANCE and hides it for MANAJEMEN", async () => {
    signInAs(["FINANCE"]);
    const finance = renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);
    expect(await screen.findByRole("tab", { name: "Pembayaran" })).toBeInTheDocument();
    finance.unmount();

    clearSession();
    signInAs(["MANAJEMEN"]);
    vi.mocked(apiFetch).mockClear();
    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);
    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Pembayaran" })).not.toBeInTheDocument();
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).endsWith("/payments"))).toBe(false);
  });

  it("opens the tab named in ?tab= and highlights the linked payment", async () => {
    signInAs(["FINANCE"]);
    searchParams = new URLSearchParams("tab=payments&payment=pay-1");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("PAY-0001")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Pembayaran" })).toHaveAttribute("aria-selected", "true");
    expect(document.getElementById("payment-pay-1")).toHaveAttribute("data-highlighted", "true");
  });

  it("falls back to Jadwal when the role cannot see the requested tab", async () => {
    signInAs(["MANAJEMEN"]);
    searchParams = new URLSearchParams("tab=payments");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByRole("tab", { name: "Jadwal" })).toHaveAttribute("aria-selected", "true");
  });
});

describe("ContractDetailView payment entry point", () => {
  beforeEach(() => {
    clearSession();
    searchParams = new URLSearchParams();
    vi.mocked(apiFetch).mockReset();
  });

  function mockContract(status: Contract["status"]) {
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      if (String(path).endsWith("/installments")) {
        return [] as never;
      }
      return { ...CONTRACT, status } as never;
    });
  }

  it('shows "Catat Pembayaran" to ADMIN_OPERASIONAL on an ACTIVE contract', async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockContract("ACTIVE");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByRole("link", { name: /Catat Pembayaran/ })).toHaveAttribute(
      "href",
      `/contracts/${CONTRACT.id}/payments/new`,
    );
  });

  it("hides it from FINANCE", async () => {
    signInAs(["FINANCE"]);
    mockContract("ACTIVE");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Catat Pembayaran/ })).not.toBeInTheDocument();
  });

  it("hides it on a DRAFT contract", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockContract("DRAFT");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /Catat Pembayaran/ })).not.toBeInTheDocument();
  });
});

describe("ContractDetailView settlement and write-off entry points", () => {
  beforeEach(() => {
    clearSession();
    searchParams = new URLSearchParams();
    vi.mocked(apiFetch).mockReset();
  });

  function mockContract(status: Contract["status"]) {
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      if (String(path).endsWith("/installments")) {
        return [] as never;
      }
      return { ...CONTRACT, status } as never;
    });
  }

  it("shows Pelunasan Dipercepat and Write-off only for ADMIN_OPERASIONAL on an ACTIVE contract", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockContract("ACTIVE");

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByRole("button", { name: "Pelunasan Dipercepat" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Write-off" })).toBeInTheDocument();
  });

  it.each([
    ["FINANCE", "ACTIVE"],
    ["ADMIN_OPERASIONAL", "CLOSED"],
  ] as const)("does not offer it to %s on a %s contract", async (role, status) => {
    signInAs([role]);
    mockContract(status);

    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Pelunasan Dipercepat" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Write-off" })).not.toBeInTheDocument();
  });
});

/**
 * T39 decision 8 end to end: a write whose response is lost is often lost because the backend is down, so
 * the `onSettled` refetch of the contract fails too. With cached data the page must keep rendering — and
 * with it the dialog, its `Idempotency-Key` and its "Coba lagi" — instead of swapping in a page-level error.
 */
describe("ContractDetailView keeps a lost write retryable when the refetch fails too", () => {
  const SETTLEMENT_QUOTE = {
    quote_id: "q1",
    quote_no: "Q-20261127-0001",
    contract_id: CONTRACT.id,
    status: "QUOTED",
    quoted_at: "2026-11-27T10:00:00+07:00",
    valid_until: "2026-11-27T10:15:00+07:00",
    contract_version: 3,
    outstanding_principal: "10000000.00",
    unpaid_billed_interest: "150000.00",
    accrued_interest: "50000.00",
    penalty_outstanding: "25000.00",
    rebate_amount: "300000.00",
    admin_fee: "100000.00",
    available_credit: "0.00",
    credit_used: "0.00",
    gross_amount: "10625000.00",
    cash_due: "10625000.00",
  };

  const SETTLEMENT = {
    settlement_id: "s1",
    settlement_no: "SET-202611-0001",
    contract_id: CONTRACT.id,
    quote_id: "q1",
    cash_received: "10625000.00",
    credit_used: "0.00",
    rebate_amount: "300000.00",
    admin_fee: "100000.00",
    executed_at: "2026-11-27T10:05:00+07:00",
    allocations: [],
    credit_applications: [],
  };

  const WRITE_OFF = {
    contract_id: CONTRACT.id,
    contract_no: CONTRACT.contract_no,
    status: "TERMINATED",
    write_off_reason: "rusak total",
    written_off_at: "2026-11-27T10:00:00+07:00",
    total_written_off: "7500000.00",
    installments: [],
  };

  const WRITE_OFF_PATH = `/contracts/${CONTRACT.id}/write-off`;

  /**
   * The backend goes down with the first write: that write and every call after it reject with `outage`
   * until `restore()`; then the write succeeds and the contract reads back as `closedStatus`.
   */
  function mockOutage(outage: () => Error, writeResult: unknown, closedStatus: Contract["status"]) {
    let down = false;
    let written = false;
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      const p = String(path);
      if (down) {
        throw outage();
      }
      if (p === "/settlements/quote") {
        return SETTLEMENT_QUOTE as never;
      }
      if (p === "/settlements" || p === WRITE_OFF_PATH) {
        if (!written) {
          written = true;
          down = true;
          throw outage();
        }
        return writeResult as never;
      }
      if (p.endsWith("/installments")) {
        return [] as never;
      }
      return { ...CONTRACT, status: written ? closedStatus : "ACTIVE" } as never;
    });
    return {
      restore: () => {
        down = false;
      },
    };
  }

  function writeCalls(path: string) {
    return vi.mocked(apiFetch).mock.calls.filter(([calledPath]) => calledPath === path);
  }

  function sentKey(path: string, index: number): string | undefined {
    return writeCalls(path)[index]?.[1]?.headers?.["Idempotency-Key"];
  }

  /** The dialog's own "Coba lagi" (the page and the schedule tab may carry their own). */
  async function dialogRetry() {
    return waitFor(() => {
      const button = within(screen.getByRole("dialog")).getByRole("button", { name: "Coba lagi" });
      expect(button).toBeEnabled();
      return button;
    });
  }

  const OUTAGES: [string, () => Error][] = [
    ["a network error", () => new TypeError("Failed to fetch")],
    ["a 502", () => new ApiError("BAD_GATEWAY", "Bad gateway", 502)],
  ];

  beforeEach(() => {
    clearSession();
    searchParams = new URLSearchParams();
    vi.mocked(apiFetch).mockReset();
    vi.mocked(toast.success).mockClear();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
    signInAs(["ADMIN_OPERASIONAL"]);
  });

  it.each(OUTAGES)("settlement: %s on execute and on the refetch still offers Coba lagi", async (_, outage) => {
    const backend = mockOutage(outage, SETTLEMENT, "CLOSED");
    const user = userEvent.setup();
    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    await user.click(await screen.findByRole("button", { name: "Pelunasan Dipercepat" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Lihat Quote Pelunasan" }));
    await user.click(await within(screen.getByRole("dialog")).findByRole("button", { name: "Lunasi Sekarang" }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Ya, lunasi" }));

    const retry = await dialogRetry();
    // The refetch failed after the execute did; the cached contract stays on screen with a stale notice.
    expect(screen.getByText("Kontrak mungkin tidak terbaru")).toBeInTheDocument();
    expect(screen.queryByText("Kontrak tidak dapat dimuat")).not.toBeInTheDocument();

    backend.restore();
    await user.click(retry);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    // The write-off operation mounts with the page and takes key-1; the settlement's quote step takes key-2.
    expect(writeCalls("/settlements")).toHaveLength(2);
    expect(sentKey("/settlements", 0)).toBe("key-2");
    expect(sentKey("/settlements", 1)).toBe("key-2");
    expect(writeCalls("/settlements")[1]?.[1]?.body).toBe(JSON.stringify({ quote_id: "q1" }));
    expect(toast.success).toHaveBeenCalledTimes(1);
  });

  it.each(OUTAGES)("write-off: %s on submit and on the refetch still offers Coba lagi", async (_, outage) => {
    const backend = mockOutage(outage, WRITE_OFF, "TERMINATED");
    const user = userEvent.setup();
    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    await user.click(await screen.findByRole("button", { name: "Write-off" }));
    await user.type(within(await screen.findByRole("dialog")).getByLabelText("Alasan write-off"), "rusak total");
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Write-off sekarang" }));

    const retry = await dialogRetry();
    expect(screen.getByText("Kontrak mungkin tidak terbaru")).toBeInTheDocument();
    expect(screen.queryByText("Kontrak tidak dapat dimuat")).not.toBeInTheDocument();

    backend.restore();
    await user.click(retry);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(writeCalls(WRITE_OFF_PATH)).toHaveLength(2);
    expect(sentKey(WRITE_OFF_PATH, 0)).toBe("key-1");
    expect(sentKey(WRITE_OFF_PATH, 1)).toBe("key-1");
    expect(writeCalls(WRITE_OFF_PATH)[1]?.[1]?.body).toBe(JSON.stringify({ reason: "rusak total" }));
    expect(toast.success).toHaveBeenCalledTimes(1);
  });

  it("still shows the page-level error when the first load fails", async () => {
    vi.mocked(apiFetch).mockRejectedValue(new TypeError("Failed to fetch"));
    renderWithQueryClient(<ContractDetailView contractId={CONTRACT.id} />);

    expect(await screen.findByText("Kontrak tidak dapat dimuat")).toBeInTheDocument();
  });
});
