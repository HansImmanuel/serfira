import type { Installment } from "@/types/contract";

/**
 * Human label for an installment reference in allocation and credit-application rows.
 *
 * <p>`null` is the EXCESS line (held as customer credit). An id that the loaded schedule does not
 * contain — or a schedule that has not loaded yet — falls back to the id's first 8 characters rather
 * than inventing a period number.
 */
export function periodLabel(installments: Installment[] | undefined, installmentId: string | null): string {
  if (installmentId === null) {
    return "Kredit nasabah";
  }
  const match = installments?.find((installment) => installment.id === installmentId);
  return match === undefined ? installmentId.slice(0, 8) : `Angsuran ke-${match.period_no}`;
}
