/** Wire types for the contract module (FE §2.2–§2.5), snake_case to match the API exactly. */

export type Money = string;

export type ContractStatus = "DRAFT" | "ACTIVE" | "CLOSED" | "TERMINATED";
export type InterestScheme = "FLAT" | "EFFECTIVE";
export type AssetType = "MOTORCYCLE" | "CAR" | "ELECTRONICS" | "OTHER";
export type InstallmentStatus =
  | "PENDING"
  | "PARTIALLY_PAID"
  | "PAID"
  | "OVERDUE"
  | "SETTLED"
  | "WRITTEN_OFF";
export type ClosedReason = "MATURITY" | "SETTLEMENT";

export interface CustomerSummary {
  id: string;
  full_name: string;
  nik: string;
  phone: string;
  address: string | null;
}

export interface AssetSummary {
  id: string;
  asset_type: AssetType;
  brand: string;
  model: string;
  serial_no: string | null;
  plate_no: string | null;
}

export interface ContractListItem {
  id: string;
  contract_no: string;
  customer: CustomerSummary;
  asset: AssetSummary;
  principal: Money;
  tenor_months: number;
  status: ContractStatus;
  /** null while DRAFT: a draft has no schedule, so there is no receivable yet (renders "–"). */
  outstanding: Money | null;
  planned_start_date: string;
  start_date: string | null;
  created_at: string;
}

export interface Contract extends ContractListItem {
  asset_price: Money;
  down_payment: Money;
  interest_scheme: InterestScheme;
  interest_rate: Money;
  grace_period_days: number;
  penalty_rate_daily: Money;
  closed_at: string | null;
  closed_reason: ClosedReason | null;
  version: number;
}

/** One schedule row (FE §2.5). Components derive recognised total and resolved amount from these. */
export interface Installment {
  id: string;
  period_no: number;
  due_date: string;
  principal_amount: Money;
  interest_amount: Money;
  recognized_interest_amount: Money;
  /** Effective (remaining) penalty, already net of active adjustments. */
  penalty_amount: Money;
  paid_amount: Money;
  settled_amount: Money;
  written_off_amount: Money;
  status: InstallmentStatus;
  outstanding: Money;
}

export interface CreditApplication {
  application_id: string;
  credit_id: string;
  installment_id: string;
  amount: Money;
  applied_at: string;
}

export interface CreditBalance {
  total_credit: Money;
  available_balance: Money;
  applications: CreditApplication[];
}

/**
 * `POST /contracts/{id}/credit/apply` body: `amount` applies part of the available balance; omitting it
 * applies all of it (Addendum §2.2). The endpoint requires an `Idempotency-Key` header (ADR-017): the same key
 * and body replay the stored result, a different body under the key is 409 `CONFLICT`.
 */
export interface ApplyCreditPayload {
  amount?: string;
}

export interface CreditApplicationResult {
  applied_amount: Money;
  available_balance: Money;
  applications: CreditApplication[];
}

/** `journal_entry.ref_type` vocabulary (`LedgerRefType` on the backend), in the backend's order. */
export const LEDGER_REF_TYPES = [
  "CONTRACT_ACTIVATION",
  "PAYMENT",
  "BILLING",
  "PENALTY_ACCRUAL",
  "PENALTY_WAIVER",
  "CREDIT_APPLICATION",
  "SETTLEMENT",
  "WRITE_OFF",
] as const;

export type LedgerRefType = (typeof LEDGER_REF_TYPES)[number];

/** A posted journal line (ADR-013 A-8): ledger-literal, never a running balance. */
export interface StatementRow {
  entry_date: string;
  account_code: string;
  account_name: string;
  description: string | null;
  ref_type: LedgerRefType;
  ref_id: string;
  debit: Money;
  credit: Money;
  is_reversal: boolean;
  reversal_of_id: string | null;
}

/** Create payload: money is sent as a scale-2 string so no binary float reaches the server. */
export interface CreateContractPayload {
  customer: {
    full_name: string;
    nik: string;
    phone: string;
    address: string | null;
  };
  asset: {
    asset_type: AssetType;
    brand: string;
    model: string;
    serial_no: string | null;
    plate_no: string | null;
  };
  asset_price: string;
  down_payment: string;
  tenor_months: number;
  interest_scheme: InterestScheme;
  interest_rate: string;
  planned_start_date: string;
}

/** `POST /contracts/{id}/write-off` body (F5): the operator's reason, trimmed, at most 500 characters. */
export interface WriteOffPayload {
  reason: string;
}

/** One installment the write-off closed, with the recognized receivable capped on it. */
export interface WrittenOffInstallment {
  installment_id: string;
  period_no: number;
  written_off_amount: Money;
}

/** `POST /contracts/{id}/write-off` response (`WriteOffResponse`): the terminated contract and what was capped. */
export interface WriteOffResult {
  contract_id: string;
  contract_no: string;
  status: ContractStatus;
  write_off_reason: string;
  written_off_at: string;
  total_written_off: Money;
  installments: WrittenOffInstallment[];
}
