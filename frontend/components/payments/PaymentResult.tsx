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
import { allocationLabel, channelLabel } from "@/lib/paymentLabels";
import type { ContractStatus } from "@/types/contract";
import type { Payment } from "@/types/payment";

/**
 * Outcome of a posted payment (FE §2.6): the server's allocation lines in the order the waterfall
 * applied them (ADR-009), rendered as returned — the browser never recomputes allocation. A positive
 * `excess_amount` is customer credit that is never applied automatically (PRD P-4), so it is flagged.
 * A replayed request returns the same payment, so this view is identical for a retry.
 *
 * <p>`contractStatus` is the contract's *confirmed* status after the payment, or `null` when it could not be
 * confirmed (the refetch failed or is still running, so cached data may predate the payment). Only an
 * ACTIVE contract takes another payment or has credit applied from the Kredit tab; a CLOSED one (the payment
 * matured it) offers neither, and an unknown one offers neither until the operator has checked the contract.
 */
export function PaymentResult({
  contractId,
  payment,
  onRecordAnother,
  contractStatus,
}: {
  contractId: string;
  payment: Payment;
  onRecordAnother: () => void;
  contractStatus: ContractStatus | null;
}) {
  const installments = useInstallments(contractId);
  const hasExcess = compareMoney(payment.excess_amount, "0.00") > 0;
  const contractActive = contractStatus === "ACTIVE";
  const statusUnknown = contractStatus === null;

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

      {statusUnknown ? (
        <Alert>
          <AlertTitle>Status kontrak belum dapat dipastikan</AlertTitle>
          <AlertDescription>
            Pembayaran sudah tercatat, tetapi status kontrak terbaru tidak dapat dimuat. Buka halaman kontrak untuk
            memeriksa status dan saldo sebelum mencatat pembayaran lain atau menerapkan kredit.
          </AlertDescription>
        </Alert>
      ) : null}

      {hasExcess ? (
        <Alert>
          <AlertTitle>Ada kelebihan pembayaran</AlertTitle>
          <AlertDescription>
            {`Kelebihan ${formatMoney(payment.excess_amount)} disimpan sebagai kredit nasabah dan tidak dipakai otomatis.`}
            {contractActive ? " Terapkan dari tab Kredit." : null}
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
                <TableCell>{allocationLabel(allocation.allocation_type)}</TableCell>
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
        {contractActive ? (
          <Button variant="outline" onClick={onRecordAnother}>
            Catat pembayaran lain
          </Button>
        ) : null}
      </div>
    </div>
  );
}
