"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft, ChevronRight, Plus, Search } from "lucide-react";
import { useState } from "react";

import { ContractStatusBadge } from "@/components/contracts/ContractStatusBadge";
import { DateDisplay } from "@/components/shared/DateDisplay";
import { ErrorAlert } from "@/components/shared/ErrorAlert";
import { MoneyDisplay } from "@/components/shared/MoneyDisplay";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useContracts } from "@/hooks/useContracts";
import { useCanWrite } from "@/hooks/useSession";
import type { ContractStatus } from "@/types/contract";

const STATUS_OPTIONS: Array<{ value: ContractStatus | "ALL"; label: string }> = [
  { value: "ALL", label: "Semua status" },
  { value: "DRAFT", label: "DRAFT" },
  { value: "ACTIVE", label: "ACTIVE" },
  { value: "CLOSED", label: "CLOSED" },
  { value: "TERMINATED", label: "TERMINATED" },
];

/**
 * Contract book (FE §2.2): server-side status filter, free-text search, pagination and newest-first
 * order. Every value shown comes from the list endpoint; nothing is recomputed here.
 */
export function ContractListView() {
  const router = useRouter();
  const canWrite = useCanWrite();
  const [status, setStatus] = useState<ContractStatus | "ALL">("ALL");
  const [searchInput, setSearchInput] = useState("");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);

  const contracts = useContracts({ status, query, page });

  function applySearch(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPage(0);
    setQuery(searchInput);
  }

  function changeStatus(value: string) {
    setStatus(value as ContractStatus | "ALL");
    setPage(0);
  }

  const totalPages = contracts.data?.total_pages ?? 0;
  const rows = contracts.data?.content ?? [];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">Kontrak</h1>
          <p className="text-sm text-muted-foreground">Buku kontrak pembiayaan.</p>
        </div>
        {canWrite ? (
          <Button asChild>
            <Link href="/contracts/new">
              <Plus className="size-4" aria-hidden="true" />
              Buat Kontrak
            </Link>
          </Button>
        ) : null}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Select value={status} onValueChange={changeStatus}>
          <SelectTrigger className="w-44" aria-label="Filter status">
            <SelectValue placeholder="Status" />
          </SelectTrigger>
          <SelectContent>
            {STATUS_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <form className="flex flex-1 items-center gap-2" onSubmit={applySearch}>
          <Input
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            placeholder="Cari nomor kontrak atau nama nasabah"
            aria-label="Cari kontrak"
            className="max-w-sm"
          />
          <Button type="submit" variant="outline" size="sm">
            <Search className="size-4" aria-hidden="true" />
            Cari
          </Button>
        </form>
      </div>

      {contracts.isError ? (
        <ErrorAlert error={contracts.error} onRetry={() => void contracts.refetch()} />
      ) : (
        <div className="rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>No. Kontrak</TableHead>
                <TableHead>Nasabah</TableHead>
                <TableHead>Aset</TableHead>
                <TableHead className="text-right">Pokok</TableHead>
                <TableHead>Tenor</TableHead>
                <TableHead>Status</TableHead>
                <TableHead className="text-right">Outstanding</TableHead>
                <TableHead>Dibuat</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {contracts.isPending
                ? Array.from({ length: 5 }, (_, index) => (
                    <TableRow key={`skeleton-${index}`}>
                      <TableCell colSpan={8}>
                        <Skeleton className="h-5 w-full" />
                      </TableCell>
                    </TableRow>
                  ))
                : rows.map((contract) => (
                    <TableRow
                      key={contract.id}
                      className="cursor-pointer"
                      onClick={() => router.push(`/contracts/${contract.id}`)}
                    >
                      <TableCell className="font-mono text-xs">
                        <Link
                          href={`/contracts/${contract.id}`}
                          className="underline-offset-4 hover:underline"
                          onClick={(event) => event.stopPropagation()}
                        >
                          {contract.contract_no}
                        </Link>
                      </TableCell>
                      <TableCell>{contract.customer.full_name}</TableCell>
                      <TableCell>
                        {contract.asset.brand} {contract.asset.model}
                      </TableCell>
                      <TableCell className="text-right">
                        <MoneyDisplay value={contract.principal} />
                      </TableCell>
                      <TableCell>{contract.tenor_months} bulan</TableCell>
                      <TableCell>
                        <ContractStatusBadge status={contract.status} />
                      </TableCell>
                      <TableCell className="text-right">
                        <MoneyDisplay value={contract.outstanding} />
                      </TableCell>
                      <TableCell>
                        <DateDisplay value={contract.created_at} />
                      </TableCell>
                    </TableRow>
                  ))}
              {!contracts.isPending && rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={8} className="py-8 text-center text-sm text-muted-foreground">
                    Belum ada kontrak yang cocok dengan filter ini.
                  </TableCell>
                </TableRow>
              ) : null}
            </TableBody>
          </Table>
        </div>
      )}

      <div className="flex items-center justify-between text-sm text-muted-foreground">
        <span>
          {contracts.data === undefined
            ? "–"
            : `${contracts.data.total_elements} kontrak · halaman ${contracts.data.page + 1} dari ${Math.max(totalPages, 1)}`}
        </span>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((current) => Math.max(current - 1, 0))}
          >
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
