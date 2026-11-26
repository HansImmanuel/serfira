"use client";

import Link from "next/link";
import type { UseQueryResult } from "@tanstack/react-query";
import { ChevronLeft, ChevronRight } from "lucide-react";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { AgingBuckets, AgingReport, ContractAgingRow } from "@/types/report";

const COLUMN_COUNT = 8;

const ZERO_AMOUNT = /^0+(\.0+)?$/;

/**
 * The NPL test of ADR-026 D4: the contract has an amount more than 90 days past due. It is the one value
 * the browser derives, and it needs no arithmetic: a bucket amount is never negative (ADR-013 A-7), so
 * "greater than zero" is "not a zero decimal string".
 */
export function isNplRow(row: ContractAgingRow): boolean {
  return !ZERO_AMOUNT.test(row.buckets.dpd_over_90);
}

function BucketCells({ buckets }: { buckets: AgingBuckets }) {
  return (
    <>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.total_outstanding} />
      </TableCell>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.current} />
      </TableCell>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.dpd_1_30} />
      </TableCell>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.dpd_31_60} />
      </TableCell>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.dpd_61_90} />
      </TableCell>
      <TableCell className="text-right">
        <MoneyDisplay value={buckets.dpd_over_90} />
      </TableCell>
    </>
  );
}

/**
 * Per-contract aging rows (FE §2.9), shared by the aging and outstanding pages (FE §2.10 reuses these
 * rows; ADR-026 D4 identity). Paging is the API's. Every amount is shown as returned; with `showSummary`
 * the first row is the API's portfolio buckets, never a sum of this page.
 */
export function AgingContractTable({
  query,
  page,
  onPageChange,
  showNplBadge,
  showSummary = false,
}: {
  query: UseQueryResult<AgingReport>;
  page: number;
  onPageChange: (page: number) => void;
  showNplBadge: boolean;
  showSummary?: boolean;
}) {
  const report = query.data;
  // Placeholder data is the previous page: keep its portfolio and page count, never its rows.
  const loadingRows = query.isPending || query.isPlaceholderData;
  const rows = loadingRows ? [] : (report?.contracts.content ?? []);
  const totalPages = report?.contracts.total_pages ?? 0;

  return (
    <div className="space-y-3">
      {/* A failed page shows the error instead of the table (no rows from another page), and the pager
          below stays so the user can retry or go back to a page that loaded. */}
      {query.isError ? (
        <ErrorAlert error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>No. Kontrak</TableHead>
                <TableHead>Nasabah</TableHead>
                <TableHead className="text-right">Outstanding</TableHead>
                <TableHead className="text-right">Current</TableHead>
                <TableHead className="text-right">1–30</TableHead>
                <TableHead className="text-right">31–60</TableHead>
                <TableHead className="text-right">61–90</TableHead>
                <TableHead className="text-right">&gt;90</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {showSummary && report !== undefined ? (
                <TableRow aria-label="Total portofolio" className="bg-muted/50 font-medium">
                  <TableCell colSpan={2}>Total portofolio</TableCell>
                  <BucketCells buckets={report.portfolio} />
                </TableRow>
              ) : null}
              {loadingRows
                ? Array.from({ length: 5 }, (_, index) => (
                    <TableRow key={`skeleton-${index}`} data-testid="aging-row-skeleton">
                      <TableCell colSpan={COLUMN_COUNT}>
                        <Skeleton className="h-5 w-full" />
                      </TableCell>
                    </TableRow>
                  ))
                : rows.map((row) => (
                    <TableRow key={row.contract_id}>
                      <TableCell className="font-mono text-xs">
                        <span className="flex items-center gap-2">
                          <Link href={`/contracts/${row.contract_id}`} className="underline-offset-4 hover:underline">
                            {row.contract_no}
                          </Link>
                          {showNplBadge && isNplRow(row) ? <Badge variant="destructive">NPL</Badge> : null}
                        </span>
                      </TableCell>
                      <TableCell>{row.customer_name}</TableCell>
                      <BucketCells buckets={row.buckets} />
                    </TableRow>
                  ))}
              {!loadingRows && rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={COLUMN_COUNT} className="py-8 text-center text-sm text-muted-foreground">
                    Tidak ada kontrak ACTIVE dengan outstanding.
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </div>
      )}

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>
          {report === undefined
            ? "–"
            : `${report.contracts.total_elements} kontrak · halaman ${page + 1} dari ${Math.max(totalPages, 1)}`}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => onPageChange(Math.max(page - 1, 0))}>
            <ChevronLeft className="size-4" aria-hidden="true" />
            Sebelumnya
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages}
            onClick={() => onPageChange(page + 1)}
          >
            Berikutnya
            <ChevronRight className="size-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
    </div>
  );
}
