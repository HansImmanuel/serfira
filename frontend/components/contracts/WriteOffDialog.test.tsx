import { QueryClientProvider } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
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

vi.mock("sonner", () => ({ toast: { success: vi.fn() } }));

import { toast } from "sonner";

import { validateWriteOffReason, WriteOffDialog } from "@/components/contracts/WriteOffDialog";
import { ApiError, apiFetch } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { newIdempotencyKey } from "@/lib/idempotency";
import type { WriteOffResult } from "@/types/contract";

import { renderWithQueryClient } from "../../test/utils";

const PATH = "/contracts/c1/write-off";

function result(): WriteOffResult {
  return {
    contract_id: "c1",
    contract_no: "MF-202611-0001",
    status: "TERMINATED",
    write_off_reason: "rusak total",
    written_off_at: "2026-11-27T10:00:00+07:00",
    total_written_off: "7500000.00",
    installments: [{ installment_id: "i1", period_no: 3, written_off_amount: "7500000.00" }],
  };
}

function calls() {
  return vi.mocked(apiFetch).mock.calls.filter(([path]) => path === PATH);
}

function sentKey(index: number): string | undefined {
  return calls()[index]?.[1]?.headers?.["Idempotency-Key"];
}

function sentBody(index: number): unknown {
  return JSON.parse(String(calls()[index]?.[1]?.body));
}

function dialog() {
  return screen.getByRole("dialog");
}

function renderDialog(canStartNew = true) {
  return renderWithQueryClient(
    <WriteOffDialog contractId="c1" contractNo="MF-202611-0001" canStartNew={canStartNew} />,
  );
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Write-off" }));
  await screen.findByRole("dialog");
}

async function typeReason(user: ReturnType<typeof userEvent.setup>, text: string) {
  await user.type(within(dialog()).getByLabelText("Alasan write-off"), text);
}

async function submit(user: ReturnType<typeof userEvent.setup>) {
  await user.click(within(dialog()).getByRole("button", { name: "Write-off sekarang" }));
}

describe("validateWriteOffReason", () => {
  it("rejects whitespace only", () => {
    expect(validateWriteOffReason("   ")).toEqual({ error: "Alasan wajib diisi." });
  });

  it("trims the reason", () => {
    expect(validateWriteOffReason("  rusak total  ")).toEqual({ reason: "rusak total" });
  });

  it("accepts exactly 500 characters and rejects 501", () => {
    expect(validateWriteOffReason("a".repeat(500))).toEqual({ reason: "a".repeat(500) });
    expect(validateWriteOffReason("a".repeat(501))).toEqual({ error: "Alasan maksimal 500 karakter." });
  });

  it("counts after trimming", () => {
    expect(validateWriteOffReason(` ${"a".repeat(500)} `)).toEqual({ reason: "a".repeat(500) });
  });
});

describe("WriteOffDialog", () => {
  beforeEach(() => {
    vi.mocked(apiFetch).mockReset();
    vi.mocked(toast.success).mockClear();
    let issued = 0;
    vi.mocked(newIdempotencyKey).mockImplementation(() => `key-${++issued}`);
  });

  it("sends the trimmed reason with one key and reports the written-off total", async () => {
    vi.mocked(apiFetch).mockResolvedValue(result() as never);
    const user = userEvent.setup();
    renderDialog();

    await openDialog(user);
    expect(dialog()).toHaveTextContent("Write-off kontrak MF-202611-0001");
    expect(dialog()).toHaveTextContent(
      "Seluruh sisa piutang dihapusbukukan dan kontrak menjadi TERMINATED. Tindakan ini tidak dapat dibatalkan.",
    );
    expect(dialog()).toHaveTextContent("0/500");
    await typeReason(user, "  rusak total  ");
    expect(dialog()).toHaveTextContent("15/500");
    await submit(user);

    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(calls()).toHaveLength(1);
    expect(apiFetch).toHaveBeenCalledWith(PATH, {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: '{"reason":"rusak total"}',
    });
    expect(toast.success).toHaveBeenCalledWith(
      `Kontrak MF-202611-0001 di-write-off: ${formatMoney("7500000.00")}.`,
    );
  });

  it("shows the field error and sends nothing for a blank reason", async () => {
    const user = userEvent.setup();
    renderDialog();

    await openDialog(user);
    await typeReason(user, "   ");
    await submit(user);

    expect(within(dialog()).getByRole("alert")).toHaveTextContent("Alasan wajib diisi.");
    expect(calls()).toHaveLength(0);
  });

  it("keeps the key and the reason locked after a lost response", async () => {
    vi.mocked(apiFetch)
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(result() as never);
    const user = userEvent.setup();
    const view = renderDialog();

    await openDialog(user);
    await typeReason(user, "  rusak total  ");
    await submit(user);
    expect(await within(dialog()).findByText("Failed to fetch")).toBeInTheDocument();
    expect(within(dialog()).getByLabelText("Alasan write-off")).toBeDisabled();

    // The write-off committed; the refetched contract is TERMINATED, so a new one is no longer offered.
    view.rerender(
      <QueryClientProvider client={view.queryClient}>
        <WriteOffDialog contractId="c1" contractNo="MF-202611-0001" canStartNew={false} />
      </QueryClientProvider>,
    );
    expect(within(dialog()).getByLabelText("Alasan write-off")).toBeDisabled();

    // Closing and reopening keeps the unresolved operation reachable.
    await user.click(within(dialog()).getByRole("button", { name: "Batal" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "Write-off" }));
    expect(within(await screen.findByRole("dialog")).getByText("Failed to fetch")).toBeInTheDocument();

    await user.click(within(dialog()).getByRole("button", { name: "Coba lagi" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(calls()).toHaveLength(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
    expect(sentBody(0)).toEqual({ reason: "rusak total" });
    expect(sentBody(1)).toEqual({ reason: "rusak total" });
    expect(toast.success).toHaveBeenCalledTimes(1);
  });

  it("does not say a write-off cannot start while the retry of a lost response is in flight", async () => {
    vi.mocked(apiFetch)
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockReturnValueOnce(new Promise(() => {}));
    const user = userEvent.setup();
    const view = renderDialog();

    await openDialog(user);
    await typeReason(user, "rusak total");
    await submit(user);
    expect(await within(dialog()).findByText("Failed to fetch")).toBeInTheDocument();
    view.rerender(
      <QueryClientProvider client={view.queryClient}>
        <WriteOffDialog contractId="c1" contractNo="MF-202611-0001" canStartNew={false} />
      </QueryClientProvider>,
    );

    await user.click(within(dialog()).getByRole("button", { name: "Coba lagi" }));
    await within(dialog()).findByRole("button", { name: "Memproses…" });

    expect(within(dialog()).queryByText("Write-off tidak dapat dimulai")).not.toBeInTheDocument();
  });

  it("treats a 503 as an unknown outcome and retries with the same key", async () => {
    vi.mocked(apiFetch)
      .mockRejectedValueOnce(new ApiError("SERVICE_UNAVAILABLE", "Layanan tidak tersedia", 503))
      .mockResolvedValueOnce(result() as never);
    const user = userEvent.setup();
    renderDialog();

    await openDialog(user);
    await typeReason(user, "rusak total");
    await submit(user);

    expect(await within(dialog()).findByText("Layanan tidak tersedia")).toBeInTheDocument();
    expect(within(dialog()).getByLabelText("Alasan write-off")).toBeDisabled();
    expect(within(dialog()).queryByRole("button", { name: "Write-off sekarang" })).not.toBeInTheDocument();
    await user.click(within(dialog()).getByRole("button", { name: "Coba lagi" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(calls()).toHaveLength(2);
    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-1");
    expect(sentBody(1)).toEqual({ reason: "rusak total" });
  });

  it("CONTRACT_STATE_INVALID is definitive", async () => {
    vi.mocked(apiFetch).mockRejectedValue(
      new ApiError("CONTRACT_STATE_INVALID", "Kontrak tidak berstatus ACTIVE.", 409),
    );
    const user = userEvent.setup();
    renderDialog();

    await openDialog(user);
    await typeReason(user, "rusak total");
    await submit(user);

    expect(await within(dialog()).findByText("Kontrak tidak berstatus ACTIVE.")).toBeInTheDocument();
    expect(within(dialog()).getByLabelText("Alasan write-off")).toBeEnabled();
    expect(within(dialog()).queryByRole("button", { name: "Coba lagi" })).not.toBeInTheDocument();
    expect(within(dialog()).getByRole("button", { name: "Write-off sekarang" })).toBeEnabled();
  });

  it("a spent key remounts with a new key and an empty reason", async () => {
    vi.mocked(apiFetch)
      .mockRejectedValueOnce(new ApiError("IDEMPOTENCY_KEY_EXPIRED", "Kunci kedaluwarsa.", 409))
      .mockResolvedValueOnce(result() as never);
    const user = userEvent.setup();
    renderDialog();

    await openDialog(user);
    await typeReason(user, "rusak total");
    await submit(user);
    await user.click(await within(dialog()).findByRole("button", { name: "Mulai form baru" }));

    const reason = await within(dialog()).findByLabelText("Alasan write-off");
    expect(reason).toHaveValue("");
    expect(reason).toBeEnabled();
    await typeReason(user, "rusak total");
    await submit(user);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    expect(sentKey(0)).toBe("key-1");
    expect(sentKey(1)).toBe("key-2");
  });
});
