# ADR-026: Reporting Metric Definitions — Outstanding, AR, NPL, Collection Rate (Sprint 8 planning)

- **Status:** Accepted (definitions); implementation is T36
- **Date:** 2026-11-26
- **Deciders:** Hans (solo developer)
- **Relates to:** PRD R-1 and §5A definitions, `04_GAPS_ADDENDUM.md` §14, ADR-013 (A-4 DPD, A-6 `as_of`,
  A-7 per-installment buckets), ADR-019 (effective penalty port), ADR-020 (void), ADR-018 (settlement),
  ADR-024 (exact decimal wire format), `06_FRONTEND_SPEC.md` §2.10.

---

## Context

PRD R-1 requires dashboard metrics "dengan definisi matematis tertulis di spec". PRD §5A and Addendum §14
give one-line definitions, which leave open questions that change the numbers:

- **NPL.** "Outstanding kontrak dengan DPD > 90" can mean the whole contract's outstanding, or only the
  part of it that sits in the >90 aging bucket.
- **Collection rate.** The definition doesn't say which period "periode ini" covers, whether the numerator
  and denominator share an installment cohort, or how credit applications are treated. A credit
  application resolves an installment through the same mutator as a payment
  (`InstallmentReceivablePort.applyPaymentResolution`), so it raises `installment.paid_amount`.
- **AR.** `06_FRONTEND_SPEC.md` §2.10 labelled it "overdue receivable", while PRD §5A and Addendum §14
  define it as outstanding on installments that are *due* on the as-of date. An installment can be due
  but still inside its grace period, and so not yet overdue.

The aging report (T8, ADR-013 A-7) already computes per-installment outstanding and DPD through the
`contract` module's formulas (`InstallmentBalance`, `InstallmentAging`). These definitions must reuse
those formulas, not add a second one.

These decisions were taken at Sprint 8 planning (2026-11-26) by the domain owner.

---

## Decision

### D1 — Common rules

- `as_of` is `clock.today()` only. Any other value is `400 INVALID_AS_OF_DATE`, as for the aging report
  (ADR-013 A-6). Business dates are application-clock dates (`Asia/Jakarta`).
- Per-installment `outstanding` is `InstallmentBalance.outstanding` with penalty net of active
  adjustments (ADR-019): `recognized_total − (paid + settled + written_off)`. Future unrecognized interest
  is never part of any metric, by construction.
- DPD is the GLOSSARY / ADR-013 A-4 definition, `max(0, as_of − due_date − grace_period_days)`.
- **Stock metrics** (D2–D4) cover **ACTIVE contracts only**, the same scope as the aging report.
  **Period metrics** (D5–D6) cover every non-DRAFT contract (ACTIVE, CLOSED, TERMINATED), so a contract
  that matured or was written off during the period still counts.

### D2 — Outstanding

`total_outstanding = Σ outstanding` over every installment of every ACTIVE contract. It equals the aging
report's portfolio `total_outstanding` exactly. It includes principal that is not yet due, matching the
PRD §5A "principal residual" term.

### D3 — AR (accounts receivable due)

`accounts_receivable = Σ outstanding` over installments of ACTIVE contracts with `due_date ≤ as_of`. This
includes installments that are due but still inside the grace period (DPD 0). AR is not "overdue
receivable": overdue is the subset with DPD ≥ 1. `AR ≤ total_outstanding`.

### D4 — NPL (whole-contract)

- A contract is **NPL** on `as_of` when it is ACTIVE and at least one of its installments has
  `outstanding > 0` and `DPD > 90`.
- `npl_outstanding = Σ outstanding` over **every** installment of every NPL contract. That includes its
  current and future-due principal, not only the >90 bucket.
- `npl_ratio = npl_outstanding / total_outstanding`.

The definition is the aging report's identity, so the two reports always agree: a contract is NPL exactly
when its aging row has `dpd_over_90 > 0`, and `npl_outstanding` is the sum of those rows'
`total_outstanding`.

### D5 — Collection rate (calendar month to date, due-date cohort)

- **Period:** `[first day of as_of's month, as_of]`, inclusive.
- **Cohort:** installments whose `due_date` falls in the period, on non-DRAFT contracts. The numerator and
  denominator use the same cohort.
- **Numerator (`collected`):** Σ `payment_allocation.amount` with `allocation_type ∈ {PENALTY, INTEREST,
  PRINCIPAL}` on cohort installments, where the payment is `POSTED`. Read it from the allocations, never
  from `installment.paid_amount`, because that also includes credit applications. Allocations of `VOIDED`
  payments are excluded (ADR-020). The cohort decides inclusion, not the payment date: cash paid early,
  before the period, for an installment due in the period is counted.
- **Excluded from the numerator:**
  - `EXCESS` allocations, which go to credit, not to an installment.
  - Credit applications. They are non-cash, and the cash behind them was already received as an excess.
  - Settlement allocations.
  - Write-offs and penalty adjustments.
- **Denominator (`due`):** Σ over the cohort of `recognized_total − settled_amount`. Penalty is counted
  net of active adjustments (ADR-019). Settlement is taken out of both sides (PRD §5A: "Settlement tidak
  masuk numerator/denominator"). Written-off amounts stay in the denominator: they were due and were not
  collected.
- `collection_rate = collected / due`. Because `collected + settled ≤ recognized_total` on each
  installment, `0 ≤ collection_rate ≤ 1`.
- Accepted consequence: an installment that is fully resolved by a credit application stays in the
  denominator but adds nothing to the numerator. That is deliberate. The cash was received as an excess on
  an earlier payment, not as a collection on this installment.

### D6 — Settlement cash (separate metric)

`settlement_cash = Σ settlement.cash_received` for settlements whose `executed_at` business date falls in
the D5 period. `credit_used` is not cash and is excluded. Settlement cash is never part of the collection
rate.

### D7 — Wire format and undefined ratios

- Money is an exact decimal string (ADR-024).
- Ratios (`npl_ratio`, `collection_rate`) are decimal-string **fractions at scale 4, rounded HALF_UP**,
  the same convention as stored rates (`"0.0150"` = 1.5%).
- A ratio whose denominator is zero is JSON `null`, never `0` or `1`. The browser formats percentages with
  `lib/decimal.ts`, never with `Number`.

### D8 — Ownership

- `reporting` computes and aggregates.
- The inputs come through read-only ports owned by the modules that own the data:
  - `contract`: installment outstanding, DPD, due date, and cohort receivable.
  - `payment`: posted non-EXCESS allocations.
  - `settlement`: cash received.
- This follows `02_TECH_SPEC.md` §1 and ADR-013 A-7. `reporting` never re-derives a formula it can
  consume.

---

## Consequences

**Positive**

- R-1's metrics have one written definition. The aging report and the outstanding report cannot disagree
  on total outstanding or NPL.
- Collection rate is bounded to [0, 1] and cannot be inflated by settlements, credit recycling or voided
  cash.

**Negative / trade-offs**

- NPL is conservative. A single installment more than 90 days late puts the whole contract, including its
  undue principal, into the numerator. That matches contract-level collectibility reporting, but it is a
  larger figure than the >90 aging bucket.
- A month-to-date cohort on the first days of a month is small and volatile. A zero cohort shows `null`.
- No historical `as_of` (ADR-013 A-6 stands), so trends need a snapshot store. That is out of scope.

---

## Alternatives Considered

| Option | Reason rejected |
|---|---|
| NPL numerator = the >90 aging bucket only | Understates exposure: the rest of a non-performing contract is unlikely to be collected on schedule. It would also just repeat the aging report's last column. |
| Collection rate on a cash basis (all cash received in the period ÷ cohort due) | The numerator and denominator would cover different installments, so prepayments and arrears collections would push the rate above 1 or move it around. |
| Count credit applications as collections | Counts the same cash twice: once when it arrived as an excess, again when it is applied. |
| Read the numerator from `installment.paid_amount` | `paid_amount` includes credit applications, which breaks the previous rule. |
| Include settlement in the collection rate | PRD §5A excludes it explicitly; it is shown as a separate metric. |
| Collection rate over the last 30 days | The frontend spec says "periode ini", and a calendar month is how the business reads it. |
