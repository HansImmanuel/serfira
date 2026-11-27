import { addMoney, compareMoney, subtractMoney } from "@/lib/decimal";
import type { Money } from "@/types/contract";
import type { SettlementQuote } from "@/types/settlement";

export interface SettlementBreakdown {
  /** The future interest before the rebate, so the displayed lines sum to the gross (ADR-018 D6). */
  futureInterestGross: Money;
  /** `gross_amount − available_credit`; null exactly when the credit exceeds the gross. */
  cashDue: Money | null;
  creditExceedsGross: boolean;
}

/**
 * The settlement modal's only money arithmetic (T39 decision 5), exact through `lib/decimal`.
 *
 * <p>A quote always stores `credit_used = 0`, so its `cash_due` equals the gross; execution then consumes
 * all available credit (ADR-018 D10). The cash the customer actually pays is therefore the gross minus the
 * snapshotted credit, and a credit larger than the gross cannot be executed (the server answers 409
 * `CREDIT_EXCEEDS_SETTLEMENT`). The response carries no future-interest line, so it is derived from the
 * D6 identity: `gross = principal + billed + accrued + future − rebate + penalty + admin`.
 */
export function settlementBreakdown(quote: SettlementQuote): SettlementBreakdown {
  const charged = [
    quote.unpaid_billed_interest,
    quote.accrued_interest,
    quote.penalty_outstanding,
    quote.admin_fee,
  ].reduce((sum, amount) => addMoney(sum, amount), quote.outstanding_principal);
  const futureInterestGross = addMoney(subtractMoney(quote.gross_amount, charged), quote.rebate_amount);
  const creditExceedsGross = compareMoney(quote.available_credit, quote.gross_amount) > 0;
  return {
    futureInterestGross,
    cashDue: creditExceedsGross ? null : subtractMoney(quote.gross_amount, quote.available_credit),
    creditExceedsGross,
  };
}

const INSTANT = /^(.+T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})$/;

/** Milliseconds since the epoch of an ISO offset instant, keeping any sub-millisecond fraction. */
function instantMs(value: string): number {
  const match = INSTANT.exec(value);
  const wholeSeconds = match === null ? Number.NaN : Date.parse(`${match[1]}${match[3]}`);
  if (match === null || Number.isNaN(wholeSeconds)) {
    throw new Error(`not an ISO offset instant: ${value}`);
  }
  const fraction = match[2] ?? "";
  return wholeSeconds + (fraction.length === 0 ? 0 : Number(`0.${fraction}`) * 1000);
}

/**
 * How long the quote lives, measured between its two server instants only (T39 decision 6): the browser
 * clock is never compared with the server clock, so a skewed browser still counts the right lifetime.
 */
export function quoteLifetimeMs(quote: Pick<SettlementQuote, "quoted_at" | "valid_until">): number {
  return Math.round(instantMs(quote.valid_until) - instantMs(quote.quoted_at));
}

/** The lifetime left, measured by local elapsed time since the quote response arrived; never negative. */
export function remainingMs(lifetimeMs: number, receivedAtMs: number, nowMs: number): number {
  return Math.max(0, lifetimeMs - (nowMs - receivedAtMs));
}

/** `mm:ss`, seconds rounded up, so the display reads `00:00` only once the quote has expired. */
export function formatCountdown(ms: number): string {
  const totalSeconds = Math.ceil(Math.max(0, ms) / 1000);
  const minutes = String(Math.floor(totalSeconds / 60)).padStart(2, "0");
  const seconds = String(totalSeconds % 60).padStart(2, "0");
  return `${minutes}:${seconds}`;
}
