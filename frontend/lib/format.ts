/**
 * Display formatting for the two value families the API returns (FE §3.1/§3.2): money at scale 2
 * and business dates/timestamps in Asia/Jakarta. Formatting only — validation and re-submission use
 * `lib/decimal.ts`.
 */

export const EMPTY_VALUE = "–";

const MONEY_FORMAT = new Intl.NumberFormat("id-ID", {
  style: "currency",
  currency: "IDR",
  maximumFractionDigits: 0,
});

const BUSINESS_ZONE = "Asia/Jakarta";

const DATE_FORMAT = new Intl.DateTimeFormat("id-ID", {
  day: "2-digit",
  month: "short",
  year: "numeric",
  timeZone: BUSINESS_ZONE,
});

const DATE_TIME_FORMAT = new Intl.DateTimeFormat("id-ID", {
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: BUSINESS_ZONE,
});

const DATE_ONLY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

function toDate(value: string): Date | null {
  const dateOnly = DATE_ONLY_PATTERN.exec(value);
  if (dateOnly !== null) {
    // A date-only value is a business date, not an instant: anchor it at UTC midnight so a fixed
    // business zone renders the same calendar day regardless of the browser's own zone.
    return new Date(Date.UTC(Number(dateOnly[1]), Number(dateOnly[2]) - 1, Number(dateOnly[3])));
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** Rupiah at scale 0 (FE §3.1); a missing amount is the documented dash (DRAFT outstanding). */
export function formatMoney(value: number | string | null | undefined): string {
  if (value === null || value === undefined) {
    return EMPTY_VALUE;
  }
  const amount = typeof value === "number" ? value : Number(value);
  return Number.isFinite(amount) ? MONEY_FORMAT.format(amount) : EMPTY_VALUE;
}

/** `28 Feb 2026`, in the business zone, without a day shift for date-only values. */
export function formatDate(value: string | null | undefined): string {
  if (!value) {
    return EMPTY_VALUE;
  }
  const date = toDate(value);
  return date === null ? EMPTY_VALUE : DATE_FORMAT.format(date);
}

/** `19 Nov 2026, 15.30` — the Jakarta wall clock of a timestamp (audit columns, credit history). */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return EMPTY_VALUE;
  }
  const date = toDate(value);
  return date === null ? EMPTY_VALUE : DATE_TIME_FORMAT.format(date);
}
