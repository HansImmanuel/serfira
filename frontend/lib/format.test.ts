import { describe, expect, it } from "vitest";

import { EMPTY_VALUE, formatDate, formatDateTime, formatMoney, formatRatioPercent } from "./format";

describe("formatMoney", () => {
  it("formats rupiah with id-ID grouping and no decimals", () => {
    const formatted = formatMoney("1573333.00");
    expect(formatted.startsWith("Rp")).toBe(true);
    expect(formatted).toContain("1.573.333");
    expect(formatted).not.toContain(",00");
  });

  it("accepts the exact decimal string returned by the API", () => {
    expect(formatMoney("1500000.00")).toContain("1.500.000");
  });

  it("renders zero as rupiah rather than as an empty value", () => {
    expect(formatMoney("0.00")).toContain("0");
  });

  it("keeps every digit of a value beyond Number.MAX_SAFE_INTEGER", () => {
    // 2^53 + 1 is not representable as a double; parsing through Number would round it.
    expect(formatMoney("9007199254740993.00")).toContain("9.007.199.254.740.993");
    expect(formatMoney("99999999999999999.99")).toContain("100.000.000.000.000.000");
  });

  it("rounds half away from zero to whole rupiah, like the previous Intl behaviour", () => {
    expect(formatMoney("1000.49")).toContain("1.000");
    expect(formatMoney("1000.50")).toContain("1.001");
    expect(formatMoney("-1000.50")).toContain("1.001");
  });

  it("renders a negative amount with the sign before the currency symbol", () => {
    expect(formatMoney("-1573333.00")).toBe("-Rp\u00a01.573.333");
  });

  it("renders a missing amount as the documented dash", () => {
    expect(formatMoney(null)).toBe("–");
    expect(formatMoney(undefined)).toBe("–");
  });

  it("renders a malformed amount as the dash instead of NaN", () => {
    expect(formatMoney("abc")).toBe("–");
    expect(formatMoney("1e3")).toBe("–");
    expect(formatMoney("1,5")).toBe("–");
  });
});

describe("formatDate", () => {
  it("formats a business date without shifting the day", () => {
    expect(formatDate("2026-02-28")).toBe("28 Feb 2026");
    expect(formatDate("2026-01-01")).toBe("01 Jan 2026");
  });

  it("formats a timestamp in the business zone", () => {
    expect(formatDate("2026-11-19T23:30:00+07:00")).toBe("19 Nov 2026");
  });

  it("renders a missing date as the documented dash", () => {
    expect(formatDate(null)).toBe("–");
  });
});

describe("formatDateTime", () => {
  it("renders the Jakarta wall-clock time of a timestamp", () => {
    const formatted = formatDateTime("2026-11-19T15:30:43+07:00");
    expect(formatted).toContain("19 Nov 2026");
    expect(formatted).toContain("15.30");
  });

  it("renders a missing timestamp as the documented dash", () => {
    expect(formatDateTime(null)).toBe("–");
  });
});

describe("formatRatioPercent", () => {
  it("turns a scale-4 fraction into an id-ID percent with two decimals", () => {
    expect(formatRatioPercent("0.0150")).toBe("1,50%");
    expect(formatRatioPercent("0.4393")).toBe("43,93%");
    expect(formatRatioPercent("0.0005")).toBe("0,05%");
  });

  it("renders zero and one hundred percent", () => {
    expect(formatRatioPercent("0.0000")).toBe("0,00%");
    expect(formatRatioPercent("1.0000")).toBe("100,00%");
  });

  it("groups thousands and never clamps above one", () => {
    expect(formatRatioPercent("12.5000")).toBe("1.250,00%");
  });

  it("pads a fraction coarser than four places", () => {
    expect(formatRatioPercent("0.015")).toBe("1,50%");
  });

  it("keeps every digit beyond float precision", () => {
    expect(formatRatioPercent("123456789012345.0000")).toBe("12.345.678.901.234.500,00%");
  });

  it("renders a null ratio (zero denominator) and malformed input as the empty dash", () => {
    for (const value of [null, undefined, "abc", "-0.0100", "0.12345", ""]) {
      expect(formatRatioPercent(value)).toBe(EMPTY_VALUE);
    }
  });
});
