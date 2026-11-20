import { describe, expect, it } from "vitest";

import { formatDate, formatDateTime, formatMoney } from "./format";

describe("formatMoney", () => {
  it("formats rupiah with id-ID grouping and no decimals", () => {
    const formatted = formatMoney(1573333);
    expect(formatted.startsWith("Rp")).toBe(true);
    expect(formatted).toContain("1.573.333");
    expect(formatted).not.toContain(",00");
  });

  it("accepts a decimal string as returned by the API", () => {
    expect(formatMoney("1500000.00")).toContain("1.500.000");
  });

  it("renders zero as rupiah rather than as an empty value", () => {
    expect(formatMoney(0)).toContain("0");
  });

  it("renders a missing amount as the documented dash", () => {
    expect(formatMoney(null)).toBe("–");
    expect(formatMoney(undefined)).toBe("–");
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
