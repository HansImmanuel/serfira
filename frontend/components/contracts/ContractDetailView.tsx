"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ArrowLeft, ExternalLink, Wallet } from "lucide-react";

import { ActivateContractButton } from "@/components/contracts/ActivateContractButton";
import { ContractStatusBadge } from "@/components/contracts/ContractStatusBadge";
import { CreditPanel } from "@/components/contracts/CreditPanel";
import { InstallmentTable } from "@/components/contracts/InstallmentTable";
import { ContractPaymentsPanel } from "@/components/payments/ContractPaymentsPanel";
import { DateDisplay } from "@/components/shared/DateDisplay";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useContract, useInstallments } from "@/hooks/useContract";
import { useCanReadCredit, useCanReadPayments, useCanReadStatement, useCanWrite } from "@/hooks/useSession";
import { rateFractionToPercent } from "@/lib/decimal";

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-0.5">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  );
}

/**
 * Contract detail (FE §2.4): header with the lifecycle status, the customer/asset/financing facts,
 * and the schedule, credit and ledger tabs. Every amount is rendered from the API; nothing is
 * recomputed except the two derived schedule columns `InstallmentTable` documents.
 */
export function ContractDetailView({ contractId }: { contractId: string }) {
  const contract = useContract(contractId);
  const installments = useInstallments(contractId, contract.data?.status !== "DRAFT");
  const canWrite = useCanWrite();
  const canReadCredit = useCanReadCredit();
  const canReadPayments = useCanReadPayments();
  const canReadStatement = useCanReadStatement();
  const searchParams = useSearchParams();

  if (contract.isPending) {
    return <LoadingSpinner label="Memuat kontrak…" />;
  }
  if (contract.isError) {
    return (
      <ErrorAlert
        error={contract.error}
        title="Kontrak tidak dapat dimuat"
        onRetry={() => void contract.refetch()}
      />
    );
  }

  const detail = contract.data;
  // The tab and the highlighted payment come from the URL (T39 decision 11); a tab this role cannot see
  // falls back to the schedule.
  const visibleTabs = new Set<string>(["schedule"]);
  if (canReadPayments) visibleTabs.add("payments");
  if (canReadCredit) visibleTabs.add("credit");
  if (canReadStatement) visibleTabs.add("ledger");
  const requestedTab = searchParams.get("tab");
  const initialTab = requestedTab !== null && visibleTabs.has(requestedTab) ? requestedTab : "schedule";
  const highlightId = searchParams.get("payment");

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <Button asChild variant="ghost" size="sm" className="-ml-2">
            <Link href="/contracts">
              <ArrowLeft className="size-4" aria-hidden="true" />
              Daftar kontrak
            </Link>
          </Button>
          <div className="flex items-center gap-3">
            <h1 className="font-mono text-xl font-semibold tracking-tight">{detail.contract_no}</h1>
            <ContractStatusBadge status={detail.status} />
          </div>
          <p className="text-sm text-muted-foreground">{detail.customer.full_name}</p>
        </div>
        <div className="flex gap-2">
          {canReadStatement ? (
            <Button asChild variant="outline" size="sm">
              <Link href={`/contracts/${detail.id}/statement`}>
                <ExternalLink className="size-4" aria-hidden="true" />
                Rekening koran
              </Link>
            </Button>
          ) : null}
          {detail.status === "ACTIVE" && canWrite ? (
            <Button asChild size="sm">
              <Link href={`/contracts/${detail.id}/payments/new`}>
                <Wallet className="size-4" aria-hidden="true" />
                Catat Pembayaran
              </Link>
            </Button>
          ) : null}
          {detail.status === "DRAFT" && canWrite ? (
            <ActivateContractButton contractId={detail.id} plannedStartDate={detail.planned_start_date} />
          ) : null}
        </div>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Nasabah</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-3 sm:grid-cols-2">
              <Field label="Nama">{detail.customer.full_name}</Field>
              <Field label="NIK">
                <span className="font-mono text-xs">{detail.customer.nik}</span>
              </Field>
              <Field label="Telepon">
                <span className="font-mono text-xs">{detail.customer.phone}</span>
              </Field>
              <Field label="Alamat">{detail.customer.address ?? "–"}</Field>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Aset</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-3 sm:grid-cols-2">
              <Field label="Tipe">{detail.asset.asset_type}</Field>
              <Field label="Merek / Model">
                {detail.asset.brand} {detail.asset.model}
              </Field>
              <Field label="No. seri">{detail.asset.serial_no ?? "–"}</Field>
              <Field label="No. plat">{detail.asset.plate_no ?? "–"}</Field>
            </dl>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Pembiayaan</CardTitle>
          </CardHeader>
          <CardContent>
            <dl className="grid gap-3 sm:grid-cols-2">
              <Field label="Harga aset">
                <MoneyDisplay value={detail.asset_price} />
              </Field>
              <Field label="Uang muka">
                <MoneyDisplay value={detail.down_payment} />
              </Field>
              <Field label="Plafon">
                <MoneyDisplay value={detail.principal} />
              </Field>
              <Field label="Outstanding">
                <MoneyDisplay value={detail.outstanding} />
              </Field>
              <Field label="Tenor">{detail.tenor_months} bulan</Field>
              <Field label="Bunga">
                {rateFractionToPercent(detail.interest_rate)}% / bulan · {detail.interest_scheme}
              </Field>
              <Field label="Tanggal rencana">
                <DateDisplay value={detail.planned_start_date} />
              </Field>
              <Field label="Tanggal efektif">
                <DateDisplay value={detail.start_date} />
              </Field>
            </dl>
          </CardContent>
        </Card>
      </div>

      <Tabs defaultValue={initialTab} className="gap-3">
        <TabsList>
          <TabsTrigger value="schedule">Jadwal</TabsTrigger>
          {canReadPayments ? <TabsTrigger value="payments">Pembayaran</TabsTrigger> : null}
          {canReadCredit ? <TabsTrigger value="credit">Kredit</TabsTrigger> : null}
          {canReadStatement ? <TabsTrigger value="ledger">Ledger</TabsTrigger> : null}
        </TabsList>

        <TabsContent value="schedule">
          <div className="rounded-lg border">
            {installments.isPending ? (
              <LoadingSpinner label="Memuat jadwal angsuran…" />
            ) : installments.isError ? (
              <div className="p-4">
                <ErrorAlert
                  error={installments.error}
                  title="Jadwal tidak dapat dimuat"
                  onRetry={() => void installments.refetch()}
                />
              </div>
            ) : (
              <InstallmentTable installments={installments.data} />
            )}
          </div>
        </TabsContent>

        {canReadPayments ? (
          <TabsContent value="payments">
            <ContractPaymentsPanel contractId={detail.id} installments={installments.data} highlightId={highlightId} />
          </TabsContent>
        ) : null}

        {canReadCredit ? (
          <TabsContent value="credit">
            <CreditPanel contractId={detail.id} contractStatus={detail.status} />
          </TabsContent>
        ) : null}

        {canReadStatement ? (
          <TabsContent value="ledger">
            <Card>
              <CardHeader>
                <CardTitle className="text-base">Rekening koran</CardTitle>
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <p className="text-muted-foreground">
                  Mutasi ledger kontrak ditampilkan apa adanya (debit/kredit literal, tanpa saldo berjalan).
                </p>
                <Button asChild variant="outline" size="sm">
                  <Link href={`/contracts/${detail.id}/statement`}>Buka rekening koran</Link>
                </Button>
              </CardContent>
            </Card>
          </TabsContent>
        ) : null}
      </Tabs>
    </div>
  );
}
