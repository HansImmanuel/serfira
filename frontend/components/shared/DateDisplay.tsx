"use client";

import { formatDate } from "@/lib/format";

/** Business date in the Jakarta calendar (FE §3.2), never shifted by the browser's zone. */
export function DateDisplay({ value, className }: { value: string | null | undefined; className?: string }) {
  return <span className={className}>{formatDate(value)}</span>;
}
