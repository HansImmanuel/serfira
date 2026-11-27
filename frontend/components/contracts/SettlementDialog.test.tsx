import { QueryClientProvider } from "@tanstack/react-query";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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

import { SettlementDialog } from "@/components/contracts/SettlementDialog";
import { ApiError, apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { SettlementExecution, SettlementQuote } from "@/types/settlement";

import { renderWithQueryClient } from "../../test/utils";

function quote(overrides: Partial<SettlementQuote> = {}): SettlementQuote {
  return {
    quote_id: "q1",
    quote_no: "Q-20261127-0001",
    contract_id: "c1",
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
    ...overrides,
  };
}

function execution(quoteId = "q1"): SettlementExecution {
  return {
    settlement_id: "s1",
    settlement_no: "SET-202611-0001",
    contract_id: "c1",
    quote_id: quoteId,
    cash_received: "10500000.00",
    credit_used: "125000.00",
    rebate_amount: "300000.00",
    admin_fee: "100000.00",
    executed_at: "2026-11-27T10:05:00+07:00",
    allocations: [],
    credit_applications: [],
  };
}

function mockApi(
  nextQuote: () => SettlementQuote,
  execute: () => Promise<SettlementExecution> = async () => execution(),
) {
  vi.mocked(apiFetch).mockImplementation(async (path) => {
    if (path === "/settlements/quote") {
      return nextQuote() as never;
    }
    if (path === "/settlements") {
      return (await execute()) as never;
    }
    throw new Error(`unexpected call to ${String(path)}`);
  });
}

function callsTo(path: string) {
  return vi.mocked(apiFetch).mock.calls.filter(([calledPath]) => calledPath === path);
}

function sentKey(callIndex: number): string | undefined {
  return callsTo("/settlements")[callIndex]?.[1]?.headers?.["Idempotency-Key"];
}

function sentBody(callIndex: number): unknown {
  return JSON.parse(String(callsTo("/settlements")[callIndex]?.[1]?.body));
}

/** The dialog content remounts when a new quote arrives, so it is looked up afresh each time. */
function dialog() {
  return screen.getByRole("dialog");
}

function row(label: string) {
  return within(dialog()).getByRole("row", { name: new RegExp(`^${label}`) });
}

/** `formatMoney` as `toHaveTextContent` sees it: the element text is whitespace-normalized (no NBSP). */
function shownMoney(value: string): string {
  return formatMoney(value).replace(/\s+/g, " ");
}

function renderDialog(canStartNew = true) {
  return renderWithQueryClient(<SettlementDialog contractId="c1" canStartNew={canStartNew} />);
}

async function openQuote(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Pelunasan Dipercepat" }));
  await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Lihat Quote Pelunasan" }));
  await within(await screen.findByRole("dialog")).findByRole("button", { name: "Lunasi Sekarang" });
}

async function confirmExecution(user: ReturnType<typeof userEvent.setup>) {
  await user.click(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" }));
  await user.click(within(dialog()).getByRole("button", { name: "Ya, lunasi" }));
}

describe("SettlementDialog", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    vi.mocked(toast.success).mockClear();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows the breakdown with the derived cash due after Lihat Quote Pelunasan", async () => {
    mockApi(() => quote({ available_credit: "125000.00" }));
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);

    expect(callsTo("/settlements/quote")[0]?.[1]).toEqual({
      method: "POST",
      body: JSON.stringify({ contract_id: "c1" }),
    });
    for (const label of [
      "Outstanding Pokok",
      "Bunga Tertunggak",
      "Bunga Berjalan",
      "Bunga Masa Depan",
      "Rebate",
      "Denda",
      "Admin Fee",
      "Total Pelunasan",
      "Kredit Nasabah",
      "Total Cash Due",
    ]) {
      expect(row(label)).toBeInTheDocument();
    }
    expect(row("Bunga Masa Depan")).toHaveTextContent(shownMoney("600000.00"));
    expect(row("Total Pelunasan")).toHaveTextContent(shownMoney("10625000.00"));
    expect(row("Total Cash Due")).toHaveTextContent(shownMoney("10500000.00"));
    expect(row("Total Cash Due")).not.toHaveTextContent(shownMoney("10625000.00"));
  });

  it("starts the countdown from the server lifetime whatever the browser clock says", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    mockApi(() =>
      quote({ quoted_at: "2020-01-01T10:00:00+07:00", valid_until: "2020-01-01T10:15:00+07:00" }),
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderDialog();

    await openQuote(user);

    expect(within(dialog()).getByRole("timer")).toHaveTextContent("15:00");
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeEnabled();
    expect(within(dialog()).queryByRole("button", { name: "Ambil quote baru" })).not.toBeInTheDocument();

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(within(dialog()).getByRole("timer")).toHaveTextContent("14:59");

    act(() => {
      vi.advanceTimersByTime(900_000);
    });
    expect(within(dialog()).getByRole("timer")).toHaveTextContent("00:00");
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeDisabled();
    expect(within(dialog()).getByRole("button", { name: "Ambil quote baru" })).toBeVisible();
  });

  it("executes with the quote id and one key after confirmation", async () => {
    mockApi(() => quote({ available_credit: "125000.00" }));
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await user.click(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" }));
    expect(dialog()).toHaveTextContent(
      `Terima kas ${shownMoney("10500000.00")} dari nasabah dan tutup kontrak? Tindakan ini tidak dapat dibatalkan.`,
    );
    expect(callsTo("/settlements")).toHaveLength(0);
    await user.click(within(dialog()).getByRole("button", { name: "Ya, lunasi" }));

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(callsTo("/settlements")).toHaveLength(1);
    expect(apiFetch).toHaveBeenCalledWith("/settlements", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: JSON.stringify({ quote_id: "q1" }),
    });
    expect(toast.success).toHaveBeenCalledWith(
      `Pelunasan SET-202611-0001 berhasil. Kas diterima ${formatMoney("10500000.00")}; kontrak ditutup.`,
    );
  });

  it("returns from the confirmation to the breakdown with Kembali", async () => {
    mockApi(() => quote());
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await user.click(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" }));
    await user.click(within(dialog()).getByRole("button", { name: "Kembali" }));

    expect(row("Total Cash Due")).toBeInTheDocument();
    expect(callsTo("/settlements")).toHaveLength(0);
  });

  it.each([
    ["STALE_SETTLEMENT_QUOTE", "Kontrak berubah sejak quote dibuat."],
    ["SETTLEMENT_QUOTE_EXPIRED", "Masa berlaku quote sudah lewat."],
  ])("re-quotes with a new key after %s", async (code, description) => {
    let quotes = 0;
    let executes = 0;
    mockApi(
      () => quote({ quote_id: `q${++quotes}` }),
      async () => {
        executes += 1;
        if (executes === 1) {
          throw new ApiError(code, "quote rejected", 409);
        }
        return execution("q2");
      },
    );
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await confirmExecution(user);

    expect(await within(dialog()).findByText("Quote sudah kadaluarsa, ambil quote baru")).toBeInTheDocument();
    expect(within(dialog()).getByText(description)).toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeDisabled();

    await user.click(within(dialog()).getByRole("button", { name: "Ambil quote baru" }));
    await waitFor(() => expect(callsTo("/settlements/quote")).toHaveLength(2));
    await waitFor(() =>
      expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeEnabled(),
    );
    expect(within(dialog()).queryByText("Quote sudah kadaluarsa, ambil quote baru")).not.toBeInTheDocument();
    await confirmExecution(user);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(sentBody(0)).toEqual({ quote_id: "q1" });
    expect(sentBody(1)).toEqual({ quote_id: "q2" });
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-2");
  });

  it("retries a lost response with the same key and quote after the contract closes", async () => {
    let executes = 0;
    mockApi(
      () => quote(),
      async () => {
        executes += 1;
        if (executes === 1) {
          throw new TypeError("Failed to fetch");
        }
        return execution();
      },
    );
    const user = userEvent.setup();
    const view = renderDialog();

    await openQuote(user);
    await confirmExecution(user);
    expect(await within(dialog()).findByText("Failed to fetch")).toBeInTheDocument();

    // The settlement committed; the refetched contract is CLOSED, so a new settlement is no longer offered.
    view.rerender(
      <QueryClientProvider client={view.queryClient}>
        <SettlementDialog contractId="c1" canStartNew={false} />
      </QueryClientProvider>,
    );

    expect(within(dialog()).getByRole("button", { name: "Coba lagi" })).toBeEnabled();
    expect(within(dialog()).queryByRole("button", { name: "Ambil quote baru" })).not.toBeInTheDocument();
    expect(within(dialog()).queryByRole("button", { name: "Lunasi Sekarang" })).not.toBeInTheDocument();

    // Closing and reopening keeps the unresolved operation reachable.
    await user.click(within(dialog()).getByRole("button", { name: "Batal" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Pelunasan Dipercepat" }));
    expect(within(await screen.findByRole("dialog")).getByText("Failed to fetch")).toBeInTheDocument();

    await user.click(within(dialog()).getByRole("button", { name: "Coba lagi" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(callsTo("/settlements")).toHaveLength(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
    expect(sentBody(1)).toEqual({ quote_id: "q1" });
    expect(toast.success).toHaveBeenCalledTimes(1);
  });

  it("offers no new quote while a retry of a lost response is in flight, even after expiry", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    let executes = 0;
    mockApi(
      () => quote(),
      () => {
        executes += 1;
        return executes === 1 ? Promise.reject(new TypeError("Failed to fetch")) : new Promise(() => {});
      },
    );
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderDialog();

    await openQuote(user);
    await confirmExecution(user);
    expect(await within(dialog()).findByText("Failed to fetch")).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(900_000);
    });
    expect(within(dialog()).getByRole("timer")).toHaveTextContent("00:00");

    await user.click(within(dialog()).getByRole("button", { name: "Coba lagi" }));
    await within(dialog()).findByRole("button", { name: "Memproses…" });

    expect(within(dialog()).queryByRole("button", { name: "Ambil quote baru" })).not.toBeInTheDocument();
    expect(callsTo("/settlements/quote")).toHaveLength(1);
  });

  it("blocks execution when available credit exceeds the gross", async () => {
    mockApi(() => quote({ available_credit: "10625000.01" }));
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);

    expect(within(dialog()).getByText("Kredit nasabah melebihi total pelunasan.")).toBeInTheDocument();
    expect(
      within(dialog()).getByText("Pelunasan tidak dapat dieksekusi; refund kredit di luar MVP."),
    ).toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeDisabled();
    expect(callsTo("/settlements")).toHaveLength(0);
  });

  it("shows the server message and keeps Lunasi Sekarang enabled after another 4xx", async () => {
    mockApi(
      () => quote(),
      async () => {
        throw new ApiError("CONCURRENT_MODIFICATION", "Kontrak sedang diubah", 409);
      },
    );
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await confirmExecution(user);

    expect(await within(dialog()).findByText("Kontrak sedang diubah")).toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeEnabled();
    expect(within(dialog()).queryByRole("button", { name: "Coba lagi" })).not.toBeInTheDocument();
  });

  it("a spent key offers only Mulai form baru", async () => {
    mockApi(
      () => quote(),
      async () => {
        throw new ApiError("CONFLICT", "Idempotency-Key was already used for a different request", 409);
      },
    );
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await confirmExecution(user);

    expect(await within(dialog()).findByText(/pelunasan sebelumnya mungkin sudah tercatat/)).toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Lunasi Sekarang" })).toBeDisabled();
    expect(within(dialog()).queryByRole("button", { name: "Coba lagi" })).not.toBeInTheDocument();
    expect(within(dialog()).queryByRole("button", { name: "Ambil quote baru" })).not.toBeInTheDocument();

    await user.click(within(dialog()).getByRole("button", { name: "Mulai form baru" }));

    const fresh = await screen.findByRole("dialog");
    expect(within(fresh).getByRole("button", { name: "Lihat Quote Pelunasan" })).toBeEnabled();
    expect(within(fresh).queryByRole("row", { name: /^Total Cash Due/ })).not.toBeInTheDocument();
    expect(within(fresh).queryByText(/pelunasan sebelumnya mungkin sudah tercatat/)).not.toBeInTheDocument();
    expect(callsTo("/settlements")).toHaveLength(1);
  });

  it("keeps the dialog open while the settlement is pending", async () => {
    mockApi(
      () => quote(),
      () => new Promise(() => {}),
    );
    const user = userEvent.setup();
    renderDialog();

    await openQuote(user);
    await confirmExecution(user);
    await within(dialog()).findByRole("button", { name: "Memproses…" });

    await user.keyboard("{Escape}");

    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("hides the trigger when no operation is open and the contract is not ACTIVE", () => {
    renderDialog(false);

    expect(screen.queryByRole("button", { name: "Pelunasan Dipercepat" })).not.toBeInTheDocument();
  });
});
