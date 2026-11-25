"use client";

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
import { useCredit } from "@/hooks/useContract";
import { useCanReadCredit } from "@/hooks/useSession";
import { formatDateTime } from "@/lib/format";

/**
 * Customer credit from excess payments (FE §2.4 Kredit tab).
 *
 * <p>Read-only here: applying credit is a financial action with its own eligibility rules and lands
 * with T33. The balance and the application history come straight from `GET /contracts/{id}/credit`
 * so the audit trail is never reconstructed in the browser.
 *
 * <p>The endpoint is limited to `ADMIN_OPERASIONAL`/`FINANCE`, so the panel is not rendered and the
 * query is not issued for `MANAJEMEN` (defence in depth: the tab is also hidden by the parent).
 */
export function CreditPanel({ contractId }: { contractId: string }) {
  const canRead = useCanReadCredit();
  const credit = useCredit(contractId, canRead);

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

  return (
    <div className="space-y-4">
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
                  <TableCell className="font-mono text-xs">{application.installment_id}</TableCell>
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
