"use client";

import { useState } from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useApplyCredit } from "@/hooks/useContract";
import { compareMoney, normalizeMoney } from "@/lib/decimal";
import { formatMoney } from "@/lib/format";
import type { ApplyCreditPayload, Money } from "@/types/contract";

/**
 * Validates the optional amount against the available balance. Blank means "apply everything" and
 * sends an empty body; anything else must be positive scale-2 money no larger than the balance (the
 * API would answer 400/409 otherwise). The server re-checks the balance at apply time.
 */
export function validateCreditAmount(
  input: string,
  available: Money,
): { payload: ApplyCreditPayload } | { error: string } {
  if (input.trim().length === 0) {
    return { payload: {} };
  }
  const amount = normalizeMoney(input);
  if (amount === null || compareMoney(amount, "0.00") <= 0) {
    return { error: "Jumlah harus lebih dari 0, maksimal 17 digit bulat dan 2 desimal." };
  }
  if (compareMoney(amount, available) > 0) {
    return { error: `Melebihi saldo tersedia (${formatMoney(available)}).` };
  }
  return { payload: { amount } };
}

/**
 * Apply customer credit (T33, Addendum §2.2): reuses the payment waterfall server-side against the
 * oldest due installment with recognized receivable (ADR-009). The browser only chooses how much.
 */
export function ApplyCreditDialog({ contractId, availableBalance }: { contractId: string; availableBalance: Money }) {
  const [open, setOpen] = useState(false);
  const [amount, setAmount] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const mutation = useApplyCredit(contractId);

  function changeOpen(next: boolean) {
    if (!next && mutation.isPending) {
      // Closing now would hide the outcome of an apply that has no Idempotency-Key to retry safely.
      return;
    }
    setOpen(next);
    if (next) {
      setAmount("");
      setFieldError(null);
      mutation.reset();
    }
  }

  async function handleApply() {
    const result = validateCreditAmount(amount, availableBalance);
    if ("error" in result) {
      setFieldError(result.error);
      return;
    }
    setFieldError(null);
    try {
      const applied = await mutation.mutateAsync(result.payload);
      setOpen(false);
      toast.success(`Kredit diterapkan: ${formatMoney(applied.applied_amount)}.`);
    } catch {
      // The mutation error renders inside the dialog; keep it open so the operator can read it.
    }
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      <DialogTrigger asChild>
        <Button size="sm">Terapkan Kredit</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Terapkan kredit nasabah?</DialogTitle>
          <DialogDescription>
            Saldo tersedia {formatMoney(availableBalance)} diterapkan ke angsuran jatuh tempo tertua: denda, lalu
            bunga, lalu pokok. Hanya piutang yang sudah diakui yang dikurangi.
          </DialogDescription>
        </DialogHeader>
        {mutation.isError ? <ErrorAlert error={mutation.error} title="Kredit tidak dapat diterapkan" /> : null}
        <div className="space-y-1.5">
          <Label htmlFor="credit-amount">Jumlah</Label>
          <Input
            id="credit-amount"
            inputMode="decimal"
            autoComplete="off"
            placeholder="Kosongkan untuk menerapkan seluruh saldo"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            aria-invalid={fieldError !== null}
          />
          {fieldError === null ? null : (
            <p className="text-xs text-destructive" role="alert">
              {fieldError}
            </p>
          )}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)} disabled={mutation.isPending}>
            Batal
          </Button>
          <Button onClick={handleApply} disabled={mutation.isPending}>
            {mutation.isPending ? "Menerapkan…" : "Terapkan"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
