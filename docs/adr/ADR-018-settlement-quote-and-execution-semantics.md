# ADR-018: Settlement Quote & Execution Semantics (E1/E2, Sprint 5 T12/T13)

- **Status:** Accepted
- **Date:** 2026-10-05
- **Deciders:** Hans (solo developer)

---

## Context

Sprint 5 builds early settlement (*pelunasan dipercepat*): a customer closes an ACTIVE contract
before maturity by paying what they owe today, minus a rebate on interest they will never be charged,
plus a flat admin fee. Story E1 produces an immutable **quote** (a priced snapshot with a TTL); story
E2 **executes** a quote into an immutable `settlement` transaction that closes the contract.

The financial rules were decided across the specs and must not be re-litigated here:

- **Components** (`04_GAPS_ADDENDUM.md §16.3`): a quote prices all outstanding principal (including
  future periods), interest already billed but unpaid, running interest of the active period
  (ACT/30, capped at 30 days, `§8`), outstanding penalty, **minus** the eligible rebate, **plus** the
  admin fee.
- **Config** (`§1`, seeded `system_parameter`): `SETTLEMENT_ADMIN_FEE = 150000`,
  `SETTLEMENT_REBATE_RATE = 0.5000`, `SETTLEMENT_QUOTE_TTL_MINUTES = 15`.
- **Quote lifecycle** (`§13`, `DM §1.5`): component snapshot immutable, status moves
  `QUOTED → EXECUTED/EXPIRED`, snapshots `contract.version`; execution recalculates current
  components and rejects on a changed snapshot or expiry. `settlement` uses `Idempotency-Key`; an
  `EXECUTED` quote is never executed again.
- **Credit** (`§13`, `§16.3`): settlement may explicitly consume `AVAILABLE` customer credit to reduce
  cash due; **all** AVAILABLE credit must be consumed; if credit exceeds the gross amount the
  settlement is rejected (refund is out of MVP).
- **Interest recognition** (`PRD §5A`, `ADR-011`): future scheduled interest is **never** a receivable
  until billed on its due date.

What the specs did **not** pin, and what this ADR decides, is the **accounting treatment** — the exact
debit/credit lines of the settlement journal. This matters because of a trap the ADR-018 planning
investigation (`.agents/tasks/sprint5-settlement/ledger-conventions.md`) confirmed:

> Future scheduled interest has **never** been booked to `PIUTANG_BUNGA` / `PENDAPATAN_BUNGA`. Billing
> (`InstallmentBillingService`) debits `PIUTANG_BUNGA` and credits `PENDAPATAN_BUNGA` only for interest
> recognized on its due date. So at settlement there is **no `PIUTANG_BUNGA` receivable for future
> periods to clear**, and the rebate must **not** be booked as a contra against a receivable that does
> not exist. Doing either would create a phantom receivable/contra pair and double-count.

The chart of accounts is already complete for settlement (seeded V1): `KAS` (ASSET),
`PIUTANG_POKOK/BUNGA/DENDA` (ASSET), `TITIPAN_NASABAH` (LIABILITY), `PENDAPATAN_BUNGA/DENDA/ADMIN`
(INCOME), `DISKON_PELUNASAN` and `BIAYA_PENGHAPUSAN_PIUTANG` (EXPENSE). `LedgerRefType.SETTLEMENT`
exists, and the `settlement*`/`contract_credit*` tables exist in full since V1 (immutability in V5), so
**no new table is needed** — only the behaviour, the Java module, and one index extension.

The `settlement_quote` table already stores the component split this ADR produces:
`outstanding_principal`, `unpaid_billed_interest`, `accrued_interest`, `penalty_outstanding`,
`rebate_amount`, `admin_fee`, `available_credit`, `credit_used`, `gross_amount`, `cash_due`,
`contract_version`, `valid_until`, `status`.

---

## Decision

### D1 — Settlement posts exactly one balanced journal entry per settlement

`ref_type = SETTLEMENT`, `ref_id = settlement.id`, `entry_date = executed_at` (Asia/Jakarta). This
follows the existing one-entry-per-event convention (ADR-008 decision 2: an entry may carry *several
lines*). **There is no settlement void or settlement reversal in MVP**, so no settlement entry is ever
corrected: `settlement` and `settlement_allocation` are immutable (V5/V1), and exactly one non-reversal
`SETTLEMENT` entry exists per settlement, backed by the extended `uq_journal_entry_event` (T13).

> If a settlement reversal is ever introduced (a future void-settlement story), it would follow the
> general ledger convention — a new entry with `reversal_of_id` set, reusing the original
> `(SETTLEMENT, settlement.id)`. That is permitted *only* because `uq_journal_entry_event` is scoped
> `WHERE reversal_of_id IS NULL`, so a reversal does not collide with the original. This is noted for
> completeness; nothing in Sprint 5 posts such a reversal, and the one-non-reversal-entry guard in
> `LedgerPostingService` plus the extended index fully describe MVP behaviour.

### D2 — Future unrecognized interest is recognized directly as income, never as a cleared receivable

Define, per installment, over ACTIVE non-`SETTLED`/`WRITTEN_OFF` installments whose interest is not yet
fully billed:

```
futureInterestGross = Σ (interestAmount − recognizedInterestAmount)  −  accrued_interest
rebateAmount        = round(SETTLEMENT_REBATE_RATE × futureInterestGross, HALF_EVEN, 2)   // 50%
futureInterestCharged = futureInterestGross − rebateAmount
```

**Active-period boundary (resolves the D2/D7 overlap for mid-period quotes).** The earliest unbilled
installment — the "active period" of D7 — contributes its interest to the quote in *two* disjoint parts,
never both to the same bucket:

- the portion already **earned** by the settlement date is `accrued_interest` (D7), charged in full with
  **no** rebate (it is time already elapsed, not unearned);
- the **remaining** unrecognized interest of that same period is unearned and therefore rebate-eligible,
  so it belongs in `futureInterestGross`.

The `− accrued_interest` term above removes exactly the earned slice from the gross so the active period's
interest is counted once: `accrued_interest` (no rebate) plus its remainder (rebate-eligible). All fully
future periods (strictly after the active one) contribute their whole scheduled interest to
`futureInterestGross`, since none of it is earned yet. On a settlement that falls on a due date the active
period is already billed, so `accrued_interest = 0` and that period's interest sits entirely in
`unpaid_billed_interest` — it is not in `futureInterestGross` at all (its `interestAmount −
recognizedInterestAmount = 0` once billed). The engine computes `accrued_interest` first (D7), then
`futureInterestGross` net of it, so the two are disjoint by construction.

`futureInterestCharged` (the half the customer pays) is recognized at execution as **`Cr PENDAPATAN_BUNGA`**,
paired against the cash/credit that funds it. The rebated half is **simply never recognized** — there is
**no `DISKON_PELUNASAN` line** for it, because there was never a `PIUTANG_BUNGA` receivable to discount.
This matches the project's "recognize revenue when earned" convention used by billing (interest) and
penalty accrual.

### D3 — `DISKON_PELUNASAN` is reserved for forgiving an already-recognized receivable

In the standard early-settlement case nothing recognized is forgiven, so **no `DISKON_PELUNASAN` line
appears**. The account earns its place only when a settlement deliberately forgives an *already-billed*
interest or penalty receivable as a goodwill reduction (`Dr DISKON_PELUNASAN / Cr PIUTANG_*`). For
Sprint 5 MVP, settlement clears recognized receivables in full and uses `DISKON_PELUNASAN` only if such
a forgiveness is explicitly priced into the quote; the normal path never emits it. This keeps
`DISKON_PELUNASAN` meaning "a receivable we were owed and chose to waive," never "interest we never
earned."

### D4 — The full settlement journal

For a settlement that clears principal, billed interest, running interest, penalty, charges the net
future interest, consumes credit, and takes the admin fee, the single entry is:

| Line | Account | Dr | Cr | Meaning |
|------|---------|----|----|---------|
| 1 | `KAS` | cash_received | | cash the customer pays |
| 2 | `TITIPAN_NASABAH` | credit_used | | AVAILABLE credit consumed (if any) |
| 3 | `PIUTANG_POKOK` | | Σ principal resolved | clear principal receivable |
| 4 | `PIUTANG_BUNGA` | | Σ billed interest resolved | clear recognized interest receivable |
| 5 | `PIUTANG_DENDA` | | Σ effective penalty resolved | clear penalty receivable |
| 6 | `PENDAPATAN_BUNGA` | | accrued_interest + futureInterestCharged | income earned now (running + net future) |
| 7 | `PENDAPATAN_ADMIN` | | admin_fee | admin-fee income |

Balance holds because `cash_received + credit_used = gross_amount` and
`gross_amount = (principal + billedInterest + penalty cleared) + accrued_interest + futureInterestCharged + admin_fee`.
Zero components contribute no line (same rule as the payment journal). Running interest
(`accrued_interest`) and future net interest are both fresh income (`PENDAPATAN_BUNGA`), never a
`PIUTANG_BUNGA` clear, because neither was ever billed.

### D5 — `settlement_allocation` records only receivable resolution; income/fee/credit are journal-only

`settlement_allocation` rows (immutable, `allocation_type ∈ {PENALTY, INTEREST, PRINCIPAL}`, `amount > 0`)
record **which installment's recognized receivable** each settlement resolved — the analogue of
`payment_allocation`, oldest installment first, `PENALTY → INTEREST → PRINCIPAL`. They do **not** carry
future-interest income, the admin fee, the rebate, or credit consumption: those are journal lines and
quote columns, not per-installment receivable resolutions. Consumed credit is recorded in
`settlement_credit_application` (one row per source `contract_credit`). Because a settlement is never
voided or reversed in MVP (D1), `settlement_credit_application` is **append-only/immutable**: once a row
is written it is never updated or deleted (DB backstop `trg_settlement_credit_application_immutable` reuses
`block_modification()`, matching `settlement_allocation`; `03_DOMAIN_MODEL.md` invariant 14). This keeps
`settlement_allocation` meaning exactly "receivable cleared on installment X," consistent with the
`PENALTY/INTEREST/PRINCIPAL`-only CHECK already in V1.

### D6 — `gross_amount` / `cash_due` derivation stored in the quote

```
gross_amount = outstanding_principal
             + unpaid_billed_interest
             + accrued_interest
             + penalty_outstanding
             + futureInterestCharged          // = futureInterestGross − rebate_amount
             + admin_fee
cash_due     = gross_amount − credit_used
```

The quote stores `rebate_amount` and all named components; `futureInterestCharged` is reconstructible as
`(gross_amount − outstanding_principal − unpaid_billed_interest − accrued_interest − penalty_outstanding
− admin_fee)` and is asserted against `futureInterestGross − rebate_amount` in tests. (Chosen over adding
a `future_interest_charged` column: the schema is fixed from V1 and the value is derivable, so no
migration is spent on a redundant column.)

### D7 — Running interest only for the earliest unrecognized active period, zero once billed

`accrued_interest = round(monthlyInterest × min(daysElapsedInPeriod, 30) / 30, HALF_EVEN, 2)` for the
single earliest installment whose interest is not yet recognized **and** whose due date is in the future
relative to the settlement date (the "active period"). If that installment is already billed (settlement
date ≥ its due date), its interest sits in `unpaid_billed_interest` and `accrued_interest = 0` for it —
counting both would double-count (`§16.3`). ACT/30 (`§8`): one month = 30 days, elapsed days capped at 30.

The active period's *remaining* (unearned) interest is **not** in `accrued_interest`; it is rebate-eligible
and lands in `futureInterestGross` net of this `accrued_interest` slice (see D2's active-period boundary).
So the active period's scheduled interest is split once: earned slice here (full charge), unearned
remainder in D2 (rebate-eligible). The quote computes `accrued_interest` (this decision) before
`futureInterestGross` (D2) so the subtraction is well-defined.

### D8 — Effective penalty is read through the ADR-019 port

`penalty_outstanding` is the **effective** penalty (gross accrual − active allocation − adjustment),
read through `penalty.application.EffectivePenaltyPort` (ADR-019), not a native `penalty_adjustment`
read. Settlement must accrue-before-resolve: bill and accrue through the settlement date in the same
transaction before pricing, so the base each missing penalty day used is the base actually in force
(the accrue-before-resolve invariant, ADR-013; the parallel of what T4 did for payment).

### D9 — Execution revalidation

Execution re-prices the current components and compares to the quote snapshot. It rejects with:

- `409 STALE_SETTLEMENT_QUOTE` if `contract.version` differs from the snapshot, or any recomputed
  component differs from the stored snapshot;
- `409 SETTLEMENT_QUOTE_EXPIRED` if `clock.now() > valid_until`;
- `409 SETTLEMENT_QUOTE_ALREADY_EXECUTED` if the quote status is already `EXECUTED` (a different
  `Idempotency-Key` must not re-execute an executed quote, `§13`; `uk_settlement_quote_id` is the DB
  backstop — one settlement per quote).

Idempotent replay of the **same** `Idempotency-Key` returns the stored settlement response (ADR-017
single-use-forever; an expired key → `409 IDEMPOTENCY_KEY_EXPIRED`; `uq_settlement_idempotency` is the
permanent backstop).

### D10 — Credit-must-be-fully-consumed, settlement closes the contract

All `AVAILABLE` credit for the contract must be consumed by the settlement (`credit_used = available_credit`);
if `available_credit > gross_amount` the settlement is rejected `409 CREDIT_EXCEEDS_SETTLEMENT` (refund
out of MVP, `§13`). On success the contract closes `SETTLEMENT` and every open installment moves to
`SETTLED` with `settled_amount`/`settled_at` set — through the `contract` module's own path, never the
payment-resolution path (which only closes `MATURITY`).

---

## Worked golden example (Demo Contract B, `§18.2`)

EFFECTIVE, principal 150,000,000, 36 months, 0.75%/month, start 2026-01-15. Installments 1–6 PAID on
time (no penalty). Settlement is quoted and executed on **2026-07-15** (period 7's due date — so period
7 is *billed* that day; periods 8–36 are future/unrecognized). This case is deliberately clean (no
penalty, settlement on a due date so `accrued_interest = 0`) to make the balance obvious; the
implementation tests add mid-period and penalty variants.

Schedule facts (pinned by `EffectiveScheduleGoldenTest`): monthly annuity `A = 4,769,959.90`;
Σ principal = 150,000,000.00; Σ interest = 21,718,556.34. After 6 on-time payments, the outstanding
principal entering period 7 and the remaining scheduled interest for periods 7–36 are derived from the
amortization table (the implementation pins these from the engine, not by hand). For the ADR we show the
**shape** with symbolic component totals `P₇₋₃₆` (outstanding principal), `I₇` (period-7 billed
interest), `I₈₋₃₆` (future unrecognized interest periods 8–36):

```
outstanding_principal   = P₇₋₃₆
unpaid_billed_interest  = I₇                         (period 7 billed on 2026-07-15, unpaid)
accrued_interest        = 0                            (settlement on the due date, D7)
penalty_outstanding     = 0                            (no late periods)
futureInterestGross     = I₈₋₃₆
rebate_amount           = round(0.50 × I₈₋₃₆, HALF_EVEN, 2)
futureInterestCharged   = I₈₋₃₆ − rebate_amount
admin_fee               = 150,000.00
available_credit        = 0  → credit_used = 0
gross_amount            = P₇₋₃₆ + I₇ + (I₈₋₃₆ − rebate_amount) + 150,000.00
cash_due                = gross_amount
```

Journal (credit = 0, so no `TITIPAN_NASABAH` line):

| Account | Dr | Cr |
|---------|----|----|
| `KAS` | gross_amount | |
| `PIUTANG_POKOK` | | P₇₋₃₆ |
| `PIUTANG_BUNGA` | | I₇ |
| `PENDAPATAN_BUNGA` | | I₈₋₃₆ − rebate_amount |
| `PENDAPATAN_ADMIN` | | 150,000.00 |

Σ Dr = gross_amount = P₇₋₃₆ + I₇ + (I₈₋₃₆ − rebate_amount) + 150,000.00 = Σ Cr. **Balanced.** The rebated
half of `I₈₋₃₆` appears nowhere — it is interest the lender never earns, so it is never recognized. The
`settlement_allocation` rows for this settlement are: `PRINCIPAL` per installment summing to P₇₋₃₆, and
`INTEREST` on period 7 for I₇ (the only recognized interest cleared). Future interest and admin fee are
journal-only (D5). The implementation's `SettlementQuoteEngineTest` pins the real numeric
`P₇₋₃₆`, `I₇`, `I₈₋₃₆`, `rebate_amount` from the engine and `SettlementExecutionIT` asserts the posted
entry balances and the contract closes `SETTLEMENT`.

---

## Alternatives Considered

- **Book future interest gross then contra it with `DISKON_PELUNASAN`.** Rejected: it only balances if a
  matching gross `PIUTANG_BUNGA` receivable is first recognized, which would recognize interest that was
  never billed — exactly the double-count the investigation flagged, and a violation of "future interest
  is never a receivable" (PRD §5A). D2's direct recognition of only the charged half avoids a phantom
  receivable entirely.
- **A new `BUNGA_DITANGGUHKAN` (deferred/unearned interest) liability account.** Rejected: there is no
  such account in the COA, it would need a V-migration seed, and FLAT/EFFECTIVE schedules never booked
  unearned interest as a liability in the first place — so there is nothing to release. Prefer the
  existing accounts.
- **Several journal entries per settlement** (one per component). Rejected: ADR-008 decision 2 settles
  on one balanced entry per event with multiple lines; multiple entries would complicate reconciliation
  and the `(ref_type, ref_id)` guard for no benefit. (ADR-008 explicitly left SETTLEMENT free to choose;
  this ADR chooses one entry and extends `uq_journal_entry_event` accordingly — see D1 and T13.)
- **Add a `future_interest_charged` column to `settlement_quote`.** Rejected (D6): the value is derivable
  from the stored components and the schema is fixed from V1; a redundant column is not worth a migration
  and risks drift against `gross_amount`.
- **Reuse the payment-resolution path to close the contract.** Rejected (D10): that path closes only
  `MATURITY` and is explicitly documented not to touch SETTLED contracts; settlement must close through
  the contract module's own `SETTLEMENT` path so E2's decision is not overwritten.
- **100% rebate on future interest (charge nothing for unearned interest).** Rejected: contradicts
  `SETTLEMENT_REBATE_RATE = 0.5000` and `§16.3`'s "dikurangi rebate yang eligible" (a non-zero charge
  remains). The 50% rate is the product decision; D2 implements exactly it.

---

## Consequences

- **One ADR, two stories.** T12 (quote) implements the component engine (D2, D6, D7, D8) and persists an
  immutable `settlement_quote`; T13 (execution) implements D1, D4, D5, D9, D10 and the journal. Both read
  the effective penalty through ADR-019.
- **Migration is small.** Tables exist since V1. The only schema change settlement needs is extending
  `uq_journal_entry_event` (V12) to include `'SETTLEMENT'` so the one-entry-per-settlement rule has a DB
  backstop (drop + recreate the partial unique index in the T13 migration). ADR-008's T25 note explicitly
  asked for this decision at T13; D1 makes it.
- **New read seam on `contract`.** `InstallmentReceivable` exposes only `recognizedInterestAmount`; T12
  adds scheduled `interestAmount` to the settlement-facing snapshot (or a dedicated settlement port
  method) so `futureInterestGross` is computable. This is an ADR-010-style seam addition, recorded in the
  T12 task.
- **`KAS` positivity.** Like every cash-in event, settlement debits `KAS`; the demo seed (G5) provides the
  opening cash balance (ADR-008 decision 9). No change here.
- **Reporting.** Settlement cash is a separate metric from regular-payment collection (`§14`); the
  `PENDAPATAN_BUNGA` recognized at settlement is early-recognized interest income and will show in income
  reporting when that story lands. Flagged for T22, not built here.
- **Void interaction (T16, Sprint 6).** A paid installment later settled is `SETTLED`; the void story must
  account for settled installments (out of this ADR's scope).

---

## References

- `01_PRD.md` §5A (interest recognition), §5.? (settlement scenario)
- `02_TECH_SPEC.md` §3 (ledger + posting rules), §4.3 (penalty), §5
- `03_DOMAIN_MODEL.md` §1.5 (settlement_quote), §1.6 (settlement), §1.12 (COA), §1.13 (journal)
- `04_GAPS_ADDENDUM.md` §1 (config), §8 (ACT/30), §13 (settlement transaction & quote), §16.3 (component
  rules), §18.2 (Demo Contract B golden)
- ADR-008 (ledger posting semantics; one entry per event; T25 scoped `uq_journal_entry_event`),
  ADR-009 (allocation waterfall), ADR-011 (billing/recognition), ADR-013 (accrue-before-resolve),
  ADR-017 (idempotency single-use-forever), ADR-019 (EffectivePenaltyPort)
- Investigation: `.agents/tasks/sprint5-settlement/ledger-conventions.md`
- Tasks: `docs/tasks.md` T12 (E1 quote), T13 (E2 execution)
