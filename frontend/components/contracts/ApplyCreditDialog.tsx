"use client";

import { useState } from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { useIdempotencyKey } from "@/hooks/useIdempotencyKey";
import { useApplyCredit } from "@/hooks/useContract";
import { compareMoney, normalizeMoney } from "@/lib/decimal";
import { formatMoney } from "@/lib/format";
import { ApiError } from "@/lib/api";
import { isSpentKeyError } from "@/lib/idempotency";
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
 * One logical credit-apply operation: its `Idempotency-Key`, the amount typed, the attempt's outcome and the
 * dialog's open state all live here, and none of it is reset by closing the dialog.
 *
 * <p>That is deliberate (FE §1.4, ADR-017): an apply whose response was lost may still have committed, and
 * closing then reopening the dialog must not turn the retry into a second, unrelated application. Only
 * `ApplyCreditDialog` remounts this component — after a success, or when the operator explicitly starts a new
 * form — and a remount is the only thing that mints a new key.
 */
function ApplyCreditOperation({
  contractId,
  availableBalance,
  canStartNew,
  initiallyOpen,
  onCompleted,
  onRestart,
}: {
  contractId: string;
  availableBalance: Money;
  canStartNew: boolean;
  initiallyOpen: boolean;
  onCompleted: () => void;
  onRestart: () => void;
}) {
  const idempotencyKey = useIdempotencyKey();
  // The ceiling the operator saw when this operation began. After an ambiguous failure the live balance
  // may already have dropped (the apply committed), yet a retry of the same amount must still be allowed:
  // the server replays it. The server re-checks the balance for any request it has not seen.
  const [startingBalance] = useState(availableBalance);
  const [open, setOpen] = useState(initiallyOpen);
  const [amount, setAmount] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const mutation = useApplyCredit(contractId);
  const spentKeyError = mutation.isError && isSpentKeyError(mutation.error) ? mutation.error : null;
  // The outcome is unknown when no definite answer came back (network error, 5xx): the apply may have
  // committed. A 4xx answer (balance exceeded, nothing applicable, ...) rolled the claim back, so nothing
  // is outstanding and the live balance is the right ceiling again.
  const outcomeUnknown =
    mutation.isError && !(mutation.error instanceof ApiError && mutation.error.status < 500);
  // An unresolved operation stays recoverable whatever else changed: the balance may be zero or the contract
  // closed by the very apply whose response was lost, and a retry then replays the stored result.
  const unresolved = outcomeUnknown || spentKeyError !== null;

  function changeOpen(next: boolean) {
    if (!next && mutation.isPending) {
      // Closing now would hide the outcome of an apply that is still in flight.
      return;
    }
    setOpen(next);
  }

  async function handleApply() {
    const result = validateCreditAmount(amount, outcomeUnknown ? startingBalance : availableBalance);
    if ("error" in result) {
      setFieldError(result.error);
      return;
    }
    setFieldError(null);
    try {
      const applied = await mutation.mutateAsync({ payload: result.payload, idempotencyKey });
      setOpen(false);
      toast.success(`Kredit diterapkan: ${formatMoney(applied.applied_amount)}.`);
      onCompleted();
    } catch {
      // The mutation error renders inside the dialog and stays until the next attempt; the key is kept.
    }
  }

  const showTrigger = open || unresolved || (canStartNew && compareMoney(availableBalance, "0.00") > 0);

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      {showTrigger ? (
        <DialogTrigger asChild>
          <Button size="sm">Terapkan Kredit</Button>
        </DialogTrigger>
      ) : null}
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Terapkan kredit nasabah?</DialogTitle>
          <DialogDescription>
            Saldo tersedia {formatMoney(availableBalance)} diterapkan ke angsuran jatuh tempo tertua: denda, lalu
            bunga, lalu pokok. Hanya piutang yang sudah diakui yang dikurangi.
          </DialogDescription>
        </DialogHeader>
        {spentKeyError !== null ? (
          <Alert variant="destructive">
            <AlertTitle>Kredit tidak diterapkan</AlertTitle>
            <AlertDescription>
              <span>
                Permintaan ini tidak dapat diulang dengan isian ini: kunci formulirnya sudah dipakai atau tidak
                berlaku lagi, dan penerapan sebelumnya mungkin sudah tercatat. Periksa riwayat pemakaian kredit
                lebih dulu agar kredit tidak diterapkan dua kali, lalu mulai form baru.
              </span>
              <span className="ml-2 font-mono text-xs opacity-70">{spentKeyError.code}</span>
              <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRestart}>
                Mulai form baru
              </Button>
            </AlertDescription>
          </Alert>
        ) : mutation.isError ? (
          <div className="space-y-2">
            <ErrorAlert error={mutation.error} title="Kredit tidak dapat diterapkan" />
            <p className="text-xs text-muted-foreground">
              Jika hasilnya belum pasti, Anda dapat mengirim ulang dengan jumlah yang sama: permintaan yang sama
              tidak akan menerapkan kredit dua kali.
            </p>
          </div>
        ) : null}
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
          <Button variant="outline" onClick={() => changeOpen(false)} disabled={mutation.isPending}>
            Batal
          </Button>
          <Button onClick={handleApply} disabled={mutation.isPending || spentKeyError !== null}>
            {mutation.isPending ? "Menerapkan…" : "Terapkan"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Apply customer credit (T33, Addendum §2.2): reuses the payment waterfall server-side against the
 * oldest due installment with recognized receivable (ADR-009). The browser only chooses how much.
 *
 * <p>The endpoint is retry-safe through an `Idempotency-Key` (TS §2.2, ADR-017): the same key and amount
 * replay the stored result, a different amount under the same key is 409 `CONFLICT`. This wrapper stays
 * mounted whatever the balance or the contract status does, so an outcome the operator has not yet read is
 * never unmounted away. `canStartNew` gates only a *new* application (contract ACTIVE); recovering an
 * unresolved one is never gated by it.
 */
export function ApplyCreditDialog({
  contractId,
  availableBalance,
  canStartNew,
}: {
  contractId: string;
  availableBalance: Money;
  canStartNew: boolean;
}) {
  const [operation, setOperation] = useState({ id: 0, initiallyOpen: false });

  return (
    <ApplyCreditOperation
      key={operation.id}
      contractId={contractId}
      availableBalance={availableBalance}
      canStartNew={canStartNew}
      initiallyOpen={operation.initiallyOpen}
      onCompleted={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: false }))}
      onRestart={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: true }))}
    />
  );
}
