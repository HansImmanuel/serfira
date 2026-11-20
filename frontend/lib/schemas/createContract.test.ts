import { describe, expect, it } from "vitest";

import {
  createContractSchema,
  emptyCreateContractValues,
  toCreateContractPayload,
} from "./createContract";

const validValues = {
  ...emptyCreateContractValues,
  full_name: "Budi Santoso",
  nik: "3174010101900001",
  phone: "081234567890",
  address: "Jl. Merdeka No. 1, Jakarta",
  asset_type: "MOTORCYCLE" as const,
  brand: "Honda",
  model: "Vario 160",
  serial_no: "MH1JM1234567890",
  plate_no: "B 1234 XYZ",
  asset_price: "20000000",
  down_payment: "5000000",
  tenor_months: "12",
  interest_scheme: "FLAT" as const,
  interest_rate_percent: "1.5",
  planned_start_date: "2026-12-01",
};

function issuesOf(values: Record<string, unknown>): string[] {
  const result = createContractSchema.safeParse(values);
  return result.success ? [] : result.error.issues.map((issue) => issue.path.join("."));
}

describe("createContractSchema", () => {
  it("accepts a complete draft", () => {
    const result = createContractSchema.safeParse(validValues);
    expect(result.success).toBe(true);
  });

  it("requires the customer identity fields", () => {
    expect(issuesOf({ ...validValues, full_name: "  " })).toContain("full_name");
    expect(issuesOf({ ...validValues, address: "" })).toContain("address");
  });

  it("requires exactly 16 NIK digits after separators", () => {
    expect(issuesOf({ ...validValues, nik: "317401010190000" })).toContain("nik");
    expect(issuesOf({ ...validValues, nik: "31740101019000011" })).toContain("nik");
    expect(issuesOf({ ...validValues, nik: "3174-0101-0190-0001" })).not.toContain("nik");
    expect(issuesOf({ ...validValues, nik: "317401010190000A" })).toContain("nik");
  });

  it("requires a phone of 9 to 15 digits after separators", () => {
    expect(issuesOf({ ...validValues, phone: "0812345" })).toContain("phone");
    expect(issuesOf({ ...validValues, phone: "+62 812-3456-7890" })).not.toContain("phone");
    expect(issuesOf({ ...validValues, phone: "0812345678901234567" })).toContain("phone");
  });

  it("rejects money input finer than scale 2 instead of rounding it", () => {
    expect(issuesOf({ ...validValues, asset_price: "20000000.005" })).toContain("asset_price");
    expect(issuesOf({ ...validValues, asset_price: "dua puluh juta" })).toContain("asset_price");
  });

  it("requires a positive asset price and a down payment below it", () => {
    expect(issuesOf({ ...validValues, asset_price: "0" })).toContain("asset_price");
    expect(issuesOf({ ...validValues, down_payment: "-1" })).toContain("down_payment");
    expect(issuesOf({ ...validValues, down_payment: "20000000" })).toContain("down_payment");
    expect(issuesOf({ ...validValues, down_payment: "20000001" })).toContain("down_payment");
  });

  it("requires a whole tenor between 1 and 120 months", () => {
    expect(issuesOf({ ...validValues, tenor_months: "0" })).toContain("tenor_months");
    expect(issuesOf({ ...validValues, tenor_months: "121" })).toContain("tenor_months");
    expect(issuesOf({ ...validValues, tenor_months: "12.5" })).toContain("tenor_months");
    expect(issuesOf({ ...validValues, tenor_months: "abc" })).toContain("tenor_months");
  });

  it("requires a percentage the rate column can hold", () => {
    expect(issuesOf({ ...validValues, interest_rate_percent: "1.234" })).toContain("interest_rate_percent");
    expect(issuesOf({ ...validValues, interest_rate_percent: "-0.5" })).toContain("interest_rate_percent");
    expect(issuesOf({ ...validValues, interest_rate_percent: "0" })).not.toContain("interest_rate_percent");
  });

  it("rejects a date that does not exist", () => {
    expect(issuesOf({ ...validValues, planned_start_date: "" })).toContain("planned_start_date");
    expect(issuesOf({ ...validValues, planned_start_date: "2026-02-30" })).toContain("planned_start_date");
    expect(issuesOf({ ...validValues, planned_start_date: "01/12/2026" })).toContain("planned_start_date");
  });
});

describe("toCreateContractPayload", () => {
  it("maps the form to the snake_case request the API expects", () => {
    const parsed = createContractSchema.parse(validValues);

    expect(toCreateContractPayload(parsed)).toEqual({
      customer: {
        full_name: "Budi Santoso",
        nik: "3174010101900001",
        phone: "081234567890",
        address: "Jl. Merdeka No. 1, Jakarta",
      },
      asset: {
        asset_type: "MOTORCYCLE",
        brand: "Honda",
        model: "Vario 160",
        serial_no: "MH1JM1234567890",
        plate_no: "B 1234 XYZ",
      },
      asset_price: "20000000.00",
      down_payment: "5000000.00",
      tenor_months: 12,
      interest_scheme: "FLAT",
      interest_rate: "0.0150",
      planned_start_date: "2026-12-01",
    });
  });

  it("sends absent optional identity fields as null rather than an empty string", () => {
    const parsed = createContractSchema.parse({ ...validValues, serial_no: "  ", plate_no: "" });

    const payload = toCreateContractPayload(parsed);

    expect(payload.asset.serial_no).toBeNull();
    expect(payload.asset.plate_no).toBeNull();
  });
});
