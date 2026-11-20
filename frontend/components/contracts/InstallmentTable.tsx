"use client";

import { InstallmentStatusBadge } from "@/components/contracts/ContractStatusBadge";
import { DateDisplay } from "@/components/shared/DateDisplay";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { addMoney } from "@/lib/decimal";
import type { Installment } from "@/types/contract";

/**
 * The reusable schedule table (FE §2.5).
 *
 * <p>`recognized_total` and `resolved_amount` are not separate wire fields, so they are derived
 * exactly as the server's `InstallmentBalance` defines them (DM §1.4):
 * `principal + recognized interest + effective penalty` and
 * `paid + settled + written off`. "Bunga" shows the recognised interest because only billed
 * interest is receivable — future scheduled interest is deliberately not part of the total
 * (ADR-011). Money is summed with `BigInt` string arithmetic, never with floats.
 */
export function InstallmentTable({ installments }: { installments: Installment[] }) {
  if (installments.length === 0) {
    return (
      <p className="py-6 text-sm text-muted-foreground">
        Belum ada jadwal angsuran. Jadwal dibuat saat kontrak diaktivasi.
      </p>
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-12 text-right">No.</TableHead>
          <TableHead>Jatuh Tempo</TableHead>
          <TableHead className="text-right">Pokok</TableHead>
          <TableHead className="text-right">Bunga</TableHead>
          <TableHead className="text-right">Denda</TableHead>
          <TableHead className="text-right">Total Tagihan</TableHead>
          <TableHead className="text-right">Terbayar</TableHead>
          <TableHead className="text-right">Sisa</TableHead>
          <TableHead>Status</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {installments.map((installment) => {
          const recognizedTotal = addMoney(
            addMoney(installment.principal_amount, installment.recognized_interest_amount),
            installment.penalty_amount,
          );
          const resolved = addMoney(
            addMoney(installment.paid_amount, installment.settled_amount),
            installment.written_off_amount,
          );
          return (
            <TableRow key={installment.id}>
              <TableCell className="text-right font-mono text-xs">{installment.period_no}</TableCell>
              <TableCell>
                <DateDisplay value={installment.due_date} />
              </TableCell>
              <TableCell className="text-right">
                <MoneyDisplay value={installment.principal_amount} />
              </TableCell>
              <TableCell className="text-right">
                <MoneyDisplay value={installment.recognized_interest_amount} />
              </TableCell>
              <TableCell className="text-right">
                <MoneyDisplay value={installment.penalty_amount} />
              </TableCell>
              <TableCell className="text-right font-medium">
                <MoneyDisplay value={recognizedTotal} />
              </TableCell>
              <TableCell className="text-right">
                <MoneyDisplay value={resolved} />
              </TableCell>
              <TableCell className="text-right">
                <MoneyDisplay value={installment.outstanding} />
              </TableCell>
              <TableCell>
                <InstallmentStatusBadge status={installment.status} />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
