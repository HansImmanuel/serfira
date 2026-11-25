import { describe, expect, it } from "vitest";

import { compareMoney } from "./decimal";

describe("compareMoney", () => {
  it("orders two scale-2 amounts numerically", () => {
    expect(compareMoney("100.00", "99.99")).toBe(1);
    expect(compareMoney("99.99", "100.00")).toBe(-1);
    expect(compareMoney("100", "100.00")).toBe(0);
  });

  it("is exact for values a float comparison would round", () => {
    expect(compareMoney("0.1", "0.3")).toBe(-1);
    expect(compareMoney("10000000.01", "10000000.00")).toBe(1);
  });
});
