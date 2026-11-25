/**
 * Client-side validation for the payment form (FE §2.6), mirroring `PaymentRequest`: a positive
 * scale-2 amount within `NUMERIC(19,2)` and one of the four channels. The server stays the
 * authority; this only turns an obvious 400 into an inline message.
 */

import { z } from "zod";

import { compareMoney, normalizeMoney } from "@/lib/decimal";
import type { PaymentChannel, PaymentPayload } from "@/types/payment";

export const PAYMENT_CHANNELS = ["CASH", "BANK_TRANSFER", "VA_STUB", "EWALLET_STUB"] as const;

const PAYMENT_CHANNEL_LABELS: Record<PaymentChannel, string> = {
  CASH: "Tunai",
  BANK_TRANSFER: "Transfer bank",
  VA_STUB: "Virtual account (stub)",
  EWALLET_STUB: "E-wallet (stub)",
};

export const PAYMENT_CHANNEL_OPTIONS = PAYMENT_CHANNELS.map((value) => ({
  value,
  label: PAYMENT_CHANNEL_LABELS[value],
}));

const AMOUNT_MESSAGE = "Jumlah bayar harus lebih dari 0, maksimal 17 digit bulat dan 2 desimal.";

function positiveMoney(value: string): string | null {
  const normalized = normalizeMoney(value);
  return normalized !== null && compareMoney(normalized, "0.00") > 0 ? normalized : null;
}

export const paymentSchema = z.object({
  amount: z
    .string()
    .superRefine((value, ctx) => {
      if (positiveMoney(value) === null) {
        ctx.addIssue({ code: "custom", message: AMOUNT_MESSAGE });
      }
    })
    .transform((value) => positiveMoney(value) ?? value),
  channel: z.enum(PAYMENT_CHANNELS),
});

export type PaymentValues = z.infer<typeof paymentSchema>;

export const emptyPaymentValues: PaymentValues = { amount: "", channel: "CASH" };

/** Maps validated values to the request body; the amount is already a scale-2 string. */
export function toPaymentPayload(contractId: string, values: PaymentValues): PaymentPayload {
  return { contract_id: contractId, amount: values.amount, channel: values.channel };
}
