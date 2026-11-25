import { describe, expect, it } from "vitest";

import { PAYMENT_CHANNEL_OPTIONS, paymentSchema, toPaymentPayload } from "@/lib/schemas/payment";

describe("paymentSchema", () => {
  it.each(["", "0", "0.00", "-5", "1.234", "1e3", "abc", "100000000000000000"])(
    "rejects amount %j",
    (amount) => {
      expect(paymentSchema.safeParse({ amount, channel: "CASH" }).success).toBe(false);
    },
  );

  it("names the amount rule in the error message", () => {
    const result = paymentSchema.safeParse({ amount: "0", channel: "CASH" });
    expect(result.success).toBe(false);
    expect(result.error?.issues[0]?.message).toContain("Jumlah bayar harus lebih dari 0");
  });

  it("normalizes a valid amount to scale 2", () => {
    expect(paymentSchema.parse({ amount: "1500000", channel: "BANK_TRANSFER" }).amount).toBe("1500000.00");
  });

  it("accepts the 17-integer-digit maximum without losing precision", () => {
    expect(paymentSchema.parse({ amount: "99999999999999999.99", channel: "CASH" }).amount).toBe(
      "99999999999999999.99",
    );
  });

  it("rejects an unknown channel", () => {
    expect(paymentSchema.safeParse({ amount: "1", channel: "CHEQUE" }).success).toBe(false);
  });

  it("offers exactly the four API channels", () => {
    expect(PAYMENT_CHANNEL_OPTIONS.map((option) => option.value)).toEqual([
      "CASH",
      "BANK_TRANSFER",
      "VA_STUB",
      "EWALLET_STUB",
    ]);
  });
});

describe("toPaymentPayload", () => {
  it("builds the API payload with no paid_at or note", () => {
    const values = paymentSchema.parse({ amount: "250000.5", channel: "CASH" });
    expect(toPaymentPayload("c1", values)).toEqual({ contract_id: "c1", amount: "250000.50", channel: "CASH" });
  });
});
