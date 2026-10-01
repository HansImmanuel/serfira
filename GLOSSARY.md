# Glossary

Canonical vocabulary for Serfira. When naming a domain concept in code, issues, tests, or proposals, use the term exactly as defined here. Do not introduce synonyms.

Term names mirror the code and the specs (`docs/03_DOMAIN_MODEL.md`, `docs/02_TECH_SPEC.md`, `docs/01_PRD.md`, `docs/adr/`). Definitions are in English for agent consumption; the authoritative specs are in Bahasa Indonesia and win on any conflict.

Seeded from the domain model. Extend it lazily via `/domain-modeling` as new terms get resolved; don't pre-populate speculative entries.

## Core entities

- **Contract** — A financing agreement for one Customer and one Asset (`principal`, `down_payment`, `tenor_months`, `interest_scheme`, `interest_rate`). Lifecycle `DRAFT → ACTIVE → CLOSED | TERMINATED`. Business number `MF-YYYYMM-XXXX`. One Contract has exactly one Schedule. (DM §1.3)
- **Customer** — The borrower. PII (`nik`, `phone`) is AES-256-GCM encrypted at rest and looked up by HMAC hash columns (`nik_hash`, `phone_lookup`); never stored or logged in plaintext. (DM §1.1, ADR-004)
- **Asset** — The financed good (`MOTORCYCLE`, `CAR`, `ELECTRONICS`, `OTHER`). De-duplicated by identity (`serial_no`, then `plate_no`); an asset with no identity cannot be de-duplicated. (DM §1.2, ADR-006)
- **Installment** — One scheduled monthly obligation of a Contract, keyed `(contract_id, period_no)` with a `due_date`. Carries `principal_amount`, scheduled `interest_amount`, `recognized_interest_amount`, `penalty_amount`, and the resolved amounts. Status `PENDING | PARTIALLY_PAID | PAID | OVERDUE | SETTLED | WRITTEN_OFF`. (DM §1.4)
- **Schedule** — The full set of Installments generated once at Contract activation. Activation is idempotent; a schedule is generated exactly once. (PRD, DM §1.3)
- **Payment** — Cash received against a Contract (`amount > 0`, a `channel`, `paid_at`). Status `POSTED | VOIDED`. A void is a reversal, never a delete. Business number `PAY-YYYYMM-XXXX`. (DM §1.7)
- **PaymentAllocation** — An append-only row recording how a Payment was split across an Installment's components. `allocation_type` is `PENALTY | INTEREST | PRINCIPAL | EXCESS`. (DM §1.8)

## Money and recognition

- **principal** — The financed amount after down payment. Invariant: `asset_price = principal + down_payment`. (DM §1.3)
- **interest_amount** — The interest scheduled for an Installment period. Scheduled interest is *not* a receivable until billed. (DM §1.4)
- **recognized_interest_amount** — Interest already recognized as a receivable (0 until Billing on the due date; may include settlement accrued interest). Only recognized interest is allocatable or collectable. (DM §1.4)
- **Billing** — The act of recognizing due interest on an Installment whose `due_date` has been reached: one `BILLING` journal per Installment for `interest_amount − recognized_interest_amount`. `SETTLED`/`WRITTEN_OFF` installments are never billed. (ADR-011, DM §1.4)
- **penalty_base** — `max(0, principal_amount + recognized_interest_amount − paid_amount − settled_amount − written_off_amount)`. The unpaid principal+recognized-interest balance a daily penalty is charged on. Penalties do not compound on unpaid penalty. (ADR-012, DM §1.4)
- **effective_penalty** — `penalty_amount − active paid penalty allocations − penalty adjustments`. The currently collectable penalty. (DM §1.4)
- **recognized_total** — `principal_amount + recognized_interest_amount + effective_penalty`. (DM §1.4)
- **resolved_amount** — `paid_amount + settled_amount + written_off_amount`. (DM §1.4)
- **outstanding** — `recognized_total − resolved_amount`. Prefer this precise term over the ambiguous word "balance". (DM §1.4)

## Allocation and credit

- **Allocation waterfall** — The order a Payment consumes an Installment: `PENALTY → INTEREST → PRINCIPAL`, then `EXCESS`. Oldest eligible Installment first (`due_date`, tie-break `period_no`). (ADR-009, DM §1.8)
- **Eligibility threshold** — Only Installments with `due_date <= payment business date` are allocatable. Future-dated Installments are never touched. (ADR-009)
- **EXCESS** — The single leftover allocation row (`installment_id IS NULL`) created when a Payment exceeds eligible obligations. Becomes customer credit. (DM §1.8)
- **TITIPAN_NASABAH** — The liability account holding customer credit from overpayment (literally "customer deposit"). Credit is never auto-applied to future Installments. (DM §1.12, PRD P-4)
- **ContractCredit** — An immutable source of excess amount (`AVAILABLE | APPLIED | REFUNDED`), originating from an `EXCESS` PaymentAllocation. Available credit = `amount − Σ application.amount`. (DM §1.11)
- **ContractCreditApplication** — A record applying some ContractCredit to an Installment. May only reduce recognized receivable, never future unrecognized interest. The audit trail is never lost. (DM §1.11)

## Penalty and aging

- **PenaltyAccrual** — One row per late day beyond grace, keyed `(installment_id, accrual_date)`. `amount` is the incremental daily charge, not a cumulative snapshot. Journal `PENALTY_ACCRUAL`. (ADR-012, DM §1.9)
- **PenaltyAdjustment** — An immutable `WAIVE` or `REDUCE` of penalty with a required `reason` and approving actor. Corrections are new adjustments, never updates. (DM §1.10)
- **grace_period_days** — Days after `due_date` before penalty and OVERDUE begin. Snapshotted onto the Contract at activation. (DM §1.3)
- **DPD (Days Past Due)** — On business date `D`: `max(0, D − due_date − grace_period_days)`. Aging reports compute DPD directly from `due_date`, never from Installment status. (ADR-013, DM §1.4)
- **OVERDUE** — An Installment becomes OVERDUE on the first chargeable day, `today >= due_date + grace_period_days + 1`. Aging only moves *into* OVERDUE; exit is only via payment, settlement, or write-off. (ADR-013, DM §1.4)
- **Aging buckets** — Delinquency bands: Current, 1–30, 31–60, 61–90, >90 days. (PRD)

## Settlement

- **SettlementQuote** — An immutable snapshot of settlement components (`outstanding_principal`, `unpaid_billed_interest`, `accrued_interest`, `penalty_outstanding`, `rebate_amount`, `admin_fee`, `available_credit`, `credit_used`, `gross_amount`, `cash_due`) with a `valid_until` TTL and a `contract_version` for stale detection. Status `QUOTED | EXECUTED | EXPIRED`. Number `Q-YYYYMMDD-XXXX`. (DM §1.5)
- **Settlement** — Execution of a quote: validates expiry, Contract still ACTIVE, and matching `contract_version` and snapshot before posting. Number `SET-YYYYMM-XXXX`. Idempotent. (DM §1.6)
- **rebate_amount** — Discount applied at settlement, posted to `DISKON_PELUNASAN`. (DM §1.5/§1.12)
- **Future unrecognized interest** — Scheduled interest not yet billed. Never immediately receivable, including at settlement, except via explicit in-period accrual. (DM §1.4, PRD)

## Ledger

- **JournalEntry** — One balanced, immutable double-entry record per financial event, identified by `(ref_type, ref_id)`, with `entry_date` (business date, Asia/Jakarta) and `posted_at` (clock time). (ADR-008, DM §1.13)
- **JournalLine** — An immutable debit or credit line of a JournalEntry; `total debit = total credit` always. Carries `contract_id` for reconciliation. (DM §1.13)
- **Reversal** — The only correction mechanism for posted ledger records: a new entry marked `reversal_of_id`, reusing the original `(ref_type, ref_id)`. Posted history is never UPDATEd or DELETEd. (DM §1.13)
- **LedgerRefType** — The financial event kind: `CONTRACT_ACTIVATION | PAYMENT | BILLING | PENALTY_ACCRUAL | PENALTY_WAIVER | CREDIT_APPLICATION | SETTLEMENT | WRITE_OFF`. (DM §1.13)
- **Chart of accounts** — `KAS`, `PIUTANG_POKOK`, `PIUTANG_BUNGA`, `PIUTANG_DENDA`, `TITIPAN_NASABAH`, `PENDAPATAN_BUNGA`, `PENDAPATAN_DENDA`, `DISKON_PELUNASAN`, `PENDAPATAN_ADMIN`, `BIAYA_PENGHAPUSAN_PIUTANG`. (DM §1.12)

## Interest schemes

- **FLAT** — Interest computed on the original principal for every period. (PRD, DM §1.3)
- **EFFECTIVE** — Annuity scheme: interest on the declining outstanding principal. (PRD, DM §1.3)

## Operational

- **Business clock** — All business timestamps come from the injected `Clock` in zone Asia/Jakarta. Business code never calls `LocalDate.now()`/`Instant.now()`; clients cannot backdate or future-date. (tech conventions)
- **Idempotency-Key** — Endpoint-scoped header required on retryable mutations (create Contract, create Payment, settlement). Claimed with `INSERT … ON CONFLICT DO NOTHING`; replays return the stored masked response. (ADR-007, DM §1.15)
- **Daily servicing job** — The nightly job running billing → penalty accrual → aging. Each step records a `job_run` row per invocation; the `SYSTEM` user is the actor. (ADR-013, DM §1.15)
- **Write-off** — Recording an external decision to stop collecting: `ACTIVE → TERMINATED`, caps the receivable, posts `BIAYA_PENGHAPUSAN_PIUTANG`. Never hides history. (DM §1.3/§1.12)
- **app_user / SYSTEM** — The actor behind audit columns is the JWT `sub` (an `app_user` UUID) or the seeded `SYSTEM` user for jobs. `SYSTEM` never acts over HTTP. (ADR-015, DM §1.15)
