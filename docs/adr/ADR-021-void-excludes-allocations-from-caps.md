# ADR-021: Payment-Allocation Cap Triggers Exclude VOIDED Payments (T31)

- **Status:** Accepted
- **Date:** 2026-11-05
- **Deciders:** Hans (solo developer)
- **Relates to:** ADR-020 (payment void semantics; corrects the cap-trigger side of D1), ADR-002
  (append-only ledger), ADR-009 (allocation waterfall / allocation risk note), ADR-019
  (effective-penalty formula). Mirrors the DB backstop onto the POSTED-only application ports.

---

## Context

ADR-020 D1 decided that voiding a payment keeps its `payment_allocation` rows in the table as
immutable history; the VOIDED status on the parent `payment` is the only marker that those rows no
longer count toward live receivable, and all reads are expected to net voided payments **by status**,
not by deleting rows.

Two DEFERRABLE INITIALLY DEFERRED constraint-trigger functions did not follow that rule:

- `V3__deferred_accounting_invariants.sql` → `assert_payment_allocation_component_caps()` sums
  `payment_allocation` per installment (PRINCIPAL / INTEREST / PENALTY) **without** joining the
  owning payment or filtering its status.
- `V16__penalty_adjustment_cap_includes_paid.sql` → `assert_penalty_adjustment_cap()` sums the
  PENALTY `payment_allocation` for the installment the same way. Its own comment asserted parity with
  the POSTED-only application port "because void is T16, so this equals the POSTED-only application
  port today" — a claim that became **false** once void (T16, ADR-020) went live.

Because a VOIDED payment's immutable allocation rows still counted against the per-installment caps, a
legitimate re-pay of an installment whose earlier payment had been voided summed the voided rows plus
the fresh rows and tripped the deferred cap trigger at COMMIT, surfacing as HTTP 500. This is a latent
bug predating T31; it was exposed when the T31 integration tests were first run with Docker
(`VoidIdempotencyKeyDurabilityIT.aFreshVoidKeyOnAnAlreadyVoidedPaymentStaysProtectedAfterCleanup`).

The application layer was already correct:
`PaymentAllocationRepository.sumActiveByInstallmentIds` filters `where a.payment.status = :status`
and is only ever called with `PaymentStatus.POSTED`. These DB cap triggers were the only stale
readers of allocation rows.

---

## Decision

Both cap trigger functions join `payment_allocation → payment` and count only `status = 'POSTED'`
rows, making each cap numerically identical to
`PaymentAllocationRepository.sumActiveByInstallmentIds(PaymentStatus.POSTED, ...)`. `PaymentStatus`
persists `@Enumerated(STRING)`, so the SQL literal is `'POSTED'`.

The change ships as a new forward-only migration `V23__void_excludes_allocations_from_caps.sql` that
redefines both functions in place with `CREATE OR REPLACE FUNCTION` — exactly how V16 superseded V15.
V3 and V16 are applied migrations and are never edited. The existing DEFERRABLE INITIALLY DEFERRED
triggers (`trg_payment_allocation_caps_deferred`, `trg_penalty_adjustment_cap_deferred`) stay wired to
the same function names; only the function bodies change, so `ddl-auto=validate` is unaffected (no
column or entity change). Every `RAISE EXCEPTION` message and `NUMERIC(19,2)` local is preserved, so a
genuine over-allocation of POSTED rows still surfaces exactly as before.

`assert_payment_allocation_totals()` (V3, per-payment Σ allocations = `payment.amount`) is left
unchanged: it is keyed on `payment_id` and is correct for a voided payment, whose rows still total its
amount.

---

## Alternatives Considered

- **Re-derive or clear `recognized_interest_amount`, or delete the allocation rows, on void.**
  Rejected: violates ADR-002 (append-only ledger) and the V1 allocation immutability trigger,
  destroys audit history, and directly contradicts ADR-020 D1 (void keeps the rows and only flips the
  parent's status).
- **Relax the cap triggers to a tolerance (`>=`, epsilon).** Rejected: this would mask real
  over-allocation of POSTED rows, the exact invariant the caps exist to enforce (DM §3 invariant 3).
- **Drop the DB cap triggers and rely on the application port alone.** Rejected: the DB backstop
  exists precisely because the child allocation rows are immutable (V3 rationale) — the backstop must
  be made correct, not removed.

---

## Consequences

- **Migration V23** redefines `assert_payment_allocation_component_caps()` and
  `assert_penalty_adjustment_cap()` via `CREATE OR REPLACE FUNCTION`; forward-only and applies cleanly
  from an empty database (`payment` and `payment_allocation` always exist before V23).
- **V16's "void is T16, so this equals the POSTED-only application port today" comment is now false**
  and is explicitly superseded by V23's comments and this ADR.
- **No new error code, no entity/DTO change, no module-boundary change.** The behavioural change is
  limited to excluding VOIDED allocations from the two caps.
- **Tests:** `PaymentVoidIT` gains `repayingAnInstallmentAfterVoidingItsPaymentSucceeds` (pay → void →
  re-pay the same installment in full succeeds), and the existing
  `VoidIdempotencyKeyDurabilityIT.aFreshVoidKeyOnAnAlreadyVoidedPaymentStaysProtectedAfterCleanup`
  (the original 500 repro) now passes. A dedicated penalty-path regression is deliberately skipped:
  `PaymentVoidIT` has no overdue/penalty fixture and building accrued penalty + a waiver needs heavy
  scaffolding; the penalty cap's status filter is the same V23 change and is covered by the shared
  `assert_penalty_adjustment_cap()` plus the durability case.
- **ADR-020** gains a Consequences bullet pointing here as the correction of the cap-trigger side of
  void semantics.
