"use client";

import Link from "next/link";
import { CheckCircle2 } from "lucide-react";

import { periodLabel } from "@/components/contracts/installmentLabels";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useInstallments } from "@/hooks/useContract";
import { compareMoney } from "@/lib/decimal";
import { formatDateTime, formatMoney } from "@/lib/format";
import { PAYMENT_CHANNEL_OPTIONS } from "@/lib/schemas/payment";
import type { AllocationType, Payment } from "@/types/payment";

const ALLOCATION_LABELS: Record<AllocationType, string> = {
  PENALTY: "Denda",
  INTEREST: "Bunga",
  PRINCIPAL: "Pokok",
  EXCESS: "Kelebihan",
};

function channelLabel(channel: Payment["channel"]): string {
  return PAYMENT_CHANNEL_OPTIONS.find((option) => option.value === channel)?.label ?? channel;
}

/**
 * Outcome of a posted payment (FE §2.6): the server's allocation lines in the order the waterfall
 * applied them (ADR-009), rendered as returned — the browser never recomputes allocation. A positive
 * `excess_amount` is customer credit that is never applied automatically (PRD P-4), so it is flagged.
 * A replayed request returns the same payment, so this view is identical for a retry.
 */
export function PaymentResult({
  contractId,
  payment,
  onRecordAnother,
}: {
  contractId: string;
  payment: Payment;
  onRecordAnother: () => void;
}) {
  const installments = useInstallments(contractId);
  const hasExcess = compareMoney(payment.excess_amount, "0.00") > 0;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <CheckCircle2 className="size-5 text-emerald-600" aria-hidden="true" />
            Pembayaran tercatat
          </CardTitle>
        </CardHeader>
        <CardContent>
          <dl className="grid gap-3 text-sm sm:grid-cols-2">
            <div>
              <dt className="text-xs text-muted-foreground">No. pembayaran</dt>
              <dd className="font-mono">{payment.payment_no}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Waktu bayar (server)</dt>
              <dd>{formatDateTime(payment.paid_at)}</dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Jumlah</dt>
              <dd>
                <MoneyDisplay value={payment.amount} />
              </dd>
            </div>
            <div>
              <dt className="text-xs text-muted-foreground">Channel</dt>
              <dd>{channelLabel(payment.channel)}</dd>
            </div>
          </dl>
        </CardContent>
      </Card>

      {hasExcess ? (
        <Alert>
          <AlertTitle>Ada kelebihan pembayaran</AlertTitle>
          <AlertDescription>
            {`Kelebihan ${formatMoney(payment.excess_amount)} disimpan sebagai kredit nasabah dan tidak dipakai otomatis. Terapkan dari tab Kredit.`}
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Angsuran</TableHead>
              <TableHead>Komponen</TableHead>
              <TableHead className="text-right">Jumlah</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {payment.allocations.map((allocation, index) => (
              <TableRow key={`${allocation.installment_id ?? "excess"}-${allocation.allocation_type}-${index}`}>
                <TableCell>{periodLabel(installments.data, allocation.installment_id)}</TableCell>
                <TableCell>{ALLOCATION_LABELS[allocation.allocation_type]}</TableCell>
                <TableCell className="text-right">
                  <MoneyDisplay value={allocation.amount} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>

      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link href={`/contracts/${contractId}`}>Kembali ke kontrak</Link>
        </Button>
        <Button variant="outline" onClick={onRecordAnother}>
          Catat pembayaran lain
        </Button>
      </div>
    </div>
  );
}
