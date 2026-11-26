import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/reports/aging",
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

import { isNplRow } from "@/components/reports/AgingContractTable";
import { AgingReportView } from "@/components/reports/AgingReportView";
import { ApiError, apiFetch } from "@/lib/api";
import { formatDate } from "@/lib/format";
import { clearSession, setSession } from "@/lib/session";
import type { AgingBuckets, AgingReport, ContractAgingRow } from "@/types/report";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const ZERO_BUCKETS: AgingBuckets = {
  current: "0.00",
  dpd_1_30: "0.00",
  dpd_31_60: "0.00",
  dpd_61_90: "0.00",
  dpd_over_90: "0.00",
  total_outstanding: "0.00",
};

const ROW: ContractAgingRow = {
  contract_id: "11111111-1111-4111-8111-111111111111",
  contract_no: "MF-202611-0001",
  customer_name: "Budi Santoso",
  buckets: { ...ZERO_BUCKETS, current: "1573333.33", total_outstanding: "1573333.33" },
};

/** Portfolio values deliberately unequal to any page's row sums: the summary must come from here. */
const PORTFOLIO: AgingBuckets = {
  current: "11000000.00",
  dpd_1_30: "2200000.00",
  dpd_31_60: "3300000.00",
  dpd_61_90: "4400000.00",
  dpd_over_90: "5500000.00",
  total_outstanding: "26400000.00",
};

function reportOf(rows: ContractAgingRow[], page = 0, totalElements = rows.length): AgingReport {
  return {
    as_of: "2026-11-26",
    portfolio: PORTFOLIO,
    contracts: {
      content: rows,
      page,
      size: 20,
      total_elements: totalElements,
      total_pages: Math.ceil(totalElements / 20),
    },
  };
}

function signInAs(roles: string[]) {
  act(() => {
    setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
  });
}

describe("AgingReportView", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    signInAs(["MANAJEMEN"]);
  });

  afterEach(() => {
    act(() => clearSession());
  });

  it("shows skeleton rows while the report loads", () => {
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}) as never);
    renderWithQueryClient(<AgingReportView />);

    expect(screen.getAllByTestId("aging-row-skeleton").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("Per tanggal")).toHaveValue("–");
  });

  it("renders a row per contract with its customer and buckets, linking to the contract", async () => {
    vi.mocked(apiFetch).mockResolvedValue(reportOf([ROW]) as never);
    renderWithQueryClient(<AgingReportView />);

    const link = await screen.findByRole("link", { name: "MF-202611-0001" });
    expect(link).toHaveAttribute("href", `/contracts/${ROW.contract_id}`);
    const row = link.closest("tr") as HTMLElement;
    expect(within(row).getByText("Budi Santoso")).toBeInTheDocument();
    expect(within(row).getAllByText(/1\.573\.333/)).toHaveLength(2); // outstanding and current
    expect(within(row).queryByText("NPL")).not.toBeInTheDocument();
  });

  it("takes the summary row from the portfolio buckets, never from the page's rows", async () => {
    vi.mocked(apiFetch).mockResolvedValue(reportOf([ROW], 1, 21) as never);
    renderWithQueryClient(<AgingReportView />);

    const summary = await screen.findByRole("row", { name: "Total portofolio" });
    expect(within(summary).getByText(/26\.400\.000/)).toBeInTheDocument();
    expect(within(summary).getByText(/11\.000\.000/)).toBeInTheDocument();
    expect(within(summary).getByText(/2\.200\.000/)).toBeInTheDocument();
    expect(within(summary).getByText(/3\.300\.000/)).toBeInTheDocument();
    expect(within(summary).getByText(/4\.400\.000/)).toBeInTheDocument();
    expect(within(summary).getByText(/5\.500\.000/)).toBeInTheDocument();
  });

  it("pages server-side and never sends as_of", async () => {
    const user = userEvent.setup();
    vi.mocked(apiFetch).mockImplementation(((path: string) =>
      Promise.resolve(reportOf([ROW], path.includes("page=1") ? 1 : 0, 25))) as never);
    renderWithQueryClient(<AgingReportView />);

    await screen.findByRole("link", { name: "MF-202611-0001" });
    expect(screen.getByRole("button", { name: /Sebelumnya/ })).toBeDisabled();
    expect(screen.getByText("25 kontrak · halaman 1 dari 2")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Berikutnya/ }));

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith("/reports/aging?page=1&size=20"));
    expect(apiFetch).toHaveBeenCalledWith("/reports/aging?page=0&size=20");
    for (const [path] of vi.mocked(apiFetch).mock.calls) {
      expect(String(path)).not.toContain("as_of");
    }
  });

  it("keeps the portfolio summary and as-of while the next page loads, but never the previous rows", async () => {
    const user = userEvent.setup();
    vi.mocked(apiFetch).mockImplementation(((path: string) =>
      path.includes("page=1") ? new Promise(() => {}) : Promise.resolve(reportOf([ROW], 0, 25))) as never);
    renderWithQueryClient(<AgingReportView />);

    await screen.findByRole("link", { name: "MF-202611-0001" });
    await user.click(screen.getByRole("button", { name: /Berikutnya/ }));

    await waitFor(() => expect(screen.getAllByTestId("aging-row-skeleton").length).toBeGreaterThan(0));
    expect(screen.queryByRole("link", { name: "MF-202611-0001" })).not.toBeInTheDocument();
    const summary = screen.getByRole("row", { name: "Total portofolio" });
    expect(within(summary).getByText(/26\.400\.000/)).toBeInTheDocument();
    expect(screen.getByLabelText("Per tanggal")).toHaveValue(formatDate("2026-11-26"));
    expect(screen.getByText("25 kontrak · halaman 2 dari 2")).toBeInTheDocument();
  });

  it("shows the API's as_of in a read-only field", async () => {
    vi.mocked(apiFetch).mockResolvedValue(reportOf([ROW]) as never);
    renderWithQueryClient(<AgingReportView />);

    const asOf = screen.getByLabelText("Per tanggal");
    await waitFor(() => expect(asOf).toHaveValue(formatDate("2026-11-26")));
    expect(asOf).toHaveAttribute("readonly");
  });

  it("shows the empty message when no ACTIVE contract owes money", async () => {
    vi.mocked(apiFetch).mockResolvedValue({ ...reportOf([]), portfolio: ZERO_BUCKETS } as never);
    renderWithQueryClient(<AgingReportView />);

    expect(await screen.findByText("Tidak ada kontrak ACTIVE dengan outstanding.")).toBeInTheDocument();
  });

  it("shows the API error and retries on request", async () => {
    const user = userEvent.setup();
    vi.mocked(apiFetch).mockRejectedValueOnce(new ApiError("INTERNAL_ERROR", "Server sedang bermasalah", 500));
    vi.mocked(apiFetch).mockResolvedValueOnce(reportOf([ROW]) as never);
    renderWithQueryClient(<AgingReportView />);

    expect(await screen.findByText("Server sedang bermasalah")).toBeInTheDocument();
    expect(screen.getByText("INTERNAL_ERROR")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Coba lagi" }));
    expect(await screen.findByRole("link", { name: "MF-202611-0001" })).toBeInTheDocument();
  });

  it("keeps the pager when another page fails, without stale rows, so the user can go back", async () => {
    const user = userEvent.setup();
    vi.mocked(apiFetch).mockImplementation(((path: string) =>
      path.includes("page=1")
        ? Promise.reject(new ApiError("INTERNAL_ERROR", "Halaman gagal dimuat", 500))
        : Promise.resolve(reportOf([ROW], 0, 25))) as never);
    renderWithQueryClient(<AgingReportView />);

    await screen.findByRole("link", { name: "MF-202611-0001" });
    await user.click(screen.getByRole("button", { name: /Berikutnya/ }));

    expect(await screen.findByText("Halaman gagal dimuat")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "MF-202611-0001" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Berikutnya/ })).toBeDisabled();
    const previous = screen.getByRole("button", { name: /Sebelumnya/ });
    expect(previous).toBeEnabled();

    await user.click(previous);

    expect(await screen.findByRole("link", { name: "MF-202611-0001" })).toBeInTheDocument();
    expect(screen.queryByText("Halaman gagal dimuat")).not.toBeInTheDocument();
  });

  it("retries a failed page in place", async () => {
    const user = userEvent.setup();
    const NEXT_ROW: ContractAgingRow = { ...ROW, contract_id: "page-2-row", contract_no: "MF-202611-0021" };
    let pageOneCalls = 0;
    vi.mocked(apiFetch).mockImplementation(((path: string) => {
      if (!path.includes("page=1")) {
        return Promise.resolve(reportOf([ROW], 0, 25));
      }
      pageOneCalls += 1;
      return pageOneCalls === 1
        ? Promise.reject(new ApiError("INTERNAL_ERROR", "Halaman gagal dimuat", 500))
        : Promise.resolve(reportOf([NEXT_ROW], 1, 25));
    }) as never);
    renderWithQueryClient(<AgingReportView />);

    await screen.findByRole("link", { name: "MF-202611-0001" });
    await user.click(screen.getByRole("button", { name: /Berikutnya/ }));
    await screen.findByText("Halaman gagal dimuat");

    await user.click(screen.getByRole("button", { name: "Coba lagi" }));

    expect(await screen.findByRole("link", { name: "MF-202611-0021" })).toBeInTheDocument();
    expect(screen.getByText("25 kontrak · halaman 2 dari 2")).toBeInTheDocument();
  });

  it("renders access denied without a request for a role that cannot read reports", () => {
    signInAs([]);
    renderWithQueryClient(<AgingReportView />);

    expect(screen.getByText("Akses ditolak")).toBeInTheDocument();
    expect(apiFetch).not.toHaveBeenCalled();
  });
});

describe("isNplRow", () => {
  const withOver90 = (dpdOver90: string): ContractAgingRow => ({
    ...ROW,
    buckets: { ...ROW.buckets, dpd_over_90: dpdOver90 },
  });

  it("is the ADR-026 D4 test: any amount more than 90 days past due", () => {
    expect(isNplRow(withOver90("0.00"))).toBe(false);
    expect(isNplRow(withOver90("0"))).toBe(false);
    expect(isNplRow(withOver90("0.01"))).toBe(true);
    expect(isNplRow(withOver90("1500000.00"))).toBe(true);
  });
});
