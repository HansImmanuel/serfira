import { describe, expect, it } from "vitest";

import { REPORT_PAGE_SIZE, agingReportPath } from "./useReports";

describe("agingReportPath", () => {
  it("pages the per-contract rows server-side", () => {
    expect(REPORT_PAGE_SIZE).toBe(20);
    expect(agingReportPath(0)).toBe("/reports/aging?page=0&size=20");
  });

  it("never sends as_of: the backend's today is the only valid value (ADR-013 A-6)", () => {
    expect(agingReportPath(3)).not.toContain("as_of");
  });
});
