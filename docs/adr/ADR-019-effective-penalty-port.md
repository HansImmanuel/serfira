# ADR-019: Effective-Penalty Ownership via a `penalty`-Module Port (E5, Sprint 5 T15)

- **Status:** Accepted
- **Date:** 2026-10-05
- **Deciders:** Hans (solo developer)

---

## Context

"Effective penalty" is the penalty a customer actually owes on an installment:

```
effectivePenalty = grossAccruedPenalty − activePenaltyAllocation − penaltyAdjustment
```

where `grossAccruedPenalty` is `installment.penalty_amount` (raised by accrual), `activePenaltyAllocation`
is the penalty already paid, and `penaltyAdjustment` is `Σ penalty_adjustment.amount` for waivers/reductions
(`04_GAPS_ADDENDUM.md §16.4`, invariant 9).

Today this formula is computed in **two** places, and the `penalty_adjustment` term is read in a way the
architecture explicitly calls a temporary seam (confirmed by the Sprint 5 investigation,
`.agents/tasks/sprint5-settlement/ledger-conventions.md`):

1. The V3 cap trigger `assert_payment_allocation_component_caps()` subtracts
   `SELECT COALESCE(SUM(amount),0) FROM penalty_adjustment WHERE installment_id = …` at the DB level.
2. `InstallmentRepository.sumPenaltyAdjustmentsByInstallmentIds(...)` — a **native** two-column projection
   in the `contract` module, surfaced into `InstallmentReceivable.penaltyAdjustments`. Its Javadoc states:
   *"there is deliberately no PenaltyAdjustment entity in this module"* — the `contract` module reaches
   straight into a `penalty`-owned table because the `penalty` module did not yet expose it. This is the
   ADR-010 temporary cross-module seam.

The `penalty_adjustment` **table** already exists (V1) with its CHECK constraints
(`adjustment_type ∈ {WAIVE, REDUCE}`, `amount > 0`) and an immutability trigger, but there is **no JPA
entity, no write path, and no port** — nothing inserts an adjustment yet. Story E5 (T15) introduces
waive/reduce, which is the first writer. That is the moment to resolve the seam: a waiver must produce an
adjustment row **and** a correcting journal, and settlement (T12/T13, ADR-018) now also needs effective
penalty — so the formula is about to have a **fourth** consumer (contract, payment-cap, settlement,
reporting), multiplying the native-read seam if left as is.

The module rules (`structure` steering, ADR-001) are strict: a module must never read another module's
tables; cross-module needs go through a port interface owned by the **data-owning** module. `penalty_adjustment`
is penalty-owned. The current native read in `contract` violates that rule as an accepted temporary measure
(ADR-010). Review finding CR-14 asked for "one outstanding formula for payment snapshot, contract totals,
settlement and reporting."

---

## Decision

### D1 — The `penalty` module owns effective penalty and exposes it through a port

Introduce `com.serfira.penalty.application.EffectivePenaltyPort` with a snapshot method, e.g.:

```java
EffectivePenaltySnapshot loadEffectivePenalty(UUID contractId);
// snapshot: contractId + List<InstallmentEffectivePenalty(installmentId, grossAccrued, adjustment, effective)>
```

implemented by an `EffectivePenaltyService` in `penalty`. Consumers (`contract` totals, settlement quote
in the new `settlement` module, reporting) read effective penalty through this port rather than reading
`penalty_adjustment` directly. The payment-cap DB trigger (V3) stays as the DB backstop — the port is the
application-level single source; the trigger is defence in depth, the same two-layer pattern used for
balance and allocation totals.

### D2 — `PenaltyAdjustment` becomes a `penalty`-module JPA entity with the waive/reduce write path

T15 adds the `PenaltyAdjustment` entity (extends `Auditable` — the table has `updated_at NOT NULL`, per
ADR-008's correction), a `PenaltyAdjustmentService` that appends an adjustment (append-only; no update/
delete, enforced by the V1 immutability trigger), and the `POST /penalty-adjustments` endpoint
(ADMIN_OPERASIONAL only, `§3.4`). The entity lives in `penalty.domain`; the native projection in
`contract.InstallmentRepository` is **removed** and replaced by a call through the port.

### D3 — A waiver posts a correcting journal

Waiving or reducing recognized penalty removes a receivable the customer no longer owes:

```
Dr DISKON_PELUNASAN (or a dedicated penalty-waiver expense)   Cr PIUTANG_DENDA
```

for the adjusted amount, `ref_type = PENALTY_WAIVER`, `ref_id = penalty_adjustment.id`. The journal
reverses recognized penalty **receivable**, never the accrual history (accrual rows stay append-only; the
adjustment is a separate append-only record). The exact expense account (reuse `DISKON_PELUNASAN` vs. a
new `BEBAN_WAIVER_DENDA`) is pinned in the T15 task after confirming the COA; this ADR fixes the
**shape** (`Cr PIUTANG_DENDA`, append-only, `PENALTY_WAIVER` ref), not the final expense code. The
`uq_journal_entry_event` index may need `PENALTY_WAIVER` added if one-entry-per-adjustment is enforced at
the DB level (decided in T15 alongside the ADR-018 `SETTLEMENT` extension).

### D4 — One formula, four consumers

After T15 the effective-penalty formula exists in exactly one application-level place
(`EffectivePenaltyService`) plus the DB backstop trigger. `contract` totals, the `settlement` quote, the
payment cap (via the trigger), and reporting all agree by construction. This closes CR-14. **T30** completed
D4 by routing the last two `contract` read paths (the schedule response and the aging report) through the
port, so every consumer now reports the same effective penalty (see the implementation note below).

---

## Alternatives Considered

- **Keep the native `penalty_adjustment` read in `contract`.** Rejected: it violates the module boundary
  (ADR-001), was always labelled temporary (ADR-010), and E5 + settlement would multiply the violation to
  four readers. T15 is the natural point to pay it down because it is the first writer anyway.
- **Put effective penalty in `contract` (where the receivable snapshot lives).** Rejected: `penalty_adjustment`
  is penalty-owned data; the owning module must expose it, or every cross-module rule inverts.
- **Compute effective penalty only in the DB trigger and have the app re-query raw rows.** Rejected: the
  app needs the value for pricing (settlement), response DTOs, and reporting — not just the write-time cap.
  A single application port with the trigger as backstop matches the established two-layer invariant pattern.
- **Defer the port and let settlement read `penalty_adjustment` natively too.** Rejected: it would add a
  third module reaching into a penalty table right as we are building the penalty writer, entrenching the
  seam instead of removing it.

---

## Consequences

- T15 grows slightly beyond "add waive/reduce": it also introduces the entity, the port, and the removal
  of the `contract` native read — but this is strictly smaller than the original stub implied (the table
  already exists; no `CREATE TABLE`). Net: `V15` is a thin migration (COA touch-up if a new waiver expense
  account is chosen, and possibly the `PENALTY_WAIVER` index extension), not a table creation.
- `contract.InstallmentReceivable.penaltyAdjustments` is sourced through the port after T15; the receivable
  snapshot shape is unchanged for its own consumers.
- Settlement (ADR-018 D8) depends on this port for `penalty_outstanding`; T15 therefore sequences **before**
  T12/T13, matching the Sprint 5 order T14 → T15 → T12 → T13.
- The DB cap trigger is unchanged and keeps enforcing the same formula independently, so there is no window
  where the app and DB disagree.

---

## References

- `03_DOMAIN_MODEL.md` §1.9 (penalty accrual), invariant 9
- `04_GAPS_ADDENDUM.md` §16.4 (penalty waiver audit), §3.4 (role matrix)
- ADR-001 (modular monolith; module boundary rules), ADR-010 (payment↔contract seam; the temporary native
  read), ADR-012 (penalty accrual semantics), ADR-018 (settlement; consumer of this port)
- Review finding CR-14 (one outstanding penalty formula across consumers)
- Investigation: `.agents/tasks/sprint5-settlement/ledger-conventions.md`
- Tasks: `docs/tasks.md` T15 (E5 waive/reduce)

---

## Implementation note (PR #7 review fixes)

The initial T15 implementation shipped effective penalty as `max(0, gross − adjustment)`, dropping the
`− activePenaltyAllocation` term from the Context formula. The PR #7 review (findings 2–5) corrected it:

- **F2 (money-balances):** `EffectivePenaltyService.effective(...)` now subtracts Σ POSTED `PENALTY`
  payment allocations too, so the remaining-penalty cap is `max(0, gross − Σ adjustment − Σ paid penalty)`.
  `penalty` reads the paid term through the new `payment.application.PenaltyAllocationPort`
  (`payment` owns `payment_allocation`). Migration **V16** replaces `assert_penalty_adjustment_cap()` so the
  DB cap subtracts the same `Σ PENALTY payment_allocation` term and stays numerically identical to the V3
  PENALTY cap; V16 supersedes V15's gross-only cap (V15 is left applied/unedited, forward-only).
- **F3/F4:** a waiver now recomputes the installment's resolution status from the adjustment-aware balance
  and applies the "all installments PAID → close MATURITY" rule, through the new
  `contract.application.InstallmentStatusRecomputePort`. That versioned installment write serializes
  concurrent waivers (optimistic lock), and `PenaltyAdjustmentRetryingService` retries the collision outside
  the transaction (mirroring `ContractCreditRetryingService`), so exactly one of two racing over-remaining
  waivers commits.
- **F5 (doc):** the `PenaltyAdjustmentService` Javadoc/flush comment were corrected — the deferred cap and
  journal-balance triggers fire at COMMIT, not on `flush`; the mapped 409 comes from the in-memory pre-check.
- **F1 (D4 follow-up), resolved by T30:** the schedule response (`InstallmentResponse`) and the aging report
  (`AgingReportSourceService`) had still read gross penalty via the zero-adjustment
  `InstallmentBalance.of(...)`. T30 routed both through `EffectivePenaltyPort`: `InstallmentResponse` gained
  an adjustment-aware overload `from(Installment, Σ adjustment)` and reports the effective (remaining)
  penalty on `penalty_amount` (wire shape unchanged); `ContractQueryService.installments(...)` threads the
  per-installment Σ adjustment for ACTIVE contracts (and uses an empty map for non-ACTIVE ones, since the
  port answers only for ACTIVE contracts, keeping the schedule endpoint 200 for DRAFT/closed contracts); and
  `AgingReportSourceService` nets the Σ adjustment into `outstanding`, querying the port once per ACTIVE
  contract. No second formula and no native `penalty_adjustment` read were introduced, and the `contract →
  penalty` edge is unchanged (T30 only added in-module consumers). This completes D4.
