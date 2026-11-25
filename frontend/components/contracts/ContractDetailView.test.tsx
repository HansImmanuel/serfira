import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/contracts/c1",
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

function mockApi() {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
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
