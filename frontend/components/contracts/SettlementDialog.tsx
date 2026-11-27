"use client";

import { useIsMutating } from "@tanstack/react-query";
import { useEffect, useState } from "react";
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
import { useIdempotencyKey } from "@/hooks/useIdempotencyKey";
import { executeSettlementKey, useExecuteSettlement, useSettlementQuote } from "@/hooks/useSettlement";
import { ApiError } from "@/lib/api";
import { formatDateTime, formatMoney } from "@/lib/format";
import { isSpentKeyError } from "@/lib/idempotency";
import { formatCountdown, quoteLifetimeMs, remainingMs, settlementBreakdown } from "@/lib/settlement";
import type { Money } from "@/types/contract";
import type { SettlementQuote } from "@/types/settlement";

interface QuoteAnswer {
  title: string;
  description: string;
  /** Whether "Ambil quote baru" is the next action (T39 settlement copy table). */
  requote: boolean;
}

const CREDIT_EXCEEDS_SETTLEMENT: QuoteAnswer = {
  title: "Kredit nasabah melebihi total pelunasan.",
  description: "Pelunasan tidak dapat dieksekusi; refund kredit di luar MVP.",
  requote: false,
};

/** The definite answers that end this quote, with the copy FE §2.8 gives them ("kadaluarsa" verbatim). */
const QUOTE_ANSWERS: ReadonlyMap<string, QuoteAnswer> = new Map([
  [
    "STALE_SETTLEMENT_QUOTE",
    {
      title: "Quote sudah kadaluarsa, ambil quote baru",
      description: "Kontrak berubah sejak quote dibuat.",
      requote: true,
    },
  ],
  [
    "SETTLEMENT_QUOTE_EXPIRED",
    {
      title: "Quote sudah kadaluarsa, ambil quote baru",
      description: "Masa berlaku quote sudah lewat.",
      requote: true,
    },
  ],
  [
    "SETTLEMENT_QUOTE_ALREADY_EXECUTED",
    {
      title: "Quote ini sudah dieksekusi.",
      description: "Periksa status kontrak sebelum melanjutkan.",
      requote: false,
    },
  ],
  ["CREDIT_EXCEEDS_SETTLEMENT", CREDIT_EXCEEDS_SETTLEMENT],
]);

function SettlementTrigger() {
  return (
    <DialogTrigger asChild>
      <Button variant="outline" size="sm">
        Pelunasan Dipercepat
      </Button>
    </DialogTrigger>
  );
}

function StartBlockedNotice() {
  return (
    <Alert>
      <AlertTitle>Pelunasan tidak dapat dimulai</AlertTitle>
      <AlertDescription>
        Kontrak ini tidak lagi berstatus ACTIVE, sehingga pelunasan baru tidak dapat dimulai.
      </AlertDescription>
    </Alert>
  );
}

function BreakdownRow({
  label,
  value,
  deduction = false,
  total = false,
}: {
  label: string;
  value: Money | null;
  deduction?: boolean;
  total?: boolean;
}) {
  return (
    <tr className={total ? "border-t font-semibold" : undefined}>
      <th scope="row" className="py-1 pr-4 text-left font-normal text-muted-foreground">
        {label}
      </th>
      <td className="py-1 text-right tabular-nums">
        {deduction ? "− " : null}
        {formatMoney(value)}
      </td>
    </tr>
  );
}

function SettlementBreakdownTable({
  quote,
  futureInterestGross,
  cashDue,
}: {
  quote: SettlementQuote;
  futureInterestGross: Money;
  cashDue: Money | null;
}) {
  return (
    <table className="w-full text-sm">
      <tbody>
        <BreakdownRow label="Outstanding Pokok" value={quote.outstanding_principal} />
        <BreakdownRow label="Bunga Tertunggak" value={quote.unpaid_billed_interest} />
        <BreakdownRow label="Bunga Berjalan" value={quote.accrued_interest} />
        <BreakdownRow label="Bunga Masa Depan" value={futureInterestGross} />
        <BreakdownRow label="Rebate" value={quote.rebate_amount} deduction />
        <BreakdownRow label="Denda" value={quote.penalty_outstanding} />
        <BreakdownRow label="Admin Fee" value={quote.admin_fee} />
        <BreakdownRow label="Total Pelunasan" value={quote.gross_amount} total />
        <BreakdownRow label="Kredit Nasabah" value={quote.available_credit} deduction />
        <BreakdownRow label="Total Cash Due" value={cashDue} total />
      </tbody>
    </table>
  );
}

/**
 * The execution of one quote: its `Idempotency-Key`, its countdown and the attempt's outcome. It is keyed by
 * `quote_id` (T39 decision 7), so a new quote is a new execution with a new key — a new `quote_id` under an
 * old key would be `CONFLICT` — while closing and reopening the dialog keeps all of it. It renders the
 * dialog's content and its trigger, but not the `Dialog` root, which the operation owns: the root stays
 * mounted while the dialog is closed, and so does this component.
 */
function SettlementExecutionStep({
  contractId,
  quote,
  receivedAt,
  open,
  canStartNew,
  requoting,
  requoteError,
  onRequote,
  onClose,
  onSucceeded,
  onRestart,
}: {
  contractId: string;
  quote: SettlementQuote;
  receivedAt: number;
  open: boolean;
  canStartNew: boolean;
  requoting: boolean;
  requoteError: Error | null;
  onRequote: () => void;
  onClose: () => void;
  onSucceeded: () => void;
  onRestart: () => void;
}) {
  const idempotencyKey = useIdempotencyKey();
  const mutation = useExecuteSettlement(contractId);
  const [confirming, setConfirming] = useState(false);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // Lifetime from the two server instants, elapsed time from the local clock since the response arrived
  // (T39 decision 6): a browser clock that is years off still counts the server's lifetime.
  const remaining = remainingMs(quoteLifetimeMs(quote), receivedAt, now);
  const expired = remaining === 0;
  const { futureInterestGross, cashDue, creditExceedsGross } = settlementBreakdown(quote);

  const spentKeyError = mutation.isError && isSpentKeyError(mutation.error) ? mutation.error : null;
  // A 4xx is a definite answer: the server rolled the claim back. Anything else (network error, 5xx) leaves
  // the outcome unknown: the settlement may have committed and closed the contract. Only a retry with the
  // same key and quote is then offered, and the server replays the stored result.
  const definiteError = mutation.error instanceof ApiError && mutation.error.status < 500 ? mutation.error : null;
  const outcomeUnknown = mutation.isError && definiteError === null;
  const unresolved = outcomeUnknown || spentKeyError !== null;
  const serverAnswer = definiteError === null ? undefined : QUOTE_ANSWERS.get(definiteError.code);
  const answer = serverAnswer ?? (creditExceedsGross ? CREDIT_EXCEEDS_SETTLEMENT : undefined);
  // Any other 4xx (state invalid, concurrent modification, ...) leaves the quote usable; the server re-checks.
  const canExecute =
    canStartNew && !unresolved && !expired && answer === undefined && !mutation.isPending;
  // A pending execute (a "Coba lagi" in flight included) is still unresolved: a new quote would remount
  // this step under a new key and drop the attempt's outcome (T39 decision 7).
  const offerRequote =
    canStartNew && !unresolved && !mutation.isPending && (answer === undefined ? expired : answer.requote);
  const startBlocked = !canStartNew && !unresolved;

  async function execute() {
    if (!canExecute && !outcomeUnknown) {
      // Defence in depth: the buttons are disabled in these states, but the rule must not depend on it.
      return;
    }
    try {
      const settlement = await mutation.mutateAsync({ quoteId: quote.quote_id, idempotencyKey });
      toast.success(
        `Pelunasan ${settlement.settlement_no} berhasil. Kas diterima ${formatMoney(settlement.cash_received)}; kontrak ditutup.`,
      );
      onSucceeded();
    } catch {
      // The error renders with the breakdown and stays until the next attempt; the key is kept.
      setConfirming(false);
    }
  }

  return (
    <>
      {open || unresolved || canStartNew ? <SettlementTrigger /> : null}
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Pelunasan Dipercepat</DialogTitle>
          <DialogDescription>
            Quote {quote.quote_no}, dihitung {formatDateTime(quote.quoted_at)}. Berlaku{" "}
            <span role="timer" className="font-mono tabular-nums">
              {formatCountdown(remaining)}
            </span>
            .
          </DialogDescription>
        </DialogHeader>
        {confirming ? (
          <>
            <p>
              Terima kas {formatMoney(cashDue)} dari nasabah dan tutup kontrak? Tindakan ini tidak dapat
              dibatalkan.
            </p>
            <DialogFooter>
              <Button variant="outline" onClick={() => setConfirming(false)} disabled={mutation.isPending}>
                Kembali
              </Button>
              <Button onClick={execute} disabled={!canExecute}>
                {mutation.isPending ? "Memproses…" : "Ya, lunasi"}
              </Button>
            </DialogFooter>
          </>
        ) : (
          <>
            <SettlementBreakdownTable quote={quote} futureInterestGross={futureInterestGross} cashDue={cashDue} />
            {startBlocked ? <StartBlockedNotice /> : null}
            {spentKeyError !== null ? (
              <Alert variant="destructive">
                <AlertTitle>Pelunasan tidak diproses</AlertTitle>
                <AlertDescription>
                  <span>
                    Permintaan ini tidak dapat diulang: kunci formulirnya sudah dipakai atau tidak berlaku lagi,
                    dan pelunasan sebelumnya mungkin sudah tercatat. Periksa status kontrak lebih dulu agar
                    kontrak tidak dilunasi dua kali, lalu mulai form baru.
                  </span>
                  <span className="ml-2 font-mono text-xs opacity-70">{spentKeyError.code}</span>
                  <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRestart}>
                    Mulai form baru
                  </Button>
                </AlertDescription>
              </Alert>
            ) : answer !== undefined ? (
              <Alert variant="destructive">
                <AlertTitle>{answer.title}</AlertTitle>
                <AlertDescription>
                  <span>{answer.description}</span>
                  {serverAnswer === undefined ? null : (
                    <span className="ml-2 font-mono text-xs opacity-70">{definiteError?.code}</span>
                  )}
                </AlertDescription>
              </Alert>
            ) : mutation.isError ? (
              <div className="space-y-2">
                <ErrorAlert error={mutation.error} title="Pelunasan tidak dapat dieksekusi" />
                {outcomeUnknown ? (
                  <p className="text-xs text-muted-foreground">
                    Hasilnya belum pasti. Coba lagi mengirim permintaan yang sama: kontrak tidak akan dilunasi
                    dua kali.
                  </p>
                ) : null}
              </div>
            ) : null}
            {requoteError === null ? null : <ErrorAlert error={requoteError} title="Quote tidak dapat dibuat" />}
            <DialogFooter>
              <Button variant="outline" onClick={onClose} disabled={mutation.isPending || requoting}>
                Batal
              </Button>
              {offerRequote ? (
                <Button variant="outline" onClick={onRequote} disabled={requoting}>
                  {requoting ? "Memuat quote…" : "Ambil quote baru"}
                </Button>
              ) : null}
              {outcomeUnknown ? (
                <Button onClick={execute}>Coba lagi</Button>
              ) : (
                <Button onClick={() => setConfirming(true)} disabled={!canExecute}>
                  {mutation.isPending ? "Memproses…" : "Lunasi Sekarang"}
                </Button>
              )}
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </>
  );
}

/**
 * One logical settlement: the dialog's open state and the current quote live here, and none of it is reset
 * by closing the dialog. The quote is a mutation without a key (each call is a new quote); the execution
 * of a quote lives in `SettlementExecutionStep`, remounted by `quote_id`. Only `SettlementDialog` remounts
 * this component — after a success, or when the operator starts a new form after a spent key.
 */
function SettlementOperation({
  contractId,
  canStartNew,
  initiallyOpen,
  onCompleted,
  onRestart,
}: {
  contractId: string;
  canStartNew: boolean;
  initiallyOpen: boolean;
  onCompleted: () => void;
  onRestart: () => void;
}) {
  const [open, setOpen] = useState(initiallyOpen);
  const [quoted, setQuoted] = useState<{ quote: SettlementQuote; receivedAt: number } | null>(null);
  const quoteMutation = useSettlementQuote(contractId);
  const executing = useIsMutating({ mutationKey: executeSettlementKey(contractId) }) > 0;

  function changeOpen(next: boolean) {
    if (!next && (executing || quoteMutation.isPending)) {
      // Closing now would hide the outcome of a request that is still in flight.
      return;
    }
    setOpen(next);
  }

  async function requestQuote() {
    if (!canStartNew || executing) {
      return;
    }
    try {
      const quote = await quoteMutation.mutateAsync();
      setQuoted({ quote, receivedAt: Date.now() });
    } catch {
      // The quote error renders inside the dialog; a quote carries no key, so asking again is safe.
    }
  }

  return (
    <Dialog open={open} onOpenChange={changeOpen}>
      {quoted === null ? (
        <>
          {open || canStartNew ? <SettlementTrigger /> : null}
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>Pelunasan Dipercepat</DialogTitle>
              <DialogDescription>
                Quote menghitung total pelunasan kontrak saat ini. Bunga dan denda yang sudah jatuh tempo
                dibukukan lebih dulu, dan quote hanya berlaku untuk waktu terbatas.
              </DialogDescription>
            </DialogHeader>
            {canStartNew ? null : <StartBlockedNotice />}
            {quoteMutation.isError ? (
              <ErrorAlert error={quoteMutation.error} title="Quote tidak dapat dibuat" />
            ) : null}
            <DialogFooter>
              <Button variant="outline" onClick={() => changeOpen(false)} disabled={quoteMutation.isPending}>
                Batal
              </Button>
              <Button onClick={requestQuote} disabled={!canStartNew || quoteMutation.isPending}>
                {quoteMutation.isPending ? "Memuat quote…" : "Lihat Quote Pelunasan"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </>
      ) : (
        <SettlementExecutionStep
          key={quoted.quote.quote_id}
          contractId={contractId}
          quote={quoted.quote}
          receivedAt={quoted.receivedAt}
          open={open}
          canStartNew={canStartNew}
          requoting={quoteMutation.isPending}
          requoteError={quoteMutation.isError ? quoteMutation.error : null}
          onRequote={requestQuote}
          onClose={() => changeOpen(false)}
          onSucceeded={() => {
            setOpen(false);
            onCompleted();
          }}
          onRestart={onRestart}
        />
      )}
    </Dialog>
  );
}

/**
 * Early settlement (T39, FE §2.8, ADR-018): quote, review the breakdown under a countdown, confirm, execute.
 *
 * <p>Execution is retry-safe through one `Idempotency-Key` per quote (ADR-017). This wrapper stays mounted
 * whatever the contract status does (T39 decision 8): `canStartNew` gates only a *new* settlement, so a
 * settlement whose response was lost — and which may already have closed the contract — can still be
 * retried and replayed. Success closes the dialog; the refetched contract then shows CLOSED.
 */
export function SettlementDialog({ contractId, canStartNew }: { contractId: string; canStartNew: boolean }) {
  const [operation, setOperation] = useState({ id: 0, initiallyOpen: false });

  return (
    <SettlementOperation
      key={operation.id}
      contractId={contractId}
      canStartNew={canStartNew}
      initiallyOpen={operation.initiallyOpen}
      onCompleted={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: false }))}
      onRestart={() => setOperation((current) => ({ id: current.id + 1, initiallyOpen: true }))}
    />
  );
}
