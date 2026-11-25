/**
 * Client-side validation for the create-contract form (FE §2.3), mirroring the server's accepted
 * shapes so the operator gets an inline message instead of a 400. The server remains the authority:
 * this schema never replaces a backend rule.
 */

import { z } from "zod";

import { compareMoney, normalizeMoney, percentToRateFraction } from "@/lib/decimal";
import type { AssetType, CreateContractPayload, InterestScheme } from "@/types/contract";

export const ASSET_TYPES = ["MOTORCYCLE", "CAR", "ELECTRONICS", "OTHER"] as const;
export const INTEREST_SCHEMES = ["FLAT", "EFFECTIVE"] as const;

const ASSET_TYPE_LABELS: Record<AssetType, string> = {
  MOTORCYCLE: "Motor",
  CAR: "Mobil",
  ELECTRONICS: "Elektronik",
  OTHER: "Lainnya",
};

export const ASSET_TYPE_OPTIONS = ASSET_TYPES.map((value) => ({ value, label: ASSET_TYPE_LABELS[value] }));

const INTEREST_SCHEME_LABELS: Record<InterestScheme, string> = {
  FLAT: "Flat",
  EFFECTIVE: "Efektif",
};

export const INTEREST_SCHEME_OPTIONS = INTEREST_SCHEMES.map((value) => ({
  value,
  label: INTEREST_SCHEME_LABELS[value],
}));

function digitsOf(value: string): string {
  return value.replace(/\D/g, "");
}

/**
 * Mirrors `PiiHasher.normalizePhone` (Addendum A9): strip non-digits, then convert a leading local
 * trunk `0` to the international `62`. The backend validates the *normalized* length (9–15), so a
 * 15-digit local number becomes 16 digits once normalized and must be rejected here as well;
 * counting raw digits would let it through and earn a 400 from the API.
 */
export function normalizePhone(value: string): string {
  const digits = digitsOf(value);
  return digits.startsWith("0") ? `62${digits.slice(1)}` : digits;
}

function isCalendarDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (match === null) {
    return false;
  }
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}

const requiredText = (label: string, max: number) =>
  z
    .string()
    .trim()
    .min(1, `${label} wajib diisi.`)
    .max(max, `${label} maksimal ${max} karakter.`);

const optionalText = (max: number) => z.string().trim().max(max, `Maksimal ${max} karakter.`);

/** An optional text field is sent as `null` when blank, never as an empty string. */
function nullIfBlank(value: string): string | null {
  return value.length === 0 ? null : value;
}

const moneyField = (label: string) =>
  z
    .string()
    .superRefine((value, ctx) => {
      if (normalizeMoney(value) === null) {
        ctx.addIssue({
          code: "custom",
          message: `${label} harus berupa angka maksimal 17 digit bulat dan 2 desimal.`,
        });
      }
    })
    .transform((value) => normalizeMoney(value) ?? value);

const percentField = z.string().superRefine((value, ctx) => {
  const fraction = percentToRateFraction(value);
  if (fraction === null) {
    ctx.addIssue({
      code: "custom",
      message: "Suku bunga harus angka 0–100 dengan maksimal 2 desimal.",
    });
  } else if (compareMoney(fraction, "1.0000") > 0) {
    ctx.addIssue({ code: "custom", message: "Suku bunga maksimal 100% per bulan." });
  }
});

export const createContractSchema = z
  .object({
    full_name: requiredText("Nama nasabah", 120),
    nik: z
      .string()
      .superRefine((value, ctx) => {
        if (digitsOf(value).length !== 16) {
          ctx.addIssue({ code: "custom", message: "NIK harus 16 digit." });
        }
      }),
    phone: z
      .string()
      .superRefine((value, ctx) => {
        const digits = normalizePhone(value).length;
        if (digits < 9 || digits > 15) {
          ctx.addIssue({ code: "custom", message: "Nomor telepon harus 9–15 digit." });
        }
      }),
    address: requiredText("Alamat", 500),
    asset_type: z.enum(ASSET_TYPES),
    brand: requiredText("Merek", 80),
    model: requiredText("Model", 80),
    serial_no: optionalText(40),
    plate_no: optionalText(20),
    asset_price: moneyField("Harga aset"),
    down_payment: moneyField("Uang muka"),
    tenor_months: z
      .string()
      .trim()
      .regex(/^\d+$/, "Tenor harus bilangan bulat bulan.")
      .refine((value) => {
        const months = Number(value);
        return months >= 1 && months <= 120;
      }, "Tenor harus antara 1 dan 120 bulan."),
    interest_scheme: z.enum(INTEREST_SCHEMES),
    interest_rate_percent: percentField,
    planned_start_date: z
      .string()
      .trim()
      .refine(isCalendarDate, "Tanggal mulai wajib berupa tanggal yang valid."),
  })
  .superRefine((values, ctx) => {
    const price = normalizeMoney(values.asset_price);
    const downPayment = normalizeMoney(values.down_payment);
    if (price === null || downPayment === null) {
      return;
    }
    if (compareMoney(price, "0") <= 0) {
      ctx.addIssue({ code: "custom", path: ["asset_price"], message: "Harga aset harus lebih besar dari 0." });
      return;
    }
    if (compareMoney(downPayment, "0") < 0) {
      ctx.addIssue({ code: "custom", path: ["down_payment"], message: "Uang muka tidak boleh negatif." });
      return;
    }
    if (compareMoney(downPayment, price) >= 0) {
      ctx.addIssue({
        code: "custom",
        path: ["down_payment"],
        message: "Uang muka harus lebih kecil dari harga aset.",
      });
    }
  });

export type CreateContractValues = z.infer<typeof createContractSchema>;

export const emptyCreateContractValues: CreateContractValues = {
  full_name: "",
  nik: "",
  phone: "",
  address: "",
  asset_type: "MOTORCYCLE",
  brand: "",
  model: "",
  serial_no: "",
  plate_no: "",
  asset_price: "",
  down_payment: "",
  tenor_months: "",
  interest_scheme: "FLAT",
  interest_rate_percent: "",
  planned_start_date: "",
};

/**
 * Maps validated form values to the request body. Money stays a scale-2 string and the rate a
 * scale-4 fraction, so nothing is re-submitted through a binary float (TS §2.0).
 */
export function toCreateContractPayload(values: CreateContractValues): CreateContractPayload {
  const interestRate = percentToRateFraction(values.interest_rate_percent);
  if (interestRate === null) {
    // Unreachable after validation; failing loudly beats sending a malformed rate.
    throw new Error("Suku bunga tidak valid.");
  }
  return {
    customer: {
      full_name: values.full_name,
      nik: values.nik,
      phone: normalizePhone(values.phone),
      address: values.address,
    },
    asset: {
      asset_type: values.asset_type,
      brand: values.brand,
      model: values.model,
      serial_no: nullIfBlank(values.serial_no),
      plate_no: nullIfBlank(values.plate_no),
    },
    asset_price: values.asset_price,
    down_payment: values.down_payment,
    tenor_months: Number(values.tenor_months),
    interest_scheme: values.interest_scheme,
    interest_rate: interestRate,
    planned_start_date: values.planned_start_date,
  };
}
