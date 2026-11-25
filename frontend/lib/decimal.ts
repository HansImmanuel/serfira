/**
 * Exact decimal helpers for money and rates (TS §2.0).
 *
 * <p>Money is `BigDecimal(NUMERIC(19,2))` on the server and the rate column is `NUMERIC(7,4)`.
 * JavaScript numbers cannot represent every such value exactly, so anything that is validated,
 * re-submitted or summed runs through these string/BigInt helpers instead of `parseFloat`. Display
 * formatting may use `Intl`, but a value on its way back to the API is never a binary float.
 */

interface Decimal {
  units: bigint;
  scale: number;
}

const DECIMAL_PATTERN = /^-?\d+(?:\.\d+)?$/;

/**
 * `NUMERIC(19,2)` in scale-2 units: 17 integer digits, so the largest accepted amount is
 * `99999999999999999.99` (`10^19 - 1` units). Mirrors `DecimalBounds.requireMoneyDomain`, which the
 * backend applies before any rescaling; a larger value would answer 400 `VALIDATION_ERROR`.
 */
const MAX_MONEY_UNITS = 10n ** 19n;

function parse(value: string | number): Decimal | null {
  const text = typeof value === "number" ? String(value) : value.trim();
  if (!DECIMAL_PATTERN.test(text)) {
    return null;
  }
  const negative = text.startsWith("-");
  const [whole, fraction = ""] = text.replace("-", "").split(".");
  const units = BigInt(`${whole}${fraction}`) * (negative ? -1n : 1n);
  return { units, scale: fraction.length };
}

/** Re-expresses a decimal at `targetScale`, returning null when that would lose a digit. */
function rescale(value: Decimal, targetScale: number): bigint | null {
  if (value.scale > targetScale) {
    const factor = 10n ** BigInt(value.scale - targetScale);
    if (value.units % factor !== 0n) {
      return null;
    }
    return value.units / factor;
  }
  return value.units * 10n ** BigInt(targetScale - value.scale);
}

function formatUnits(units: bigint, scale: number): string {
  const negative = units < 0n;
  const digits = (negative ? -units : units).toString().padStart(scale + 1, "0");
  const whole = digits.slice(0, digits.length - scale);
  const fraction = digits.slice(digits.length - scale);
  return `${negative ? "-" : ""}${whole}.${fraction}`;
}

function moneyUnits(value: string | number, label: string): bigint {
  const units = rescale(decimalOf(value, label), 2);
  if (units === null) {
    throw new Error(`${label} has more than 2 decimal places: ${String(value)}`);
  }
  return units;
}

function decimalOf(value: string | number, label: string): Decimal {
  const parsed = parse(value);
  if (parsed === null) {
    throw new Error(`${label} is not a decimal number: ${String(value)}`);
  }
  return parsed;
}

/**
 * Validates operator input for a money field and returns the API's scale-2 form.
 *
 * @returns the amount at scale 2, or null when the input is not a plain decimal, is finer than the
 *          2 decimal places the server accepts (the input is never silently rounded), or exceeds
 *          the `NUMERIC(19,2)` magnitude (17 integer digits)
 */
export function normalizeMoney(value: string): string | null {
  const parsed = parse(value);
  if (parsed === null) {
    return null;
  }
  const units = rescale(parsed, 2);
  if (units === null) {
    return null;
  }
  const magnitude = units < 0n ? -units : units;
  if (magnitude >= MAX_MONEY_UNITS) {
    return null;
  }
  return formatUnits(units, 2);
}

/** Adds two scale-2 amounts exactly (used for recognised totals, never for allocation). */
export function addMoney(left: string | number, right: string | number): string {
  return formatUnits(moneyUnits(left, "left amount") + moneyUnits(right, "right amount"), 2);
}

/** Subtracts two scale-2 amounts exactly (the create form's principal preview). */
export function subtractMoney(minuend: string | number, subtrahend: string | number): string {
  return formatUnits(moneyUnits(minuend, "minuend") - moneyUnits(subtrahend, "subtrahend"), 2);
}

/** Orders two amounts exactly; returns -1, 0 or 1 (never a float subtraction). */
export function compareMoney(left: string | number, right: string | number): number {
  const leftDecimal = decimalOf(left, "left amount");
  const rightDecimal = decimalOf(right, "right amount");
  const scale = Math.max(leftDecimal.scale, rightDecimal.scale);
  const leftUnits = rescale(leftDecimal, scale);
  const rightUnits = rescale(rightDecimal, scale);
  if (leftUnits === null || rightUnits === null) {
    throw new Error(`cannot compare ${String(left)} with ${String(right)}`);
  }
  if (leftUnits === rightUnits) {
    return 0;
  }
  return leftUnits > rightUnits ? 1 : -1;
}

/**
 * Converts a monthly percentage as the operator types it into the API's rate fraction
 * (`1.5` → `0.0150`), because the wire stores fractions and a float division by 100 would be
 * inexact (TS §2.0).
 *
 * @returns the fraction at scale 4, or null for a negative value, a non-number, or a percentage
 *          finer than 2 decimal places (which the rate column cannot hold)
 */
export function percentToRateFraction(percent: string): string | null {
  const parsed = parse(percent);
  if (parsed === null || parsed.units < 0n || parsed.scale > 2) {
    return null;
  }
  const rateUnits = parsed.units * 10n ** BigInt(2 - parsed.scale);
  return formatUnits(rateUnits, 4);
}

/**
 * The inverse, for display only: `0.0150` → `1.5`. Shifting by two decimal places is exact string
 * arithmetic, so a rate is never shown through a binary float either.
 */
export function rateFractionToPercent(fraction: string | number): string {
  const parsed = decimalOf(fraction, "rate");
  const percent =
    parsed.scale >= 2
      ? formatUnits(parsed.units, parsed.scale - 2)
      : formatUnits(parsed.units * 10n ** BigInt(2 - parsed.scale), 0);
  return trimTrailingZeros(percent);
}

function trimTrailingZeros(value: string): string {
  const [whole, fraction = ""] = value.split(".");
  const trimmed = fraction.replace(/0+$/, "");
  return trimmed.length === 0 ? whole : `${whole}.${trimmed}`;
}
