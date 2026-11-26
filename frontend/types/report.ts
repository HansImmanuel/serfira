import type { PageResponse } from "@/types/api";
import type { Money } from "@/types/contract";

/** A report ratio: a scale-4 fraction decimal string (ADR-026 D7), e.g. `"0.0150"` = 1.5%. */
export type Ratio = string;

/** One amount per delinquency bucket, plus their total (`AgingBucketsResponse`; Σ bucket = total). */
export interface AgingBuckets {
  current: Money;
  dpd_1_30: Money;
  dpd_31_60: Money;
  dpd_61_90: Money;
  dpd_over_90: Money;
  total_outstanding: Money;
}

/** One ACTIVE contract's line in the aging report (`ContractAgingRow`). */
export interface ContractAgingRow {
  contract_id: string;
  contract_no: string;
  customer_name: string;
  buckets: AgingBuckets;
}

/** `GET /reports/aging` (FE §2.9): `portfolio` spans every ACTIVE contract, not just this page. */
export interface AgingReport {
  as_of: string;
  portfolio: AgingBuckets;
  contracts: PageResponse<ContractAgingRow>;
}

/** `GET /reports/outstanding` (FE §2.10, ADR-026). Every value is shown as returned. */
export interface OutstandingReport {
  as_of: string;
  total_outstanding: Money;
  accounts_receivable: Money;
  npl: {
    contract_count: number;
    outstanding: Money;
    ratio: Ratio | null;
  };
  collection: {
    period_from: string;
    period_to: string;
    collected: Money;
    due: Money;
    rate: Ratio | null;
  };
  settlement_cash: {
    period_from: string;
    period_to: string;
    amount: Money;
  };
  active_contract_count: number;
}
