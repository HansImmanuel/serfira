import { describe, expect, it } from "vitest";

import { rateFractionToPercent } from "./decimal";

describe("rateFractionToPercent", () => {
  it("turns the stored fraction back into the monthly percentage the operator entered", () => {
    expect(rateFractionToPercent("0.0150")).toBe("1.5");
    expect(rateFractionToPercent("0.0050")).toBe("0.5");
    expect(rateFractionToPercent("0.1200")).toBe("12");
    expect(rateFractionToPercent("0.0225")).toBe("2.25");
    expect(rateFractionToPercent(0.0005)).toBe("0.05");
  });

  it("renders zero interest as 0", () => {
    expect(rateFractionToPercent("0.0000")).toBe("0");
  });
});
