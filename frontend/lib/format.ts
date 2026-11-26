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

const DECIMAL_PATTERN = /^-?\d+(?:\.\d+)?$/;

interface DecimalValue {
  units: bigint;
  scale: number;
}

/**
 * Parses an API decimal string (`"20000000.00"`) without going through a binary float.
 *
 * <p>Money arrives as an exact decimal string (ADR-024), so `Number(...)` would lose precision for
 * values above 2^53 and reintroduce the rounding the backend deliberately avoids.
 */
function parseDecimal(value: string): DecimalValue | null {
  const text = value.trim();
  if (!DECIMAL_PATTERN.test(text)) {
    return null;
  }
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = text.replace("-", "").split(".");
  const units = BigInt(`${whole}${fraction}`) * (negative ? -1n : 1n);
  return { units, scale: fraction.length };
}

/** Rounds half away from zero to whole rupiah, matching `Intl`'s default rounding. */
function roundToWholeRupiah({ units, scale }: DecimalValue): bigint {
  if (scale === 0) {
    return units;
  }
  const divisor = 10n ** BigInt(scale);
  const negative = units < 0n;
  const absolute = negative ? -units : units;
  const quotient = absolute / divisor;
  const remainder = absolute % divisor;
  const rounded = remainder * 2n >= divisor ? quotient + 1n : quotient;
  return negative ? -rounded : rounded;
}

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

/**
 * Rupiah at scale 0 (FE §3.1); a missing amount is the documented dash (DRAFT outstanding).
 *
 * <p>The value is an exact decimal string from the API. It is parsed with `BigInt` and formatted
 * from an integer, so no amount is ever routed through a binary float (review finding: display lost
 * precision on large values).
 */
export function formatMoney(value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return EMPTY_VALUE;
  }
  const parsed = parseDecimal(value);
  if (parsed === null) {
    return EMPTY_VALUE;
  }
  return MONEY_FORMAT.format(roundToWholeRupiah(parsed));
}

const RATIO_SCALE = 4;

const GROUPED_INTEGER_FORMAT = new Intl.NumberFormat("id-ID", { maximumFractionDigits: 0 });

/**
 * A report ratio (ADR-026 D7: a scale-4 fraction string, `"0.0150"`) as an id-ID percent with two
 * decimals: `"1,50%"`. A `null` ratio has a zero denominator and renders as the empty dash.
 *
 * <p>Exact: at scale 4 the fraction's units are the percent at scale 2, so the value is only shifted,
 * never divided or routed through a binary float (FE §3.1). A negative value or one finer than scale 4
 * is not a ratio the API returns and also renders as the dash.
 */
export function formatRatioPercent(value: string | null | undefined): string {
  if (value === null || value === undefined) {
    return EMPTY_VALUE;
  }
  const parsed = parseDecimal(value);
  if (parsed === null || parsed.units < 0n || parsed.scale > RATIO_SCALE) {
    return EMPTY_VALUE;
  }
  const percentHundredths = parsed.units * 10n ** BigInt(RATIO_SCALE - parsed.scale);
  const whole = GROUPED_INTEGER_FORMAT.format(percentHundredths / 100n);
  const fraction = String(percentHundredths % 100n).padStart(2, "0");
  return `${whole},${fraction}%`;
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
