import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
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
import { statementPath } from "@/hooks/useStatement";
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

describe("statementPath", () => {
  it("adds ref_type and the inclusive date range after the paging parameters", () => {
    expect(statementPath("c1", 0, { refType: "PAYMENT", from: "2026-11-01", to: "2026-11-30" })).toBe(
      "/contracts/c1/statement?page=0&size=20&ref_type=PAYMENT&from=2026-11-01&to=2026-11-30",
    );
  });

  it("omits filters that are not set", () => {
    expect(statementPath("c1", 2, { refType: null, from: null, to: null })).toBe(
      "/contracts/c1/statement?page=2&size=20",
    );
  });
});

describe("StatementView filters", () => {
  const PAYMENT_ROW: StatementRow = {
    ...ROW,
    ref_type: "PAYMENT",
    ref_id: "abcdef12-3456-4789-8abc-def012345678",
    description: "Pembayaran angsuran",
  };

  function lastPath(): string {
    const calls = vi.mocked(apiFetch).mock.calls;
    return String(calls[calls.length - 1]?.[0]);
  }

  beforeEach(() => {
    clearSession();
    signInAs(["FINANCE"]);
    vi.mocked(apiFetch).mockReset();
    vi.mocked(apiFetch).mockResolvedValue({ ...pageOf([PAYMENT_ROW]), total_elements: 60, total_pages: 3 } as never);
  });

  async function chooseRefType(name: string) {
    const user = userEvent.setup();
    await user.click(screen.getByRole("combobox", { name: "Ref type" }));
    await user.click(await screen.findByRole("option", { name }));
  }

  it("sends the selected ref type and date range to the API", async () => {
    renderWithQueryClient(<StatementView contractId="c1" />);
    await screen.findByText("Pembayaran angsuran");

    await chooseRefType("PAYMENT");
    fireEvent.change(screen.getByLabelText("Dari"), { target: { value: "2026-11-01" } });
    fireEvent.change(screen.getByLabelText("Sampai"), { target: { value: "2026-11-30" } });

    await vi.waitFor(() =>
      expect(lastPath()).toBe("/contracts/c1/statement?page=0&size=20&ref_type=PAYMENT&from=2026-11-01&to=2026-11-30"),
    );
  });

  it("returns to the first page when a filter changes", async () => {
    const user = userEvent.setup();
    renderWithQueryClient(<StatementView contractId="c1" />);
    await screen.findByText("Pembayaran angsuran");

    await user.click(screen.getByRole("button", { name: /Berikutnya/ }));
    await vi.waitFor(() => expect(lastPath()).toBe("/contracts/c1/statement?page=1&size=20"));

    await chooseRefType("BILLING");

    await vi.waitFor(() => expect(lastPath()).toBe("/contracts/c1/statement?page=0&size=20&ref_type=BILLING"));
  });

  it("blocks a from-after-to range without asking the API", async () => {
    renderWithQueryClient(<StatementView contractId="c1" />);
    await screen.findByText("Pembayaran angsuran");

    fireEvent.change(screen.getByLabelText("Dari"), { target: { value: "2026-12-01" } });
    fireEvent.change(screen.getByLabelText("Sampai"), { target: { value: "2026-11-01" } });

    expect(await screen.findByText("Tanggal awal tidak boleh setelah tanggal akhir.")).toBeInTheDocument();
    expect(
      vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).includes("to=2026-11-01")),
    ).toBe(false);
  });

  it("shows the ref type and a short ref id with the full id as its title", async () => {
    renderWithQueryClient(<StatementView contractId="c1" />);

    const shortId = await screen.findByText("abcdef12");
    expect(shortId).toHaveAttribute("title", PAYMENT_ROW.ref_id);
    expect(screen.getByText("PAYMENT", { selector: "span" })).toBeInTheDocument();
  });

  it("still applies a ref type change while the date range is inverted", async () => {
    renderWithQueryClient(<StatementView contractId="c1" />);
    await screen.findByText("Pembayaran angsuran");

    fireEvent.change(screen.getByLabelText("Dari"), { target: { value: "2026-12-01" } });
    fireEvent.change(screen.getByLabelText("Sampai"), { target: { value: "2026-11-01" } });
    await chooseRefType("BILLING");

    await vi.waitFor(() =>
      expect(lastPath()).toBe("/contracts/c1/statement?page=0&size=20&ref_type=BILLING&from=2026-12-01"),
    );
  });

  describe("while the next filter or page is loading", () => {
    const BILLING_ROW: StatementRow = {
      ...ROW,
      ref_type: "BILLING",
      ref_id: "99999999-0000-4000-8000-000000000000",
      description: "Penagihan bunga",
    };

    /** First request answers at once; every later one waits until `release` is called with its page. */
    function deferAfterFirstRequest() {
      let release: (page: PageResponse<StatementRow>) => void = () => {};
      let calls = 0;
      vi.mocked(apiFetch).mockImplementation((() => {
        calls += 1;
        if (calls === 1) {
          return Promise.resolve({ ...pageOf([PAYMENT_ROW]), total_elements: 60, total_pages: 3 });
        }
        return new Promise((resolve) => {
          release = resolve as typeof release;
        });
      }) as never);
      return (page: PageResponse<StatementRow>) => release(page);
    }

    it("hides the previous filter's rows behind an explicit loading state, then shows the matching ones", async () => {
      const release = deferAfterFirstRequest();
      renderWithQueryClient(<StatementView contractId="c1" />);
      await screen.findByText("Pembayaran angsuran");

      await chooseRefType("BILLING");

      expect(await screen.findByText("Memuat rekening koran…")).toBeInTheDocument();
      expect(screen.queryByText("Pembayaran angsuran")).not.toBeInTheDocument();
      expect(screen.queryByText("60 baris", { exact: false })).not.toBeInTheDocument();

      release({ ...pageOf([BILLING_ROW]), total_elements: 1 });

      expect(await screen.findByText("Penagihan bunga")).toBeInTheDocument();
      expect(screen.queryByText("Memuat rekening koran…")).not.toBeInTheDocument();
      expect(screen.getByText(/1 baris/)).toBeInTheDocument();
    });

    it("hides the previous page's rows and disables paging until the requested page arrives", async () => {
      const release = deferAfterFirstRequest();
      const user = userEvent.setup();
      renderWithQueryClient(<StatementView contractId="c1" />);
      await screen.findByText("Pembayaran angsuran");

      await user.click(screen.getByRole("button", { name: /Berikutnya/ }));

      expect(await screen.findByText("Memuat rekening koran…")).toBeInTheDocument();
      expect(screen.queryByText("Pembayaran angsuran")).not.toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Berikutnya/ })).toBeDisabled();
      expect(screen.getByRole("button", { name: /Sebelumnya/ })).toBeDisabled();

      release({ content: [BILLING_ROW], page: 1, size: 20, total_elements: 60, total_pages: 3 });

      expect(await screen.findByText("Penagihan bunga")).toBeInTheDocument();
      expect(screen.getByText(/halaman 2 dari 3/)).toBeInTheDocument();
      expect(screen.getByRole("button", { name: /Sebelumnya/ })).toBeEnabled();
      expect(screen.getByRole("button", { name: /Berikutnya/ })).toBeEnabled();
    });

    it("still resets to page zero and keeps the inverted-range guard while loading", async () => {
      const release = deferAfterFirstRequest();
      renderWithQueryClient(<StatementView contractId="c1" />);
      await screen.findByText("Pembayaran angsuran");

      fireEvent.change(screen.getByLabelText("Dari"), { target: { value: "2026-12-01" } });
      fireEvent.change(screen.getByLabelText("Sampai"), { target: { value: "2026-11-01" } });
      expect(await screen.findByText("Tanggal awal tidak boleh setelah tanggal akhir.")).toBeInTheDocument();
      // The valid "from" alone was sent (page 0); the inverted pair never was.
      expect(lastPath()).toBe("/contracts/c1/statement?page=0&size=20&from=2026-12-01");
      release({ ...pageOf([BILLING_ROW]), total_elements: 1 });
      expect(await screen.findByText("Penagihan bunga")).toBeInTheDocument();
    });
  });
});
