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
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { useWriteOff } from "@/hooks/useContract";
import { useIdempotencyKey } from "@/hooks/useIdempotencyKey";
import { ApiError } from "@/lib/api";
import { formatMoney } from "@/lib/format";
import { isSpentKeyError } from "@/lib/idempotency";

const REASON_MAX_LENGTH = 500;

/**
 * Trims the reason and checks it against the API's `@NotBlank @Size(max = 500)`. `String.length()` on the
 * server counts UTF-16 code units, exactly like `String.prototype.length` here, so the limits agree.
 */
export function validateWriteOffReason(input: string): { reason: string } | { error: string } {
  const reason = input.trim();
  if (reason.length === 0) {
    return { error: "Alasan wajib diisi." };
  }
  if (reason.length > REASON_MAX_LENGTH) {
    return { error: `Alasan maksimal ${REASON_MAX_LENGTH} karakter.` };
  }
  return { reason };
}

/**
 * One logical write-off operation: its `Idempotency-Key`, the reason typed, the attempt's outcome and the
 * dialog's open state all live here, and none of it is reset by closing the dialog (FE §1.4, ADR-017). A
 * write-off whose response was lost may still have committed, so closing then reopening must not turn the
 * retry into a second operation. Only `WriteOffDialog` remounts this component — after a success, or when
 * the operator explicitly starts a new form — and a remount is the only thing that mints a new key.
 */
function WriteOffOperation({
  contractId,
  contractNo,
  canStartNew,
  initiallyOpen,
  onCompleted,
  onRestart,
}: {
  contractId: string;
  contractNo: string;
  canStartNew: boolean;
  initiallyOpen: boolean;
  onCompleted: () => void;
  onRestart: () => void;
}) {
  const idempotencyKey = useIdempotencyKey();
  const [open, setOpen] = useState(initiallyOpen);
  const [reasonInput, setReasonInput] = useState("");
  const [fieldError, setFieldError] = useState<string | null>(null);
  const mutation = useWriteOff(contractId);
  const spentKeyError = mutation.isError && isSpentKeyError(mutation.error) ? mutation.error : null;
  // The outcome is unknown when no definite answer came back (network error, 5xx): the write-off may have
  // committed. A 4xx answer rolled the claim back, so nothing is outstanding and the reason may be edited.
  const outcomeUnknown =
    mutation.isError && !(mutation.error instanceof ApiError && mutation.error.status < 500);
  const unresolved = outcomeUnknown || spentKeyError !== null;
  // A new write-off needs an ACTIVE contract. Re-sending one whose outcome is unknown does not: it carries
  // its original key and body, so the server replays the stored result (or rejects it truthfully).
  const canSubmit = (canStartNew || outcomeUnknown) && spentKeyError === null;
  const startBlocked = !canStartNew && !outcomeUnknown && spentKeyError === null;

  function changeOpen(next: boolean) {
    if (!next && mutation.isPending) {
      // Closing now would hide the outcome of a write-off that is still in flight.
      return;
    }
    setOpen(next);
  }

  async function handleWriteOff() {
    if (!canSubmit) {
      return;
    }
    const result = validateWriteOffReason(reasonInput);
    if ("error" in result) {
      setFieldError(result.error);
      return;
    }
    setFieldError(null);
    try {
      const written = await mutation.mutateAsync({ payload: { reason: result.reason }, idempotencyKey });
      setOpen(false);
      toast.success(`Kontrak ${written.contract_no} di-write-off: ${formatMoney(written.total_written_off)}.`);
      onCompleted();
    } catch {
      // The mutation error renders inside the dialog and stays until the next attempt; the key is kept.
    }
  }

  const showTrigger = open || unresolved || canStartNew;

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      {showTrigger ? (
        <DialogTrigger asChild>
          <Button size="sm" variant="outline" className="border-destructive/40 text-destructive hover:text-destructive">
            Write-off
          </Button>
        </DialogTrigger>
      ) : null}
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Write-off kontrak {contractNo}</DialogTitle>
          <DialogDescription>
            Seluruh sisa piutang dihapusbukukan dan kontrak menjadi TERMINATED. Tindakan ini tidak dapat
            dibatalkan.
          </DialogDescription>
        </DialogHeader>
        {startBlocked ? (
          <Alert>
            <AlertTitle>Write-off tidak dapat dimulai</AlertTitle>
            <AlertDescription>
              Kontrak ini tidak lagi berstatus ACTIVE, sehingga write-off baru tidak dapat dimulai.
            </AlertDescription>
          </Alert>
        ) : null}
        {spentKeyError !== null ? (
          <Alert variant="destructive">
            <AlertTitle>Write-off tidak dicatat</AlertTitle>
            <AlertDescription>
              <span>
                Permintaan ini tidak dapat diulang dengan isian ini: kunci formulirnya sudah dipakai atau tidak
                berlaku lagi, dan write-off sebelumnya mungkin sudah tercatat. Periksa status kontrak lebih dulu,
                lalu mulai form baru.
              </span>
              <span className="ml-2 font-mono text-xs opacity-70">{spentKeyError.code}</span>
              <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRestart}>
                Mulai form baru
              </Button>
            </AlertDescription>
          </Alert>
        ) : mutation.isError ? (
          <div className="space-y-2">
            <ErrorAlert error={mutation.error} title="Write-off tidak dapat dilakukan" />
            {outcomeUnknown ? (
              <p className="text-xs text-muted-foreground">
                Hasilnya belum pasti. Coba lagi mengirim permintaan yang sama: kontrak tidak akan di-write-off
                dua kali.
              </p>
            ) : null}
          </div>
        ) : null}
        <div className="space-y-1.5">
          <Label htmlFor="write-off-reason">Alasan write-off</Label>
          <Textarea
            id="write-off-reason"
            value={reasonInput}
            onChange={(event) => setReasonInput(event.target.value)}
            disabled={mutation.isPending || outcomeUnknown}
            aria-invalid={fieldError !== null}
          />
          <div className="flex justify-between gap-2">
            {fieldError === null ? (
              <span />
            ) : (
              <p className="text-xs text-destructive" role="alert">
                {fieldError}
              </p>
            )}
            <span className="text-xs text-muted-foreground">
              {reasonInput.length}/{REASON_MAX_LENGTH}
            </span>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => changeOpen(false)} disabled={mutation.isPending}>
            Batal
          </Button>
          {outcomeUnknown ? (
            <Button variant="destructive" onClick={handleWriteOff} disabled={mutation.isPending}>
              {mutation.isPending ? "Memproses…" : "Coba lagi"}
            </Button>
          ) : (
            <Button variant="destructive" onClick={handleWriteOff} disabled={mutation.isPending || !canSubmit}>
              {mutation.isPending ? "Memproses…" : "Write-off sekarang"}
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * Write off a contract (F5, T19): the whole remaining receivable is written off and the contract becomes
 * TERMINATED, with a mandatory reason. The endpoint is retry-safe through an `Idempotency-Key` (ADR-017).
 * This wrapper stays mounted whatever the contract status does, so an outcome the operator has not yet read
 * is never unmounted away (T39 decision 8); `canStartNew` gates only a *new* write-off.
 */
export function WriteOffDialog({
  contractId,
  contractNo,
  canStartNew,
}: {
  contractId: string;
  contractNo: string;
  canStartNew: boolean;
}) {
  const [operation, setOperation] = useState({ id: 0, initiallyOpen: false });

  return (
    <WriteOffOperation
      key={operation.id}
      contractId={contractId}
      contractNo={contractNo}
      canStartNew={canStartNew}
      initiallyOpen={operation.initiallyOpen}
      onCompleted={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: false }))}
      onRestart={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: true }))}
    />
  );
}
