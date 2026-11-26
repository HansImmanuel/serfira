"use client";

import { useState } from "react";

import { AgingContractTable } from "@/components/reports/AgingContractTable";
import { AccessDenied } from "@/components/shared/AccessDenied";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAgingReport } from "@/hooks/useReports";
import { useCanReadReports } from "@/hooks/useSession";
import { EMPTY_VALUE, formatDate } from "@/lib/format";

/**
 * Aging report (FE §2.9). The as-of date is locked to today (ADR-013 A-6): it shows the date the API
 * computed for and is never sent, so a browser in another zone cannot ask for another day.
 */
export function AgingReportView() {
  const canRead = useCanReadReports();
  const [page, setPage] = useState(0);
  const report = useAgingReport(page, canRead);

  if (!canRead) {
    return (
      <div className="space-y-4">
        <h1 className="text-xl font-semibold tracking-tight">Laporan Aging</h1>
        <AccessDenied message="Laporan hanya tersedia untuk Admin Operasional, Finance dan Manajemen." />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Laporan Aging</h1>
          <p className="text-sm text-muted-foreground">
            Outstanding kontrak ACTIVE per bucket keterlambatan, dihitung per angsuran.
          </p>
        </div>
        <div className="space-y-1">
          <Label htmlFor="aging-as-of">Per tanggal</Label>
          <Input
            id="aging-as-of"
            className="w-40"
            readOnly
            aria-readonly="true"
            value={report.data === undefined ? EMPTY_VALUE : formatDate(report.data.as_of)}
          />
        </div>
      </div>

      <AgingContractTable query={report} page={page} onPageChange={setPage} showNplBadge={false} showSummary />
    </div>
  );
}
