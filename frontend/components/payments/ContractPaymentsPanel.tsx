"use client";

import { PaymentHistoryTable } from "@/components/payments/PaymentHistoryTable";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { useContractPayments } from "@/hooks/usePayment";
import { useCanReadPayments } from "@/hooks/useSession";
import type { Installment } from "@/types/contract";

/**
 * Pembayaran tab body: loads the payment history and hands it to `PaymentHistoryTable`. Not rendered and
 * not queried for `MANAJEMEN` (the endpoint answers 403); the parent also hides the tab.
 */
export function ContractPaymentsPanel({
  contractId,
  installments,
  highlightId,
}: {
  contractId: string;
  installments: Installment[] | undefined;
  highlightId: string | null;
}) {
  const canRead = useCanReadPayments();
  const payments = useContractPayments(contractId, canRead);

  if (!canRead) {
    return null;
  }
  if (payments.isPending) {
    return <LoadingSpinner label="Memuat riwayat pembayaran…" />;
  }
  if (payments.data === undefined) {
    return (
      <ErrorAlert
        error={payments.error}
        title="Riwayat pembayaran tidak dapat dimuat"
        onRetry={() => void payments.refetch()}
      />
    );
  }

  return (
    <div className="space-y-3">
      {payments.isError ? (
        <ErrorAlert
          error={payments.error}
          title="Riwayat pembayaran mungkin tidak terbaru"
          onRetry={() => void payments.refetch()}
        />
      ) : null}
      <div className="rounded-lg border">
        <PaymentHistoryTable payments={payments.data} installments={installments} highlightId={highlightId} />
      </div>
    </div>
  );
}
