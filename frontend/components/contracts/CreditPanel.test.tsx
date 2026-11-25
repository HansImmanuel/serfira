import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, apiFetch: vi.fn() };
});

import { validateCreditAmount } from "@/components/contracts/ApplyCreditDialog";
import { CreditPanel } from "@/components/contracts/CreditPanel";
import { ApiError, apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { clearSession, setSession } from "@/lib/session";
import type { ContractStatus, CreditBalance, Installment } from "@/types/contract";

import { accessTokenWithRoles, renderWithQueryClient } from "../../test/utils";

const INSTALLMENT: Installment = {
  id: "i1",
  period_no: 3,
  due_date: "2027-01-01",
  principal_amount: "1000000.00",
  interest_amount: "200000.00",
  recognized_interest_amount: "200000.00",
  penalty_amount: "0.00",
  paid_amount: "100000.00",
  settled_amount: "0.00",
  written_off_amount: "0.00",
  status: "PARTIALLY_PAID",
  outstanding: "1100000.00",
};

function credit(available: string): CreditBalance {
  return {
    total_credit: "350000.00",
    available_balance: available,
    applications: [
      {
        application_id: "ap1",
        credit_id: "cr1",
        installment_id: "i1",
        amount: "100000.00",
        applied_at: "2026-11-20T09:00:00+07:00",
      },
    ],
  };
}

function signInAs(roles: string[]) {
  setSession({ access_token: accessTokenWithRoles(roles), refresh_token: "refresh-1" }, "operator");
}

function mockApi(available: string, apply: () => Promise<unknown> = async () => ({})) {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    if (String(path).endsWith("/credit/apply")) {
      return (await apply()) as never;
    }
    if (String(path).endsWith("/installments")) {
      return [INSTALLMENT] as never;
    }
    return credit(available) as never;
  });
}

function creditReads(): number {
  return vi.mocked(apiFetch).mock.calls.filter(([path]) => String(path).endsWith("/credit")).length;
}

function renderPanel(status: ContractStatus = "ACTIVE") {
  renderWithQueryClient(<CreditPanel contractId="c1" contractStatus={status} />);
}

describe("validateCreditAmount", () => {
  it.each(["", "   "])("treats %j as the full available balance", (input) => {
    expect(validateCreditAmount(input, "250000.00")).toEqual({ payload: {} });
  });

  it("returns a scale-2 amount within the balance", () => {
    expect(validateCreditAmount("100000", "250000.00")).toEqual({ payload: { amount: "100000.00" } });
  });

  it("accepts exactly the available balance", () => {
    expect(validateCreditAmount("250000", "250000.00")).toEqual({ payload: { amount: "250000.00" } });
  });

  it("rejects an amount above the available balance", () => {
    expect(validateCreditAmount("300000", "250000.00")).toEqual({
      error: `Melebihi saldo tersedia (${formatMoney("250000.00")}).`,
    });
  });

  it.each(["0", "-1", "1.234", "abc"])("rejects %j", (input) => {
    expect(validateCreditAmount(input, "250000.00")).toHaveProperty("error");
  });
});

describe("CreditPanel apply action", () => {
  beforeEach(() => {
    clearSession();
    vi.mocked(apiFetch).mockReset();
  });

  it("offers Terapkan Kredit to ADMIN_OPERASIONAL on an ACTIVE contract with a positive balance", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00");
    renderPanel();

    expect(await screen.findByRole("button", { name: "Terapkan Kredit" })).toBeInTheDocument();
  });

  it("hides it from FINANCE", async () => {
    signInAs(["FINANCE"]);
    mockApi("250000.00");
    renderPanel();

    expect(await screen.findByText("Saldo tersedia")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Terapkan Kredit" })).not.toBeInTheDocument();
  });

  it("hides it on a CLOSED contract", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00");
    renderPanel("CLOSED");

    expect(await screen.findByText("Saldo tersedia")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Terapkan Kredit" })).not.toBeInTheDocument();
  });

  it("hides it when no credit is available", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("0.00");
    renderPanel();

    expect(await screen.findByText("Saldo tersedia")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Terapkan Kredit" })).not.toBeInTheDocument();
  });

  it("applies the full balance with an empty body and no Idempotency-Key, then reloads the credit", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => ({ applied_amount: "250000.00", available_balance: "0.00", applications: [] }));
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    await vi.waitFor(() => expect(creditReads()).toBe(2));
    expect(apiFetch).toHaveBeenCalledWith("/contracts/c1/credit/apply", { method: "POST", body: "{}" });
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  it("blocks an amount above the balance without calling the API", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00");
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Jumlah"), "300000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(dialog).findByText(/Melebihi saldo tersedia/)).toBeInTheDocument();
    expect(vi.mocked(apiFetch).mock.calls.some(([path]) => String(path).endsWith("/credit/apply"))).toBe(false);
  });

  it("keeps the dialog open and shows the API error", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => {
      throw new ApiError("CREDIT_NOT_APPLICABLE", "Tidak ada angsuran jatuh tempo", 409);
    });
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(dialog).findByText("Tidak ada angsuran jatuh tempo")).toBeInTheDocument();
    expect(within(dialog).getByText("CREDIT_NOT_APPLICABLE")).toBeInTheDocument();
  });

  it("labels application rows by installment period", async () => {
    signInAs(["FINANCE"]);
    mockApi("250000.00");
    renderPanel();

    expect(await screen.findByText("Angsuran ke-3")).toBeInTheDocument();
  });

  it("reloads the credit balance after a failed apply, which may still have committed", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => {
      throw new TypeError("Failed to fetch");
    });
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(dialog).findByText("Failed to fetch")).toBeInTheDocument();
    await vi.waitFor(() => expect(creditReads()).toBe(2));
  });

  it("keeps the dialog open while the apply is pending", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", () => new Promise(() => {}));
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await within(dialog).findByRole("button", { name: "Menerapkan…" });

    await user.keyboard("{Escape}");

    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });
});
