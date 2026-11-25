"use client";

import { formatMoney } from "@/lib/format";
import type { Money } from "@/types/contract";

/**
 * Rupiah display (FE §3.1). A null amount is the documented dash — DRAFT contracts have no
 * receivable yet, so the column must not look like Rp 0.
 */
export function MoneyDisplay({ value, className }: { value: Money | null | undefined; className?: string }) {
  return <span className={className}>{formatMoney(value)}</span>;
}
