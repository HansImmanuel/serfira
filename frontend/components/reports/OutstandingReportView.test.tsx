import { act, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/reports/outstanding",
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

import { OutstandingReportView } from "@/components/reports/OutstandingReportView";
import { ApiError, apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { clearSession, setSession } from "@/lib/session";
import type { AgingBuckets, AgingReport, ContractAgingRow, OutstandingReport } from "@/types/report";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const ZERO_BUCKETS: AgingBuckets = {
  current: "0.00",
  dpd_1_30: "0.00",
  dpd_31_60: "0.00",
  dpd_61_90: "0.00",
  dpd_over_90: "0.00",
  total_outstanding: "0.00",
};

function row(no: string, customer: string, dpdOver90: string): ContractAgingRow {
  return {
    contract_id: `id-${no}`,
    contract_no: no,
    customer_name: customer,
    buckets: { ...ZERO_BUCKETS, current: "100000.00", dpd_over_90: dpdOver90, total_outstanding: "100000.00" },
  };
}

const ROWS = [
  row("MF-202611-0001", "Budi Santoso", "0.00"),
  row("MF-202611-0002", "Siti Rahayu", "300000.00"),
  row("MF-202611-0003", "Andi Wijaya", "0.01"),
];

const AGING: AgingReport = {
  as_of: "2026-11-26",
  portfolio: ZERO_BUCKETS,
  contracts: { content: ROWS, page: 0, size: 20, total_elements: 3, total_pages: 1 },
};

/** The `OutstandingReportIT` collection fixture's values, as the API returns them. */
const OUTSTANDING: OutstandingReport = {
  as_of: "2026-11-26",
  total_outstanding: "1150000.00",
  accounts_receivable: "1073333.33",
  npl: { contract_count: 2, outstanding: "300000.00", ratio: "0.2609" },
  collection: {
    period_from: "2026-11-01",
    period_to: "2026-11-26",
    collected: "2073333.33",
    due: "4719999.99",
    rate: "0.4393",
  },
  settlement_cash: { period_from: "2026-11-01", period_to: "2026-11-26", amount: "1000000.00" },
  active_contract_count: 3,
};

const EMPTY_BOOK: OutstandingReport = {
  ...OUTSTANDING,
  total_outstanding: "0.00",
  accounts_receivable: "0.00",
  npl: { contract_count: 0, outstanding: "0.00", ratio: null },
  collection: { ...OUTSTANDING.collection, collected: "0.00", due: "0.00", rate: null },
  settlement_cash: { ...OUTSTANDING.settlement_cash, amount: "0.00" },
  active_contract_count: 0,
};

function respondWith(outstanding: Promise<unknown>, aging: Promise<unknown> = Promise.resolve(AGING)) {
  vi.mocked(apiFetch).mockImplementation(((path: string) =>
    path.startsWith("/reports/outstanding") ? outstanding : aging) as never);
}

function card(name: string): HTMLElement {
  return screen.getByRole("group", { name });
}

describe("OutstandingReportView", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    act(() => {
      setSession({ access_token: accessTokenWithRoles(["MANAJEMEN"]), refresh_token: "refresh-1" }, "manager");
    });
  });

  afterEach(() => {
    act(() => clearSession());
  });

  it("shows card skeletons while the metrics load", () => {
    respondWith(new Promise(() => {}));
    renderWithQueryClient(<OutstandingReportView />);

    expect(screen.getAllByTestId("metric-skeleton").length).toBe(5);
  });

  it("shows every metric as the API returned it, formatted only", async () => {
    respondWith(Promise.resolve(OUTSTANDING));
    renderWithQueryClient(<OutstandingReportView />);

    await screen.findByRole("group", { name: "Total Outstanding" });
    expect(within(card("Total Outstanding")).getByText(/1\.150\.000/)).toBeInTheDocument();
    expect(within(card("Total Outstanding")).getByText("3 kontrak aktif")).toBeInTheDocument();
    expect(within(card("Total AR")).getByText(/1\.073\.333/)).toBeInTheDocument();
    expect(within(card("NPL")).getByText("26,09%")).toBeInTheDocument();
    expect(within(card("NPL")).getByText(/300\.000 · 2 kontrak/)).toBeInTheDocument();
    expect(within(card("Collection Rate MTD")).getByText("43,93%")).toBeInTheDocument();
    expect(within(card("Collection Rate MTD")).getByText(/2\.073\.333 \/ .*4\.720\.000/)).toBeInTheDocument();
    expect(within(card("Settlement Cash MTD")).getByText(/1\.000\.000/)).toBeInTheDocument();
  });

  it("labels the month-to-date period on both period cards", async () => {
    respondWith(Promise.resolve(OUTSTANDING));
    renderWithQueryClient(<OutstandingReportView />);

    await screen.findByRole("group", { name: "Collection Rate MTD" });
    const label = `${formatDate("2026-11-01")} – ${formatDate("2026-11-26")}`;
    expect(within(card("Collection Rate MTD")).getByText(label)).toBeInTheDocument();
    expect(within(card("Settlement Cash MTD")).getByText(label)).toBeInTheDocument();
  });

  it("renders a null ratio as the empty dash and zero money as Rp 0", async () => {
    respondWith(Promise.resolve(EMPTY_BOOK));
    renderWithQueryClient(<OutstandingReportView />);

    await screen.findByRole("group", { name: "NPL" });
    expect(within(card("NPL")).getByText("–")).toBeInTheDocument();
    expect(within(card("Collection Rate MTD")).getByText("–")).toBeInTheDocument();
    expect(within(card("Total Outstanding")).getByText(/Rp\s*0/)).toBeInTheDocument();
  });

  it("badges exactly the rows with an amount over 90 days past due, without a status column", async () => {
    respondWith(Promise.resolve(OUTSTANDING));
    renderWithQueryClient(<OutstandingReportView />);

    await screen.findByRole("link", { name: "MF-202611-0001" });
    const table = screen.getByRole("table");
    expect(within(table).getAllByText("NPL")).toHaveLength(2);
    for (const [no, npl] of [["MF-202611-0001", false], ["MF-202611-0002", true], ["MF-202611-0003", true]] as const) {
      const tr = within(table).getByRole("link", { name: no }).closest("tr") as HTMLElement;
      expect(within(tr).queryByText("NPL") !== null).toBe(npl);
    }
    expect(within(table).queryByRole("columnheader", { name: "Status" })).not.toBeInTheDocument();
  });

  it("keeps the contract table when the metrics fail, and the reverse", async () => {
    respondWith(Promise.reject(new ApiError("INTERNAL_ERROR", "Metrik gagal dimuat", 500)));
    renderWithQueryClient(<OutstandingReportView />);

    expect(await screen.findByText("Metrik gagal dimuat")).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "MF-202611-0001" })).toBeInTheDocument();
  });

  it("keeps the cards when the contract table fails", async () => {
    respondWith(Promise.resolve(OUTSTANDING), Promise.reject(new ApiError("INTERNAL_ERROR", "Tabel gagal dimuat", 500)));
    renderWithQueryClient(<OutstandingReportView />);

    expect(await screen.findByText("Tabel gagal dimuat")).toBeInTheDocument();
    expect(await screen.findByText("43,93%")).toBeInTheDocument();
  });

  it("renders access denied without a request for a role that cannot read reports", () => {
    act(() => {
      setSession({ access_token: accessTokenWithRoles([]), refresh_token: "refresh-1" }, "nobody");
    });
    renderWithQueryClient(<OutstandingReportView />);

    expect(screen.getByText("Akses ditolak")).toBeInTheDocument();
    expect(apiFetch).not.toHaveBeenCalled();
  });
});
