"use client";

import Link from "next/link";
import { ArrowLeft, ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";

import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useStatement } from "@/hooks/useStatement";
import { formatDate } from "@/lib/format";

/**
 * Rekening koran (FE §2.7): the contract's posted journal lines, ledger-literal.
 *
 * <p>There is deliberately no running balance (ADR-013 A-8) and a reversal is its own flagged row,
 * never a mutated original. Date-range and ref-type filters plus the payment/settlement links land
 * with T33; this page keeps the Ledger tab's link alive in the meantime.
 */
export function StatementView({ contractId }: { contractId: string }) {
  const [page, setPage] = useState(0);
  const statement = useStatement(contractId, page);

  const rows = statement.data?.content ?? [];
  const totalPages = statement.data?.total_pages ?? 0;

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <Button asChild variant="ghost" size="sm" className="-ml-2">
          <Link href={`/contracts/${contractId}`}>
            <ArrowLeft className="size-4" aria-hidden="true" />
            Kembali ke kontrak
          </Link>
        </Button>
        <h1 className="text-xl font-semibold tracking-tight">Rekening Koran</h1>
        <p className="text-sm text-muted-foreground">
          Mutasi ledger literal per akun. Baris pembalik (reversal) ditandai dan tidak menimpa baris aslinya.
        </p>
      </div>

      {statement.isError ? (
        <ErrorAlert
          error={statement.error}
          title="Rekening koran tidak dapat dimuat"
          onRetry={() => void statement.refetch()}
        />
      ) : statement.isPending ? (
        <LoadingSpinner label="Memuat rekening koran…" />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Tanggal</TableHead>
                <TableHead>Akun</TableHead>
                <TableHead>Keterangan</TableHead>
                <TableHead>Ref</TableHead>
                <TableHead className="text-right">Debit</TableHead>
                <TableHead className="text-right">Kredit</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="py-8 text-center text-sm text-muted-foreground">
                    Belum ada mutasi ledger untuk kontrak ini.
                  </TableCell>
                </TableRow>
              ) : (
                rows.map((row, index) => (
                  <TableRow key={`${row.ref_id}-${row.account_code}-${index}`}>
                    <TableCell>{formatDate(row.entry_date)}</TableCell>
                    <TableCell>
                      <div className="font-mono text-xs">{row.account_code}</div>
                      <div className="text-xs text-muted-foreground">{row.account_name}</div>
                    </TableCell>
                    <TableCell className="max-w-72 truncate text-sm" title={row.description ?? undefined}>
                      {row.description ?? "–"}
                    </TableCell>
                    <TableCell>
                      <div className="flex items-center gap-1.5">
                        <span className="font-mono text-[11px] text-muted-foreground">{row.ref_type}</span>
                        {row.is_reversal ? (
                          <Badge variant="outline" className="border-amber-200 bg-amber-50 text-amber-700">
                            REVERSAL
                          </Badge>
                        ) : null}
                      </div>
                    </TableCell>
                    <TableCell className="text-right">
                      <MoneyDisplay value={row.debit} />
                    </TableCell>
                    <TableCell className="text-right">
                      <MoneyDisplay value={row.credit} />
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </div>
      )}

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>
          {statement.data === undefined
            ? "–"
            : `${statement.data.total_elements} baris · halaman ${statement.data.page + 1} dari ${Math.max(totalPages, 1)}`}
        </span>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((current) => current - 1)}>
            <ChevronLeft className="size-4" aria-hidden="true" />
            Sebelumnya
          </Button>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage((current) => current + 1)}
          >
            Berikutnya
            <ChevronRight className="size-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
    </div>
  );
}
