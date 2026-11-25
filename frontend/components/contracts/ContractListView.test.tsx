import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/contracts",
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

import { ContractListView } from "@/components/contracts/ContractListView";
import { ApiError, apiFetch } from "@/lib/api";
import { clearSession, setSession } from "@/lib/session";
import type { PageResponse } from "@/types/api";
import type { ContractListItem } from "@/types/contract";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const DRAFT: ContractListItem = {
  id: "22222222-2222-4222-8222-222222222222",
  contract_no: "MF-202611-0002",
  customer: {
    id: "33333333-3333-4333-8333-333333333333",
    full_name: "Siti Rahayu",
    nik: "3174********0002",
    phone: "0812****7890",
    address: "Bandung",
  },
  asset: {
    id: "44444444-4444-4444-8444-444444444444",
    asset_type: "MOTORCYCLE",
    brand: "Yamaha",
    model: "NMAX",
    serial_no: null,
    plate_no: null,
  },
  principal: "18000000.00",
  tenor_months: 12,
  status: "DRAFT",
  outstanding: null,
  planned_start_date: "2026-12-01",
  start_date: null,
  created_at: "2026-11-20T03:00:00+07:00",
};

const ACTIVE: ContractListItem = {
  ...DRAFT,
  id: "55555555-5555-4555-8555-555555555555",
  contract_no: "MF-202611-0001",
  status: "ACTIVE",
  outstanding: "1250000.00",
  start_date: "2026-11-01",
  created_at: "2026-11-01T09:00:00+07:00",
};

function pageOf(content: ContractListItem[]): PageResponse<ContractListItem> {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function signInAs(roles: string[]) {
  setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
}

describe("ContractListView", () => {
  beforeEach(() => {
    clearSession();
    vi.mocked(apiFetch).mockReset();
  });

  it("shows the table while the page is loading, not an empty state", () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockReturnValue(new Promise(() => {}));

    renderWithQueryClient(<ContractListView />);

    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.queryByText("Belum ada kontrak yang cocok dengan filter ini.")).not.toBeInTheDocument();
    expect(screen.queryByText("Buat Kontrak")).toBeInTheDocument();
  });

  it("renders the rows and the page summary once the page arrives", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockResolvedValue(pageOf([ACTIVE, DRAFT]));

    renderWithQueryClient(<ContractListView />);

    expect(await screen.findByText("MF-202611-0001")).toBeInTheDocument();
    expect(screen.getByText("MF-202611-0002")).toBeInTheDocument();
    expect(screen.getByText("2 kontrak · halaman 1 dari 1")).toBeInTheDocument();
  });

  it("shows the documented dash for a DRAFT's outstanding, never Rp 0", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockResolvedValue(pageOf([DRAFT]));

    renderWithQueryClient(<ContractListView />);

    const row = (await screen.findByText("MF-202611-0002")).closest("tr");
    expect(row).not.toBeNull();
    const cells = within(row as HTMLElement).getAllByRole("cell");
    expect(cells[6]).toHaveTextContent("–");
    expect(cells[6]).not.toHaveTextContent("Rp 0");
  });

  it("shows the empty state when no contract matches the filter", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockResolvedValue(pageOf([]));

    renderWithQueryClient(<ContractListView />);

    expect(
      await screen.findByText("Belum ada kontrak yang cocok dengan filter ini."),
    ).toBeInTheDocument();
  });

  it("shows the backend's envelope message when the list call fails", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockRejectedValue(new ApiError("FORBIDDEN", "Peran Anda tidak diizinkan.", 403));

    renderWithQueryClient(<ContractListView />);

    expect(await screen.findByText("Peran Anda tidak diizinkan.")).toBeInTheDocument();
    expect(screen.getByText("FORBIDDEN")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Coba lagi" })).toBeInTheDocument());
  });

  it("offers the create action only to a role that may write", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    vi.mocked(apiFetch).mockResolvedValue(pageOf([]));

    const { unmount } = renderWithQueryClient(<ContractListView />);
    expect(await screen.findByText("Buat Kontrak")).toBeInTheDocument();
    unmount();

    clearSession();
    signInAs(["FINANCE", "MANAJEMEN"]);
    renderWithQueryClient(<ContractListView />);
    expect(await screen.findByText("Belum ada kontrak yang cocok dengan filter ini.")).toBeInTheDocument();
    expect(screen.queryByText("Buat Kontrak")).not.toBeInTheDocument();
  });
});
