/** Wire types for the payment module (FE §2.6), snake_case to match `PaymentRequest`/`PaymentResponse`. */

import type { Money } from "@/types/contract";

export type PaymentChannel = "CASH" | "BANK_TRANSFER" | "VA_STUB" | "EWALLET_STUB";
export type AllocationType = "PENALTY" | "INTEREST" | "PRINCIPAL" | "EXCESS";
export type PaymentStatus = "POSTED" | "VOIDED";

/**
 * `POST /payments` body. There is deliberately no `paid_at` and no note: the server clock is the
 * payment instant (TS §2.0) and the payment row has no note column (T33 decision, 2026-11-25).
 */
export interface PaymentPayload {
  contract_id: string;
  amount: string;
  channel: PaymentChannel;
}

/** One allocation line; `installment_id` is null exactly for `EXCESS` (held as customer credit). */
export interface PaymentAllocation {
  installment_id: string | null;
  allocation_type: AllocationType;
  amount: Money;
}

/** A posted payment with the allocation the server produced (ADR-009); also what a replay returns. */
export interface Payment {
  id: string;
  payment_no: string;
  contract_id: string;
  amount: Money;
  channel: PaymentChannel;
  paid_at: string;
  status: PaymentStatus;
  excess_amount: Money;
  allocations: PaymentAllocation[];
}

/**
 * One row of `GET /contracts/{id}/payments` (newest first, every status). Allocation lines come in no
 * defined order; `voided_at` and `void_reason` are set exactly when `status` is VOIDED.
 */
export interface ContractPayment {
  id: string;
  payment_no: string;
  amount: Money;
  channel: PaymentChannel;
  paid_at: string;
  status: PaymentStatus;
  excess_amount: Money;
  voided_at: string | null;
  void_reason: string | null;
  allocations: PaymentAllocation[];
}
