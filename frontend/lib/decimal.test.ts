import { describe, expect, it } from "vitest";

import { addMoney, normalizeMoney, percentToRateFraction, subtractMoney } from "./decimal";

describe("normalizeMoney", () => {
  it("pads a whole amount to scale 2", () => {
    expect(normalizeMoney("1500000")).toBe("1500000.00");
    expect(normalizeMoney(" 2500000 ")).toBe("2500000.00");
  });

  it("keeps an amount that already has one or two decimals", () => {
    expect(normalizeMoney("1500000.5")).toBe("1500000.50");
    expect(normalizeMoney("1500000.55")).toBe("1500000.55");
  });

  it("rejects input finer than the API's scale 2 instead of rounding it", () => {
    expect(normalizeMoney("1500000.555")).toBeNull();
  });

  it("rejects anything that is not a plain decimal number", () => {
    expect(normalizeMoney("")).toBeNull();
    expect(normalizeMoney("1,5")).toBeNull();
    expect(normalizeMoney("1.500.000")).toBeNull();
    expect(normalizeMoney("abc")).toBeNull();
    expect(normalizeMoney("1.")).toBeNull();
    expect(normalizeMoney(".5")).toBeNull();
  });
});

describe("addMoney", () => {
  it("adds without binary floating point drift", () => {
    expect(addMoney("0.1", "0.2")).toBe("0.30");
    expect(addMoney(1573333.33, 0.01)).toBe("1573333.34");
  });

  it("keeps the sign of a negative total", () => {
    expect(addMoney("100.00", "-150.00")).toBe("-50.00");
  });
});

describe("subtractMoney", () => {
  it("computes the principal preview the create form shows", () => {
    expect(subtractMoney("20000000", "5000000")).toBe("15000000.00");
    expect(subtractMoney("20000000.50", "5000000.25")).toBe("15000000.25");
  });

  it("returns a negative amount when the down payment exceeds the price", () => {
    expect(subtractMoney("1000", "1500")).toBe("-500.00");
  });
});

describe("percentToRateFraction", () => {
  it("converts a monthly percentage into the API's rate fraction at scale 4", () => {
    expect(percentToRateFraction("1.5")).toBe("0.0150");
    expect(percentToRateFraction("0.5")).toBe("0.0050");
    expect(percentToRateFraction("12")).toBe("0.1200");
    expect(percentToRateFraction("2.25")).toBe("0.0225");
  });

  it("supports zero interest", () => {
    expect(percentToRateFraction("0")).toBe("0.0000");
  });

  it("rejects a percentage the rate column cannot represent exactly", () => {
    expect(percentToRateFraction("1.234")).toBeNull();
    expect(percentToRateFraction("abc")).toBeNull();
    expect(percentToRateFraction("-1")).toBeNull();
  });
});
