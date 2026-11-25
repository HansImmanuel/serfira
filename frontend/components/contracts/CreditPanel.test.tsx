import { QueryClientProvider } from "@tanstack/react-query";
import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, apiFetch: vi.fn() };
});

vi.mock("@/lib/idempotency", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/idempotency")>();
  return { ...actual, newIdempotencyKey: vi.fn() };
});

import { validateCreditAmount } from "@/components/contracts/ApplyCreditDialog";
import { CreditPanel } from "@/components/contracts/CreditPanel";
import { ApiError, apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
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

function mockApi(available: string | (() => string), apply: () => Promise<unknown> = async () => ({})) {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    if (String(path).endsWith("/credit/apply")) {
      return (await apply()) as never;
    }
    if (String(path).endsWith("/installments")) {
      return [INSTALLMENT] as never;
    }
    return credit(typeof available === "function" ? available() : available) as never;
  });
}

function applyCalls() {
  return vi.mocked(apiFetch).mock.calls.filter(([path]) => String(path).endsWith("/credit/apply"));
}

function sentKey(callIndex: number): string | undefined {
  return applyCalls()[callIndex]?.[1]?.headers?.["Idempotency-Key"];
}

function creditReads(): number {
  return vi.mocked(apiFetch).mock.calls.filter(([path]) => String(path).endsWith("/credit")).length;
}

function setStatus(view: ReturnType<typeof renderWithQueryClient>, status: ContractStatus) {
  view.rerender(
    <QueryClientProvider client={view.queryClient}>
      <CreditPanel contractId="c1" contractStatus={status} />
    </QueryClientProvider>,
  );
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
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
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

  it("applies the full balance with an empty body and an Idempotency-Key, then reloads the credit", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => ({ applied_amount: "250000.00", available_balance: "0.00", applications: [] }));
    const user = userEvent.setup();
    renderPanel();

    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    await vi.waitFor(() => expect(creditReads()).toBe(2));
    expect(apiFetch).toHaveBeenCalledWith("/contracts/c1/credit/apply", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: "{}",
    });
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

describe("CreditPanel apply retry safety", () => {
  beforeEach(() => {
    clearSession();
    vi.mocked(apiFetch).mockReset();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  async function openDialog(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole("button", { name: "Terapkan Kredit" }));
    return screen.findByRole("dialog");
  }

  function failOnceThenSucceed() {
    let attempt = 0;
    return async () => {
      attempt += 1;
      if (attempt === 1) {
        throw new TypeError("Failed to fetch");
      }
      return { applied_amount: "100000.00", available_balance: "150000.00", applications: [] };
    };
  }

  it("retries a lost response with the same key and amount, so the server can replay it", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", failOnceThenSucceed());
    const user = userEvent.setup();
    renderPanel();

    const dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "100000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    expect(await within(dialog).findByText("Failed to fetch")).toBeInTheDocument();
    expect(within(dialog).getByText(/tidak akan menerapkan kredit dua kali/)).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(applyCalls()).toHaveLength(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
    expect(applyCalls()[0][1]?.body).toBe(applyCalls()[1][1]?.body);
  });

  it("keeps the key and the failed outcome when the dialog is closed and reopened", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", failOnceThenSucceed());
    const user = userEvent.setup();
    renderPanel();

    let dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "100000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await within(dialog).findByText("Failed to fetch");
    await user.click(within(dialog).getByRole("button", { name: "Batal" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    dialog = await openDialog(user);
    expect(within(dialog).getByText("Failed to fetch")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Jumlah")).toHaveValue("100000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(applyCalls()).toHaveLength(2));

    expect(sentKey(1)).toBe("key-1");
  });

  it("uses a new key for a genuinely new apply after a success", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => ({ applied_amount: "250000.00", available_balance: "0.00", applications: [] }));
    const user = userEvent.setup();
    renderPanel();

    let dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(applyCalls()).toHaveLength(2));

    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-2");
  });

  it("blocks resubmitting under a spent key and mints a new one only through Mulai form baru", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    let attempt = 0;
    mockApi("250000.00", async () => {
      attempt += 1;
      if (attempt === 1) {
        throw new ApiError("CONFLICT", "Idempotency-Key was already used for a different request", 409);
      }
      return { applied_amount: "50000.00", available_balance: "200000.00", applications: [] };
    });
    const user = userEvent.setup();
    renderPanel();

    const dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "100000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(dialog).findByText(/penerapan sebelumnya mungkin sudah tercatat/)).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Terapkan" })).toBeDisabled();
    expect(applyCalls()).toHaveLength(1);

    await user.click(within(dialog).getByRole("button", { name: "Mulai form baru" }));
    const fresh = await screen.findByRole("dialog");
    expect(within(fresh).queryByText(/penerapan sebelumnya mungkin sudah tercatat/)).not.toBeInTheDocument();
    expect(within(fresh).getByLabelText("Jumlah")).toHaveValue("");
    await user.type(within(fresh).getByLabelText("Jumlah"), "50000");
    await user.click(within(fresh).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(applyCalls()).toHaveLength(2));

    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-2");
  });

  it("keeps the dialog and its error when the reloaded balance is zero after an apply that did commit", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    let committed = false;
    mockApi(
      () => (committed ? "0.00" : "250000.00"),
      async () => {
        committed = true;
        throw new TypeError("Failed to fetch");
      },
    );
    const user = userEvent.setup();
    renderPanel();

    const dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "250000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    await vi.waitFor(() => expect(creditReads()).toBe(2));
    expect(await within(screen.getByRole("dialog")).findByText("Failed to fetch")).toBeInTheDocument();
    // The retry of the same amount is still allowed against the balance the operation started with.
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(applyCalls()).toHaveLength(2));
    expect(sentKey(1)).toBe("key-1");
  });

  it("keeps the dialog mounted when reloading the credit fails after an apply", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    let creditCalls = 0;
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      if (String(path).endsWith("/credit/apply")) {
        throw new TypeError("Failed to fetch");
      }
      if (String(path).endsWith("/installments")) {
        return [INSTALLMENT] as never;
      }
      creditCalls += 1;
      if (creditCalls > 1) {
        throw new ApiError("INTERNAL_ERROR", "Server error", 500);
      }
      return credit("250000.00") as never;
    });
    const user = userEvent.setup();
    renderPanel();

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(screen.getByRole("dialog")).findByText("Failed to fetch")).toBeInTheDocument();
    expect(await screen.findByText("Saldo kredit mungkin tidak terbaru")).toBeInTheDocument();
    expect(within(screen.getByRole("dialog")).getByRole("button", { name: "Terapkan" })).toBeEnabled();
  });

  it("treats a balance rejection as an ordinary error: the key stays usable and the live balance is the ceiling", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    let rejected = false;
    mockApi(
      () => (rejected ? "100000.00" : "250000.00"),
      async () => {
        if (!rejected) {
          rejected = true;
          throw new ApiError("CREDIT_EXCEEDS_BALANCE", "amount exceeds the available credit balance", 409);
        }
        return { applied_amount: "100000.00", available_balance: "0.00", applications: [] };
      },
    );
    const user = userEvent.setup();
    renderPanel();

    const dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "200000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));

    expect(await within(dialog).findByText("amount exceeds the available credit balance")).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Mulai form baru" })).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Terapkan" })).toBeEnabled();

    // Another operator applied credit meanwhile; the reloaded balance is what a new amount is checked against.
    await vi.waitFor(() => expect(creditReads()).toBe(2));
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    expect(await within(dialog).findByText(/Melebihi saldo tersedia/)).toBeInTheDocument();
    expect(applyCalls()).toHaveLength(1);

    await user.clear(within(dialog).getByLabelText("Jumlah"));
    await user.type(within(dialog).getByLabelText("Jumlah"), "100000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(applyCalls()).toHaveLength(2));

    expect(sentKey(1)).toBe("key-1");
  });

  it("still lets the operator recover a lost apply after it closed the contract and emptied the balance", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    let committed = false;
    let attempt = 0;
    mockApi(
      () => (committed ? "0.00" : "250000.00"),
      async () => {
        attempt += 1;
        if (attempt === 1) {
          committed = true;
          throw new TypeError("Failed to fetch");
        }
        return { applied_amount: "250000.00", available_balance: "0.00", applications: [] };
      },
    );
    const user = userEvent.setup();
    const view = renderWithQueryClient(<CreditPanel contractId="c1" contractStatus="ACTIVE" />);

    let dialog = await openDialog(user);
    await user.type(within(dialog).getByLabelText("Jumlah"), "250000");
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await within(dialog).findByText("Failed to fetch");
    await user.click(within(dialog).getByRole("button", { name: "Batal" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    // The apply that matured the last installment closed the contract.
    setStatus(view, "CLOSED");

    dialog = await openDialog(user);
    expect(within(dialog).getByText("Failed to fetch")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(applyCalls()).toHaveLength(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
  });

  it("keeps the dialog mounted, error included, when the contract closes while it is open", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => {
      throw new TypeError("Failed to fetch");
    });
    const user = userEvent.setup();
    const view = renderWithQueryClient(<CreditPanel contractId="c1" contractStatus="ACTIVE" />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await within(dialog).findByText("Failed to fetch");

    setStatus(view, "CLOSED");

    expect(within(screen.getByRole("dialog")).getByText("Failed to fetch")).toBeInTheDocument();
  });

  it("does not keep offering the apply once a definite rejection leaves nothing to recover", async () => {
    signInAs(["ADMIN_OPERASIONAL"]);
    mockApi("250000.00", async () => {
      throw new ApiError("CREDIT_NOT_APPLICABLE", "Tidak ada angsuran jatuh tempo", 409);
    });
    const user = userEvent.setup();
    const view = renderWithQueryClient(<CreditPanel contractId="c1" contractStatus="ACTIVE" />);

    const dialog = await openDialog(user);
    await user.click(within(dialog).getByRole("button", { name: "Terapkan" }));
    await within(dialog).findByText("Tidak ada angsuran jatuh tempo");
    await user.click(within(dialog).getByRole("button", { name: "Batal" }));
    await vi.waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    setStatus(view, "CLOSED");

    expect(screen.queryByRole("button", { name: "Terapkan Kredit" })).not.toBeInTheDocument();
  });
});
