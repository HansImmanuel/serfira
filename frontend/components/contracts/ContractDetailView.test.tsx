import { screen } from "@testing-library/react";
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

import { ContractDetailView } from "@/components/contracts/ContractDetailView";
import { apiFetch } from "@/lib/api";
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
