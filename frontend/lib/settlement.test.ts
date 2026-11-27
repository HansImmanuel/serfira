import { describe, expect, it } from "vitest";

import { formatCountdown, quoteLifetimeMs, remainingMs, settlementBreakdown } from "@/lib/settlement";
import type { SettlementQuote } from "@/types/settlement";

const BASE_QUOTE: SettlementQuote = {
  quote_id: "q1",
  quote_no: "Q-20261127-0001",
  contract_id: "c1",
  status: "QUOTED",
  quoted_at: "2026-11-27T10:00:00+07:00",
  valid_until: "2026-11-27T10:15:00+07:00",
  contract_version: 3,
  outstanding_principal: "10000000.00",
  unpaid_billed_interest: "150000.00",
  accrued_interest: "50000.00",
  penalty_outstanding: "25000.00",
  rebate_amount: "300000.00",
  admin_fee: "100000.00",
  available_credit: "0.00",
  credit_used: "0.00",
  gross_amount: "10625000.00",
  cash_due: "10625000.00",
};

describe("settlementBreakdown", () => {
  it("derives the future interest so the displayed lines sum to the gross", () => {
    expect(settlementBreakdown(BASE_QUOTE)).toEqual({
      futureInterestGross: "600000.00",
      cashDue: "10625000.00",
      creditExceedsGross: false,
    });
  });

  it("subtracts the available credit from the gross, not the quote's cash_due", () => {
    const breakdown = settlementBreakdown({ ...BASE_QUOTE, available_credit: "125000.00" });

    expect(breakdown.cashDue).toBe("10500000.00");
    expect(breakdown.creditExceedsGross).toBe(false);
  });

  it("shows zero cash due when the credit equals the gross", () => {
    expect(settlementBreakdown({ ...BASE_QUOTE, available_credit: "10625000.00" })).toEqual({
      futureInterestGross: "600000.00",
      cashDue: "0.00",
      creditExceedsGross: false,
    });
  });

  it("has no cash due when the credit exceeds the gross", () => {
    expect(settlementBreakdown({ ...BASE_QUOTE, available_credit: "10625000.01" })).toEqual({
      futureInterestGross: "600000.00",
      cashDue: null,
      creditExceedsGross: true,
    });
  });
});

describe("quoteLifetimeMs", () => {
  it("measures two +07:00 instants 15 minutes apart", () => {
    expect(
      quoteLifetimeMs({ quoted_at: "2026-11-27T10:00:00+07:00", valid_until: "2026-11-27T10:15:00+07:00" }),
    ).toBe(900000);
  });

  it("measures the same span across a Z start and a +07:00 end", () => {
    expect(
      quoteLifetimeMs({ quoted_at: "2026-11-27T03:00:00Z", valid_until: "2026-11-27T10:15:00+07:00" }),
    ).toBe(900000);
  });

  it("keeps microsecond fractions exact", () => {
    expect(
      quoteLifetimeMs({
        quoted_at: "2026-11-27T10:00:00.123456+07:00",
        valid_until: "2026-11-27T10:15:00.123456+07:00",
      }),
    ).toBe(900000);
  });
});

describe("remainingMs", () => {
  it("is the whole lifetime when no time has passed", () => {
    expect(remainingMs(900000, 1000, 1000)).toBe(900000);
  });

  it("subtracts the elapsed time since the quote arrived", () => {
    expect(remainingMs(900000, 1000, 900500)).toBe(500);
  });

  it("is clamped at zero", () => {
    expect(remainingMs(900000, 1000, 2_000_000)).toBe(0);
  });
});

describe("formatCountdown", () => {
  it.each([
    [900000, "15:00"],
    [61000, "01:01"],
    [500, "00:01"],
    [0, "00:00"],
  ])("formats %d ms as %s, rounding seconds up", (ms, expected) => {
    expect(formatCountdown(ms)).toBe(expected);
  });
});
