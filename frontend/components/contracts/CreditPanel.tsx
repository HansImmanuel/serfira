"use client";

import { ApplyCreditDialog } from "@/components/contracts/ApplyCreditDialog";
import { periodLabel } from "@/components/contracts/installmentLabels";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useCredit, useInstallments } from "@/hooks/useContract";
import { useCanReadCredit, useCanWrite } from "@/hooks/useSession";
import { compareMoney } from "@/lib/decimal";
import { formatDateTime } from "@/lib/format";
import type { ContractStatus } from "@/types/contract";

/**
 * Customer credit from excess payments (FE §2.4 Kredit tab).
 *
 * <p>The balance and the application history come straight from `GET /contracts/{id}/credit` so the
 * audit trail is never reconstructed in the browser. `ADMIN_OPERASIONAL` may apply available credit
 * on an ACTIVE contract (the API answers 409 for any other status); the button is UX only.
 *
 * <p>The endpoint is limited to `ADMIN_OPERASIONAL`/`FINANCE`, so the panel is not rendered and the
 * query is not issued for `MANAJEMEN` (defence in depth: the tab is also hidden by the parent).
 */
export function CreditPanel({ contractId, contractStatus }: { contractId: string; contractStatus: ContractStatus }) {
  const canRead = useCanReadCredit();
  const canWrite = useCanWrite();
  const credit = useCredit(contractId, canRead);
  const installments = useInstallments(contractId, canRead && contractStatus !== "DRAFT");

  if (!canRead) {
    return null;
  }
  if (credit.isPending) {
    return <LoadingSpinner label="Memuat kredit nasabah…" />;
  }
  if (credit.isError) {
    return <ErrorAlert error={credit.error} title="Kredit tidak dapat dimuat" onRetry={() => void credit.refetch()} />;
  }

  const applications = credit.data.applications;
  const canApply =
    canWrite && contractStatus === "ACTIVE" && compareMoney(credit.data.available_balance, "0.00") > 0;

  return (
    <div className="space-y-4">
      {canApply ? (
        <div className="flex justify-end">
          <ApplyCreditDialog contractId={contractId} availableBalance={credit.data.available_balance} />
        </div>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-lg border p-4">
          <div className="text-xs text-muted-foreground">Total kredit</div>
          <div className="text-lg font-semibold">
            <MoneyDisplay value={credit.data.total_credit} />
          </div>
        </div>
        <div className="rounded-lg border p-4">
          <div className="text-xs text-muted-foreground">Saldo tersedia</div>
          <div className="text-lg font-semibold">
            <MoneyDisplay value={credit.data.available_balance} />
          </div>
        </div>
      </div>

      <div className="rounded-lg border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Waktu</TableHead>
              <TableHead>Angsuran</TableHead>
              <TableHead className="text-right">Jumlah</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {applications.length === 0 ? (
              <TableRow>
                <TableCell colSpan={3} className="py-6 text-center text-sm text-muted-foreground">
                  Belum ada pemakaian kredit.
                </TableCell>
              </TableRow>
            ) : (
              applications.map((application) => (
                <TableRow key={application.application_id}>
                  <TableCell>{formatDateTime(application.applied_at)}</TableCell>
                  <TableCell>{periodLabel(installments.data, application.installment_id)}</TableCell>
                  <TableCell className="text-right">
                    <MoneyDisplay value={application.amount} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
