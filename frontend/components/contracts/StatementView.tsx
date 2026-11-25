"use client";

import Link from "next/link";
import { ArrowLeft, ChevronLeft, ChevronRight } from "lucide-react";
import { useState } from "react";

import { AccessDenied } from "@/components/shared/AccessDenied";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { NO_STATEMENT_FILTERS, useStatement, type StatementFilters } from "@/hooks/useStatement";
import { useCanReadStatement } from "@/hooks/useSession";
import { formatDate } from "@/lib/format";
import { LEDGER_REF_TYPES, type LedgerRefType } from "@/types/contract";

/** Radix Select items cannot carry an empty value, so "no ref-type filter" has its own sentinel. */
const ALL_REF_TYPES = "ALL";

function isInvertedRange(filters: StatementFilters): boolean {
  return filters.from !== null && filters.to !== null && filters.from > filters.to;
}

function Header({ contractId }: { contractId: string }) {
  return (
    <div className="space-y-1">
      <Button asChild variant="ghost" size="sm" className="-ml-2">
        <Link href={`/contracts/${contractId}`}>
          <ArrowLeft className="size-4" aria-hidden="true" />
          Kembali ke kontrak
        </Link>
      </Button>
      <h1 className="text-xl font-semibold tracking-tight">Rekening Koran</h1>
    </div>
  );
}

/**
 * Rekening koran (FE §2.7): the contract's posted journal lines, ledger-literal.
 *
 * <p>There is deliberately no running balance (ADR-013 A-8) and a reversal is its own flagged row,
 * never a mutated original. Ref-type and date-range filters are sent to the API; a range whose start
 * is after its end is caught here and the last valid filters stay applied. The ref column shows the
 * event type and a short `ref_id`: there is no payment/settlement page to link to yet (T39).
 */
export function StatementView({ contractId }: { contractId: string }) {
  const [page, setPage] = useState(0);
  const [inputs, setInputs] = useState<StatementFilters>(NO_STATEMENT_FILTERS);
  const [applied, setApplied] = useState<StatementFilters>(NO_STATEMENT_FILTERS);
  const canRead = useCanReadStatement();
  const statement = useStatement(contractId, page, applied, canRead);

  const rows = statement.data?.content ?? [];
  const totalPages = statement.data?.total_pages ?? 0;
  const rangeInvalid = isInvertedRange(inputs);

  function updateFilters(change: Partial<StatementFilters>) {
    const next = { ...inputs, ...change };
    setInputs(next);
    if (!isInvertedRange(next)) {
      setApplied(next);
      setPage(0);
    } else if (next.refType !== applied.refType) {
      // The dates wait for a valid range; the ref type is independent and must match what the select shows.
      setApplied({ ...applied, refType: next.refType });
      setPage(0);
    }
  }

  if (!canRead) {
    return (
      <div className="space-y-4">
        <Header contractId={contractId} />
        <AccessDenied
          message="Rekening koran hanya tersedia untuk Admin Operasional dan Finance. Peran Anda tidak memiliki akses ke mutasi ledger kontrak."
          backHref={`/contracts/${contractId}`}
          backLabel="Kembali ke kontrak"
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="space-y-1">
        <Header contractId={contractId} />
        <p className="text-sm text-muted-foreground">
          Mutasi ledger literal per akun. Baris pembalik (reversal) ditandai dan tidak menimpa baris aslinya.
        </p>
      </div>

      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="statement-from">Dari</Label>
          <Input
            id="statement-from"
            type="date"
            className="w-40"
            value={inputs.from ?? ""}
            onChange={(event) => updateFilters({ from: event.target.value || null })}
            aria-invalid={rangeInvalid}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="statement-to">Sampai</Label>
          <Input
            id="statement-to"
            type="date"
            className="w-40"
            value={inputs.to ?? ""}
            onChange={(event) => updateFilters({ to: event.target.value || null })}
            aria-invalid={rangeInvalid}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="statement-ref-type">Ref type</Label>
          <Select
            value={inputs.refType ?? ALL_REF_TYPES}
            onValueChange={(value) =>
              updateFilters({ refType: value === ALL_REF_TYPES ? null : (value as LedgerRefType) })
            }
          >
            <SelectTrigger id="statement-ref-type" className="w-52">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_REF_TYPES}>Semua</SelectItem>
              {LEDGER_REF_TYPES.map((refType) => (
                <SelectItem key={refType} value={refType}>
                  {refType}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setInputs(NO_STATEMENT_FILTERS);
            setApplied(NO_STATEMENT_FILTERS);
            setPage(0);
          }}
        >
          Reset filter
        </Button>
      </div>
      {rangeInvalid ? (
        <p className="text-xs text-destructive" role="alert">
          Tanggal awal tidak boleh setelah tanggal akhir.
        </p>
      ) : null}

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
                      <div className="font-mono text-[11px] text-muted-foreground" title={row.ref_id}>
                        {row.ref_id.slice(0, 8)}
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
