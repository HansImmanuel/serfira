"use client";

import { useState } from "react";

import { AgingContractTable } from "@/components/reports/AgingContractTable";
import { AccessDenied } from "@/components/shared/AccessDenied";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAgingReport, useOutstandingReport } from "@/hooks/useReports";
import { useCanReadReports } from "@/hooks/useSession";
import { formatDate, formatMoney, formatRatioPercent } from "@/lib/format";
import type { OutstandingReport } from "@/types/report";

const METRIC_COUNT = 5;

function MetricCard({ title, value, lines }: { title: string; value: string; lines: string[] }) {
  return (
    <Card role="group" aria-label={title} className="gap-2">
      <CardHeader>
        <CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle>
      </CardHeader>
      <CardContent className="space-y-1">
        <div className="text-2xl font-semibold tabular-nums">{value}</div>
        {lines.map((line) => (
          <div key={line} className="text-xs text-muted-foreground">
            {line}
          </div>
        ))}
      </CardContent>
    </Card>
  );
}

function periodLabel(from: string, to: string): string {
  return `${formatDate(from)} – ${formatDate(to)}`;
}

/** The five ADR-026 cards. Every value is the API's, formatted only (FE §2.10). */
function MetricCards({ report }: { report: OutstandingReport }) {
  const { npl, collection, settlement_cash: settlementCash } = report;
  return (
    <>
      <MetricCard
        title="Total Outstanding"
        value={formatMoney(report.total_outstanding)}
        lines={[`${report.active_contract_count} kontrak aktif`]}
      />
      <MetricCard
        title="Total AR"
        value={formatMoney(report.accounts_receivable)}
        lines={["Jatuh tempo s.d. hari ini, termasuk masa tenggang"]}
      />
      <MetricCard
        title="NPL"
        value={formatRatioPercent(npl.ratio)}
        lines={[`${formatMoney(npl.outstanding)} · ${npl.contract_count} kontrak`]}
      />
      <MetricCard
        title="Collection Rate MTD"
        value={formatRatioPercent(collection.rate)}
        lines={[
          `${formatMoney(collection.collected)} / ${formatMoney(collection.due)}`,
          periodLabel(collection.period_from, collection.period_to),
        ]}
      />
      <MetricCard
        title="Settlement Cash MTD"
        value={formatMoney(settlementCash.amount)}
        lines={[periodLabel(settlementCash.period_from, settlementCash.period_to)]}
      />
    </>
  );
}

/**
 * Outstanding portfolio (FE §2.10, ADR-026): the T36 metric cards above the aging rows, with an NPL badge on
 * contracts that have an amount more than 90 days past due. The browser computes no metric. Cards and table
 * are separate requests that load and fail independently.
 */
export function OutstandingReportView() {
  const canRead = useCanReadReports();
  const [page, setPage] = useState(0);
  const metrics = useOutstandingReport(canRead);
  const aging = useAgingReport(page, canRead);

  if (!canRead) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Outstanding Portfolio</h1>
        <AccessDenied message="Laporan hanya tersedia untuk Admin Operasional, Finance dan Manajemen." />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">Outstanding Portfolio</h1>
        <p className="text-sm text-muted-foreground">
          {metrics.data === undefined ? "Metrik portofolio hari ini." : `Per ${formatDate(metrics.data.as_of)}.`}
        </p>
      </div>

      {metrics.isError ? (
        <ErrorAlert error={metrics.error} onRetry={() => void metrics.refetch()} />
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
          {metrics.data === undefined ? (
            Array.from({ length: METRIC_COUNT }, (_, index) => (
              <Skeleton key={index} data-testid="metric-skeleton" className="h-28 w-full" />
            ))
          ) : (
            <MetricCards report={metrics.data} />
          )}
        </div>
      )}

      <section className="space-y-2" aria-label="Rincian per kontrak">
        <h2 className="text-base font-semibold">Rincian per kontrak</h2>
        <AgingContractTable query={aging} page={page} onPageChange={setPage} showNplBadge />
      </section>
    </div>
  );
}
