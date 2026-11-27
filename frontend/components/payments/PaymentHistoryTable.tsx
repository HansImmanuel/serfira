"use client";

import { useEffect, useRef, useState } from "react";

import { periodLabel } from "@/components/contracts/installmentLabels";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { formatDateTime } from "@/lib/format";
import { allocationLabel, channelLabel } from "@/lib/paymentLabels";
import { cn } from "@/lib/utils";
import type { Installment } from "@/types/contract";
import type { AllocationType, ContractPayment, PaymentAllocation } from "@/types/payment";

/** Waterfall order inside one installment (ADR-009): penalty, then interest, then principal. */
const WATERFALL_RANK: Record<AllocationType, number> = { PENALTY: 0, INTEREST: 1, PRINCIPAL: 2, EXCESS: 3 };

/**
 * The API returns a payment's allocation lines in no defined order, so the display order is derived here:
 * by the installment's period, then by waterfall rank, with EXCESS (no installment) last. Only the order is
 * derived; every amount is shown as returned.
 */
function sortAllocations(
  allocations: PaymentAllocation[],
  installments: Installment[] | undefined,
): PaymentAllocation[] {
  const periodOf = (allocation: PaymentAllocation): number => {
    if (allocation.installment_id === null) {
      return Number.POSITIVE_INFINITY;
    }
    const match = installments?.find((installment) => installment.id === allocation.installment_id);
    // A schedule that has not loaded (or lacks the id) sorts after the known periods rather than guessing one.
    return match?.period_no ?? Number.MAX_SAFE_INTEGER;
  };
  return [...allocations].sort((a, b) => {
    const byPeriod = periodOf(a) === periodOf(b) ? 0 : periodOf(a) < periodOf(b) ? -1 : 1;
    return byPeriod || WATERFALL_RANK[a.allocation_type] - WATERFALL_RANK[b.allocation_type];
  });
}

function PaymentRow({
  payment,
  installments,
  highlighted,
}: {
  payment: ContractPayment;
  installments: Installment[] | undefined;
  highlighted: boolean;
}) {
  const [expanded, setExpanded] = useState(false);
  const rowRef = useRef<HTMLTableRowElement>(null);
  const voided = payment.status === "VOIDED";

  useEffect(() => {
    if (highlighted) {
      rowRef.current?.scrollIntoView?.({ block: "center" });
    }
  }, [highlighted]);

  return (
    <>
      <TableRow
        id={`payment-${payment.id}`}
        ref={rowRef}
        data-highlighted={highlighted ? "true" : undefined}
        className={cn(highlighted && "bg-amber-50 ring-2 ring-inset ring-amber-300")}
      >
        <TableCell className="font-mono text-xs">{payment.payment_no}</TableCell>
        <TableCell>{formatDateTime(payment.paid_at)}</TableCell>
        <TableCell>{channelLabel(payment.channel)}</TableCell>
        <TableCell>
          {voided ? (
            <div className="space-y-0.5 text-xs">
              <Badge variant="outline" className="border-red-200 bg-red-50 text-red-700">
                VOIDED
              </Badge>
              {payment.voided_at === null ? null : (
                <div className="text-muted-foreground">{`Dibatalkan ${formatDateTime(payment.voided_at)}`}</div>
              )}
              {payment.void_reason === null ? null : (
                <div className="text-muted-foreground">{payment.void_reason}</div>
              )}
            </div>
          ) : (
            <Badge variant="outline">POSTED</Badge>
          )}
        </TableCell>
        <TableCell className="text-right">
          <MoneyDisplay value={payment.amount} className={cn(voided && "text-muted-foreground line-through")} />
        </TableCell>
        <TableCell className="text-right">
          <MoneyDisplay value={payment.excess_amount} className={cn(voided && "text-muted-foreground")} />
        </TableCell>
        <TableCell className="text-right">
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={expanded}
            aria-controls={`payment-${payment.id}-allocations`}
            onClick={() => setExpanded((current) => !current)}
          >
            Rincian
          </Button>
        </TableCell>
      </TableRow>
      {expanded ? (
        <TableRow id={`payment-${payment.id}-allocations`} className="bg-muted/30">
          <TableCell colSpan={7}>
            {payment.allocations.length === 0 ? (
              <p className="text-sm text-muted-foreground">Tidak ada alokasi.</p>
            ) : (
              <ul className="space-y-1 text-sm">
                {sortAllocations(payment.allocations, installments).map((allocation, index) => (
                  <li
                    key={`${allocation.installment_id ?? "excess"}-${allocation.allocation_type}-${index}`}
                    className="flex justify-between gap-4"
                  >
                    <span>
                      {periodLabel(installments, allocation.installment_id)} · {allocationLabel(allocation.allocation_type)}
                    </span>
                    <MoneyDisplay value={allocation.amount} className={cn(voided && "text-muted-foreground")} />
                  </li>
                ))}
              </ul>
            )}
          </TableCell>
        </TableRow>
      ) : null}
    </>
  );
}

/**
 * Payment history (Pembayaran tab): the rows exactly as the API returns them (newest first). A VOIDED
 * payment keeps its row, flagged with the void time and reason; its amounts render muted. `highlightId`
 * marks and scrolls to the payment a statement link points at.
 */
export function PaymentHistoryTable({
  payments,
  installments,
  highlightId,
}: {
  payments: ContractPayment[];
  installments: Installment[] | undefined;
  highlightId: string | null;
}) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>No. pembayaran</TableHead>
          <TableHead>Waktu bayar</TableHead>
          <TableHead>Channel</TableHead>
          <TableHead>Status</TableHead>
          <TableHead className="text-right">Jumlah</TableHead>
          <TableHead className="text-right">Kelebihan</TableHead>
          <TableHead className="text-right">
            <span className="sr-only">Aksi</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {payments.length === 0 ? (
          <TableRow>
            <TableCell colSpan={7} className="py-8 text-center text-sm text-muted-foreground">
              Belum ada pembayaran untuk kontrak ini.
            </TableCell>
          </TableRow>
        ) : (
          payments.map((payment) => (
            <PaymentRow
              key={payment.id}
              payment={payment}
              installments={installments}
              highlighted={payment.id === highlightId}
            />
          ))
        )}
      </TableBody>
    </Table>
  );
}
