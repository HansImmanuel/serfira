"use client";

import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { useState } from "react";

import { ContractStatusBadge } from "@/components/contracts/ContractStatusBadge";
import { PaymentResult } from "@/components/payments/PaymentResult";
import { RecordPaymentForm } from "@/components/payments/RecordPaymentForm";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { useContract } from "@/hooks/useContract";
import type { Payment } from "@/types/payment";

/**
 * Payment screen (FE §2.6) for one contract. Only an ACTIVE contract accepts money (the API answers
 * 409 `CONTRACT_STATE_INVALID` otherwise), so any other status gets an explanation and no form.
 *
 * <p>`formInstance` is the form's React key: bumping it remounts `RecordPaymentForm`, which is the only
 * way a new `Idempotency-Key` is minted — after a posted payment ("Catat pembayaran lain") or when the
 * operator deliberately restarts a form whose key is spent.
 */
export function RecordPaymentView({ contractId }: { contractId: string }) {
  const contract = useContract(contractId);
  const [formInstance, setFormInstance] = useState(0);
  const [posted, setPosted] = useState<Payment | null>(null);

  function startNewForm() {
    setPosted(null);
    setFormInstance((current) => current + 1);
  }

  if (contract.isPending) {
    return <LoadingSpinner label="Memuat kontrak…" />;
  }
  if (contract.isError) {
    return (
      <ErrorAlert error={contract.error} title="Kontrak tidak dapat dimuat" onRetry={() => void contract.refetch()} />
    );
  }

  const detail = contract.data;

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link href={`/contracts/${contractId}`}>
            <ArrowLeft className="size-4" aria-hidden="true" />
            Kembali ke kontrak
          </Link>
        </Button>
        <h1 className="text-xl font-semibold tracking-tight">Catat Pembayaran</h1>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <span className="font-mono">{detail.contract_no}</span>
          <ContractStatusBadge status={detail.status} />
          <span>{detail.customer.full_name}</span>
          <span>
            · Outstanding <MoneyDisplay value={detail.outstanding} />
          </span>
        </div>
      </div>

      {detail.status !== "ACTIVE" ? (
        <Alert>
          <AlertDescription>Pembayaran hanya dapat dicatat untuk kontrak berstatus ACTIVE.</AlertDescription>
        </Alert>
      ) : posted !== null ? (
        <PaymentResult contractId={contractId} payment={posted} onRecordAnother={startNewForm} />
      ) : (
        <RecordPaymentForm
          key={formInstance}
          contractId={contractId}
          onPosted={setPosted}
          onRestart={startNewForm}
        />
      )}
    </div>
  );
}
