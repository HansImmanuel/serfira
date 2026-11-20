"use client";

import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { ContractStatus, InstallmentStatus } from "@/types/contract";

const CONTRACT_STATUS_STYLES: Record<ContractStatus, string> = {
  DRAFT: "border-border bg-muted text-muted-foreground",
  ACTIVE: "border-emerald-200 bg-emerald-50 text-emerald-700",
  CLOSED: "border-sky-200 bg-sky-50 text-sky-700",
  TERMINATED: "border-zinc-300 bg-zinc-100 text-zinc-700",
};

const INSTALLMENT_STATUS_STYLES: Record<InstallmentStatus, string> = {
  PENDING: "border-border bg-muted text-muted-foreground",
  PARTIALLY_PAID: "border-amber-200 bg-amber-50 text-amber-700",
  PAID: "border-emerald-200 bg-emerald-50 text-emerald-700",
  OVERDUE: "border-red-200 bg-red-50 text-red-700",
  SETTLED: "border-sky-200 bg-sky-50 text-sky-700",
  WRITTEN_OFF: "border-zinc-300 bg-zinc-100 text-zinc-600 line-through",
};

/** Contract status badge; the label stays the domain term so it matches the API and the ledger. */
export function ContractStatusBadge({ status }: { status: ContractStatus }) {
  return (
    <Badge variant="outline" className={cn("font-mono text-[11px]", CONTRACT_STATUS_STYLES[status])}>
      {status}
    </Badge>
  );
}

/** Installment status badge (FE §2.5 colours). */
export function InstallmentStatusBadge({ status }: { status: InstallmentStatus }) {
  return (
    <Badge variant="outline" className={cn("font-mono text-[11px]", INSTALLMENT_STATUS_STYLES[status])}>
      {status}
    </Badge>
  );
}
