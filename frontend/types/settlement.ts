/**
 * Wire types for early settlement (FE §2.8), snake_case to match `SettlementQuoteResponse` and
 * `SettlementExecutionResponse` field for field.
 */

import type { Money } from "@/types/contract";

/** `POST /settlements/quote` (ADR-018 D6): the priced quote, valid until `valid_until`. */
export interface SettlementQuote {
  quote_id: string;
  quote_no: string;
  contract_id: string;
  status: string;
  quoted_at: string;
  valid_until: string;
  contract_version: number;
  outstanding_principal: Money;
  unpaid_billed_interest: Money;
  accrued_interest: Money;
  penalty_outstanding: Money;
  rebate_amount: Money;
  admin_fee: Money;
  /** Snapshotted, not consumed by the quote; execution consumes all of it (ADR-018 D10). */
  available_credit: Money;
  /** Always `0` on a quote, so `cash_due` equals `gross_amount` (T39 decision 5). */
  credit_used: Money;
  gross_amount: Money;
  cash_due: Money;
}

/** One receivable-resolution line (`PENALTY`/`INTEREST`/`PRINCIPAL`) of an executed settlement. */
export interface SettlementAllocation {
  installment_id: string;
  allocation_type: string;
  amount: Money;
}

/** One consumed customer-credit source of an executed settlement. */
export interface SettlementCreditApplication {
  contract_credit_id: string;
  amount: Money;
}

/** `POST /settlements` (ADR-018 D4/D5): the executed settlement record. */
export interface SettlementExecution {
  settlement_id: string;
  settlement_no: string;
  contract_id: string;
  quote_id: string;
  cash_received: Money;
  credit_used: Money;
  rebate_amount: Money;
  admin_fee: Money;
  executed_at: string;
  allocations: SettlementAllocation[];
  credit_applications: SettlementCreditApplication[];
}
