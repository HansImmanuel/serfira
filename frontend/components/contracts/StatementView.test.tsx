import { screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/contracts/c1/statement",
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

import { StatementView } from "@/components/contracts/StatementView";
import { apiFetch } from "@/lib/api";
import { clearSession, setSession } from "@/lib/session";
import type { PageResponse } from "@/types/api";
import type { StatementRow } from "@/types/contract";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const ROW: StatementRow = {
  entry_date: "2026-11-01",
  account_code: "1101",
  account_name: "Piutang Pembiayaan",
  description: "Pencairan kontrak",
  ref_type: "CONTRACT_ACTIVATION",
  ref_id: "ref-1",
  debit: "16000000.00",
  credit: "0.00",
  is_reversal: false,
  reversal_of_id: null,
};

function pageOf(content: StatementRow[]): PageResponse<StatementRow> {
  return { content, page: 0, size: 20, total_elements: content.length, total_pages: 1 };
}

function signInAs(roles: string[]) {
  setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
}

describe("StatementView role gating", () => {
  beforeEach(() => {
    clearSession();
    vi.mocked(apiFetch).mockReset();
  });

  it("shows an access-denied state to MANAJEMEN without requesting the ledger", () => {
    signInAs(["MANAJEMEN"]);

    renderWithQueryClient(<StatementView contractId="contract-1" />);

    expect(screen.getByText("Akses ditolak")).toBeInTheDocument();
    expect(apiFetch).not.toHaveBeenCalled();
  });

  it("renders the ledger-literal rows for FINANCE", async () => {
    signInAs(["FINANCE"]);
    vi.mocked(apiFetch).mockResolvedValue(pageOf([ROW]) as never);

    renderWithQueryClient(<StatementView contractId="contract-1" />);

    expect(await screen.findByText("Piutang Pembiayaan")).toBeInTheDocument();
    expect(screen.getByText("Pencairan kontrak")).toBeInTheDocument();
    expect(apiFetch).toHaveBeenCalledWith("/contracts/contract-1/statement?page=0&size=20");
  });
});
