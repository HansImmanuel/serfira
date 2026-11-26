# Glossary

Canonical vocabulary for Serfira. When naming a domain concept in code, issues, tests, or proposals, use the term exactly as defined here. Do not introduce synonyms.

Term names mirror the code and the specs (`docs/03_DOMAIN_MODEL.md`, `docs/02_TECH_SPEC.md`, `docs/01_PRD.md`, `docs/adr/`). Definitions are in English for agent consumption; the authoritative specs are in Bahasa Indonesia and win on any conflict.

Seeded from the domain model. Extend it lazily via `/domain-modeling` as new terms get resolved; don't pre-populate speculative entries.

## Core entities

- **Contract** — A financing agreement for one Customer and one Asset (`principal`, `down_payment`, `tenor_months`, `interest_scheme`, `interest_rate`). Lifecycle `DRAFT → ACTIVE → CLOSED | TERMINATED`. Business number `MF-YYYYMM-XXXX`. One Contract has exactly one Schedule. (DM §1.3)
- **Customer** — The borrower. PII (`nik`, `phone`) is AES-256-GCM encrypted at rest and looked up by HMAC hash columns (`nik_hash`, `phone_lookup`); never stored or logged in plaintext. (DM §1.1, ADR-004)
- **PII key ring** — The set of PII keys, each a key id (`kid`) with an AES-256 encryption key and an HMAC key. The **active** key seals new values (`v1:…` until `write-envelope=v2`, then `v2:<kid>:…`; ADR-025 D7) and signs new lookup hashes; the **legacy** key reads pre-ring `v1:` envelopes; lookups match **candidates**, the value's hash under every ring key, active first. The flat `encryption-key`/`hmac-key` pair is entry `k1`. (ADR-025)
- **Asset** — The financed good (`MOTORCYCLE`, `CAR`, `ELECTRONICS`, `OTHER`). De-duplicated by identity (`serial_no`, then `plate_no`); an asset with no identity cannot be de-duplicated. (DM §1.2, ADR-006)
- **Installment** — One scheduled monthly obligation of a Contract, keyed `(contract_id, period_no)` with a `due_date`. Carries `principal_amount`, scheduled `interest_amount`, `recognized_interest_amount`, `penalty_amount`, and the resolved amounts. Status `PENDING | PARTIALLY_PAID | PAID | OVERDUE | SETTLED | WRITTEN_OFF`. (DM §1.4)
- **Schedule** — The full set of Installments generated once at Contract activation. Activation is idempotent; a schedule is generated exactly once. (PRD, DM §1.3)
- **Payment** — Cash received against a Contract (`amount > 0`, a `channel`, `paid_at`). Status `POSTED | VOIDED`. A void is a reversal, never a delete: a VOIDED payment keeps its `amount` and its allocation rows intact, and live receivable is computed from POSTED payments only. Business number `PAY-YYYYMM-XXXX`. (DM §1.7, ADR-020)
- **PaymentAllocation** — An append-only row recording how a Payment was split across an Installment's components. `allocation_type` is `PENALTY | INTEREST | PRINCIPAL | EXCESS`. (DM §1.8)

## Money and recognition

- **principal** — The financed amount after down payment. Invariant: `asset_price = principal + down_payment`. (DM §1.3)
- **interest_amount** — The interest scheduled for an Installment period. Scheduled interest is *not* a receivable until billed. (DM §1.4)
- **recognized_interest_amount** — Interest already recognized as a receivable (0 until Billing on the due date; may include settlement accrued interest). Only recognized interest is allocatable or collectable. (DM §1.4)
- **Billing** — The act of recognizing due interest on an Installment whose `due_date` has been reached: one `BILLING` journal per Installment for `interest_amount − recognized_interest_amount`. `SETTLED`/`WRITTEN_OFF` installments are never billed. (ADR-011, DM §1.4)
- **penalty_base** — `max(0, principal_amount + recognized_interest_amount − paid_amount − settled_amount − written_off_amount)`. The unpaid principal+recognized-interest balance a daily penalty is charged on. Penalties do not compound on unpaid penalty. (ADR-012, DM §1.4)
- **effective_penalty** — `penalty_amount − active paid penalty allocations − penalty adjustments`. The currently collectable penalty. Owned by the `penalty` module and exposed through `EffectivePenaltyPort`; `contract` totals, settlement, the payment cap (DB trigger backstop), and reporting all read it through that one port rather than reading `penalty_adjustment` directly. (DM §1.4, ADR-019)
- **recognized_total** — `principal_amount + recognized_interest_amount + effective_penalty`. (DM §1.4)
- **resolved_amount** — `paid_amount + settled_amount + written_off_amount`. (DM §1.4)
- **outstanding** — `recognized_total − resolved_amount`. Prefer this precise term over the ambiguous word "balance". (DM §1.4)

## Allocation and credit

- **Allocation waterfall** — The order a Payment consumes an Installment: `PENALTY → INTEREST → PRINCIPAL`, then `EXCESS`. Oldest eligible Installment first (`due_date`, tie-break `period_no`). (ADR-009, DM §1.8)
- **Eligibility threshold** — Only Installments with `due_date <= payment business date` are allocatable. Future-dated Installments are never touched. (ADR-009)
- **EXCESS** — The single leftover allocation row (`installment_id IS NULL`) created when a Payment exceeds eligible obligations. Becomes customer credit. (DM §1.8)
- **TITIPAN_NASABAH** — The liability account holding customer credit from overpayment (literally "customer deposit"). Credit is never auto-applied to future Installments. (DM §1.12, PRD P-4)
- **ContractCredit** — A durable customer-credit balance (`AVAILABLE | APPLIED | REFUNDED`), booked by the `contract` module when a Payment produces an `EXCESS` PaymentAllocation (`uk_contract_credit_source`: one credit per EXCESS allocation). `amount` is immutable; only `status` transitions, flipping `AVAILABLE → APPLIED` exactly when the available balance reaches 0. The credit row is the liability sub-ledger for `TITIPAN_NASABAH`, not a second journal entry. Available balance = `amount − Σ application.amount`. Never auto-applied. (DM §1.11, Addendum §2.1, T14)
- **ContractCreditApplication** — A record applying some ContractCredit to an Installment, created by `POST /contracts/{id}/credit/apply`. Reuses the payment waterfall PENALTY → INTEREST → PRINCIPAL on the oldest due installment first, reducing only recognized receivable (never future unrecognized interest). One credit may fund many applications; the audit trail is never lost. The `Σ applications ≤ credit.amount` cap is a DB deferred trigger (V14, invariant 11). (DM §1.11, Addendum §2.2, T14)
- **Credit apply journal** — One balanced `journal_entry` per apply call: `Dr TITIPAN_NASABAH / Cr PIUTANG_DENDA|PIUTANG_BUNGA|PIUTANG_POKOK`, `ref_type = CREDIT_APPLICATION`, `ref_id` = the first ContractCreditApplication id of the call. Not in `uq_journal_entry_event`; the `LedgerPostingService` guard enforces one entry per `(ref_type, ref_id)`, like PAYMENT (ADR-008 d5). (Addendum §2.4, T14)

## Penalty and aging

- **PenaltyAccrual** — One row per late day beyond grace, keyed `(installment_id, accrual_date)`. `amount` is the incremental daily charge, not a cumulative snapshot. Journal `PENALTY_ACCRUAL`. (ADR-012, DM §1.9)
- **PenaltyAdjustment** — An immutable `WAIVE` or `REDUCE` of penalty with a required `reason` and approving actor. Corrections are new adjustments, never updates. (DM §1.10)
- **grace_period_days** — Days after `due_date` before penalty and OVERDUE begin. Snapshotted onto the Contract at activation. (DM §1.3)
- **DPD (Days Past Due)** — On business date `D`: `max(0, D − due_date − grace_period_days)`. Aging reports compute DPD directly from `due_date`, never from Installment status. (ADR-013, DM §1.4)
- **OVERDUE** — An Installment becomes OVERDUE on the first chargeable day, `today >= due_date + grace_period_days + 1`. Aging only moves *into* OVERDUE; exit is only via payment, settlement, or write-off. (ADR-013, DM §1.4)
- **Aging buckets** — Delinquency bands: Current, 1–30, 31–60, 61–90, >90 days. (PRD)

## Reporting metrics

- **AR (accounts receivable due)** — Outstanding on installments of ACTIVE contracts with `due_date ≤ as_of`, including those still inside grace (DPD 0). Not the same as overdue receivable, which is the DPD ≥ 1 subset. (ADR-026 D3)
- **NPL** — An ACTIVE contract with at least one installment where `outstanding > 0` and DPD > 90. NPL outstanding is the *whole* contract's outstanding, including undue principal, not only its >90 bucket. NPL % = NPL outstanding / total active outstanding. (ADR-026 D4)
- **Collection rate** — Month to date. Posted, non-voided, non-`EXCESS` payment allocations to installments due in `[1st of month, as_of]`, divided by those installments' recognized receivable net of `settled_amount`. Credit applications and settlement are not collections; settlement cash is reported separately. (ADR-026 D5/D6)

## Settlement

- **SettlementQuote** — An immutable snapshot of settlement components (`outstanding_principal`, `unpaid_billed_interest`, `accrued_interest`, `penalty_outstanding`, `rebate_amount`, `admin_fee`, `available_credit`, `credit_used`, `gross_amount`, `cash_due`) with a `valid_until` TTL and a `contract_version` for stale detection. Status `QUOTED | EXECUTED | EXPIRED`. Number `Q-YYYYMMDD-XXXX`. (DM §1.5)
- **Settlement** — Execution of a quote: validates expiry, Contract still ACTIVE, and matching `contract_version` and snapshot before posting. Number `SET-YYYYMM-XXXX`. Idempotent. (DM §1.6)
- **eligible interest** — The future unrecognized scheduled interest a settlement rebate is computed on: `Σ (interest_amount − recognized_interest_amount)` over not-yet-fully-billed installments. The interest the lender would never earn because the loan closes early. (ADR-018 D2, Addendum §16.3)
- **rebate_amount** — The settlement rebate: `round(SETTLEMENT_REBATE_RATE × eligible interest, HALF_EVEN, 2)` (50%). In the normal early-settlement path the rebate is *never recognized* as a ledger line — the lender simply does not recognize the forgiven half of future interest, so there is **no** `DISKON_PELUNASAN` entry for it. (ADR-018 D2)
- **future interest charged** — `eligible interest − rebate_amount`: the half of future interest the customer pays at settlement, recognized directly as `PENDAPATAN_BUNGA` income at execution (never as a cleared `PIUTANG_BUNGA` receivable, which was never booked). (ADR-018 D2)
- **DISKON_PELUNASAN** — The settlement-rebate expense account. Used *only* to forgive an **already-recognized** receivable (billed interest or penalty) as a goodwill reduction: `Dr DISKON_PELUNASAN / Cr PIUTANG_*`. It is never used for the future-interest rebate, which is an un-recognition, not an expense. (ADR-018 D3, DM §1.12)
- **Future unrecognized interest** — Scheduled interest not yet billed (`interest_amount − recognized_interest_amount`). Never a receivable, including at settlement. At settlement the charged half becomes `PENDAPATAN_BUNGA` income directly; it is never booked to `PIUTANG_BUNGA`. (DM §1.4, ADR-018 D2, PRD §5A)

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
- **Idempotency-Key** — Endpoint-scoped header required on retryable mutations (create Contract, create Payment, void Payment, apply credit, execute settlement, write off Contract, penalty adjustment). Claimed with `INSERT … ON CONFLICT DO NOTHING`; replays return the stored masked response. A key is single-use forever per endpoint: after its retention window a reuse is 409 `IDEMPOTENCY_KEY_EXPIRED`. (ADR-007, ADR-017, DM §1.15)
- **Daily servicing job** — The nightly job running billing → penalty accrual → aging → consistency check. Each step records a `job_run` row per invocation; the `SYSTEM` user is the actor. (ADR-013, DM §1.15)
- **Void** — Reversing a POSTED Payment: the Payment flips POSTED → VOIDED, one reversal JournalEntry mirrors the original with debit/credit swapped (`reversal_of_id` set), affected Installments' `paid_amount`/status are recomputed from the remaining POSTED payments, and the Payment's own EXCESS ContractCredit is reversed if still entirely AVAILABLE. Blocked (409) when that credit was partially/fully consumed (`PAYMENT_CREDIT_CONSUMED`) or any affected Installment is SETTLED/WRITTEN_OFF or its Contract is not ACTIVE (`PAYMENT_NOT_VOIDABLE`). Allocations and the original entry are never mutated or deleted; already-accrued penalty dates are never re-priced (A-3). (DM §1.7, ADR-020)
- **Outbox event** — A domain event (`PaymentReceived`, `PaymentVoided`, `ContractActivated`, `ContractClosed`, `ContractWrittenOff`, `SettlementExecuted`) written to `outbox_events` in the same transaction as the change it reports, only when the transition is performed. Its payload holds ids, numbers, amounts, enums and dates — never customer PII or free text. The event id is the row id. (ADR-027, TS §2.7)
- **Outbox publisher** — The ShedLock job (`outbox-publisher`) that claims `PENDING` outbox rows with `FOR UPDATE SKIP LOCKED`, hands each to the `OutboxSink` (today one structured log line), and marks it `SENT`; a refused event retries on later ticks and becomes `FAILED` at the retry ceiling. At-least-once: consumers deduplicate by event id. (ADR-027)
- **Write-off** — Recording an external decision to stop collecting: `ACTIVE → TERMINATED`, caps the receivable, posts `BIAYA_PENGHAPUSAN_PIUTANG`. Never hides history. (DM §1.3/§1.12)

- **Audit actor** — The actor behind audit columns is the JWT `sub` (an `AppUser` UUID) or the seeded `SYSTEM` user for jobs. `SYSTEM` never acts over HTTP. (ADR-015, DM §1.15)

## Authentication

- **AppUser** — A row in `app_user` (`id`, `username`, `password_hash`, `role`, `is_active`, lockout counters). The seeded `SYSTEM` user is inactive and has an unusable `{disabled}` hash, so it can never log in. (DM §1.15, ADR-022)
- **Login** - `POST /api/v1/auth/login` exchanges `{username, password}` for an access token and a refresh token. Every credential failure is the same generic 401 (no account enumeration); a saturated Argon2 verification semaphore is 503 `AUTHENTICATION_CAPACITY_EXCEEDED`, and a locked account is 423 `ACCOUNT_LOCKED`. (ADR-022 D3/D9/D12)
- **Access token** — Short-lived (15-minute) HS256 JWT. `sub` is the UUID of an **active** `AppUser` (checked every request), `roles` is a string array from `app_user.role`; a token whose subject is unknown/inactive is rejected with 401. (ADR-015, ADR-022 D4/D6)
- **Refresh token** - Opaque 128-bit random token, valid 7 days (sliding on each rotation), stored only as a SHA-256 hash in `refresh_token`. Returned in the response body. Single-use: `POST /api/v1/auth/refresh` revokes the presented row and links it to its replacement via `replaced_by_id`; a replayed, expired, logged-out or deactivated-account token is 401. (ADR-022 D5/D10/D11)
- **Logout** - `POST /api/v1/auth/logout` revokes the presented refresh token; idempotent (unknown or already-revoked tokens are a 200 no-op). (ADR-022 D11)
- **Brute-force lockout** - Five consecutive failed logins lock an `AppUser` for `PT15M` (both configurable via `serfira.auth.lockout.*`); the next attempt answers 423 `ACCOUNT_LOCKED` with the remaining minutes even with the correct password, and a success clears the counter. (ADR-022 D12)
- **Authentication capacity guard** — Per-instance `Semaphore` (`serfira.auth.argon2.max-concurrent-verifications`, default 2) bounding parallel Argon2 verifications; beyond it, login fails fast with 503 rather than queueing unbounded 16 MiB KDFs. (ADR-022 D9)
