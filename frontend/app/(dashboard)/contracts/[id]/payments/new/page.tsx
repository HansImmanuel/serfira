"use client";

import { useParams } from "next/navigation";

import { RecordPaymentView } from "@/components/payments/RecordPaymentView";
import { RequireWrite } from "@/components/shared/RequireWrite";

export default function RecordPaymentPage() {
  const params = useParams<{ id: string }>();
  return (
    <RequireWrite
      message="Peran Anda tidak dapat mencatat pembayaran."
      backHref={`/contracts/${params.id}`}
      backLabel="Kembali ke kontrak"
    >
      <RecordPaymentView contractId={params.id} />
    </RequireWrite>
  );
}
