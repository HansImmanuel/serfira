# ADR-020: Payment Void Semantics (E4 / T16)

- **Status:** Accepted
- **Date:** 2026-10-11
- **Deciders:** Hans (solo developer)
- **Relates to:** ADR-002 (append-only ledger), ADR-008 (ledger posting), ADR-009 (allocation
  waterfall), ADR-012 / ADR-013 (penalty base, A-3), ADR-017 (idempotency single-use-forever),
  ADR-018 (settlement). Resolves `tasks.md` A-3 for the void case and the ADR-017 "T16 must keep
  `uq_payment_idempotency` meaningful" consequence.

---

## Context

T16 (Epic E4) adds `POST /api/v1/payments/{id}/void`: an operator reverses a previously POSTED
payment. Everything the payment touched is append-only or immutable by prior decision, so "undo"
cannot mean "delete":

- `payment_allocation` rows are immutable (V1 trigger) and are summed by the V3
  `trg_payment_allocation_total_deferred` check keyed on `payment_id` **with no status filter**, so
  deleting or mutating them would break the Σ-allocations = amount invariant (DM invariant 6).
- Journal entries and lines are immutable (ADR-002); the only correction mechanism is a reversal
  entry (`reversal_of_id` set, same `(ref_type, ref_id)`).
- `penalty_accrual` is one row per `(installment_id, accrual_date)` forever (ADR-012 d7); ADR-013 §3
  (A-3) already decided those rows are never re-priced.
- A payment's EXCESS may have booked a `contract_credit` row (T14) that could since have been applied
  (T14 credit-apply) or consumed by a settlement (T13).
- A payment's installment may since have been SETTLED (ADR-018) or the contract closed.

ADR-013 settled the penalty side of void (A-3). ADR-017 flagged that void must keep
`uq_payment_idempotency` meaningful after the T18 cleanup job deletes claim rows. This ADR records
the remaining void decisions as one coherent set, so T16 can be implemented without re-deriving them.

---

## Decision

### D1 — Void is a reversal plus a status flip; allocations and the original entry are never touched

Voiding payment `P`:

1. transitions `P.status` POSTED → VOIDED (the only mutation to the `payment` row; `amount` and the
   allocation rows are untouched, preserving the V3 total invariant and historical integrity);
2. posts **one** reversal `journal_entry` whose `reversal_of_id` points at `P`'s original payment
   entry and whose lines mirror the original with debit/credit swapped (ADR-002, ADR-008);
3. recomputes each affected installment's `paid_amount` and resolution status **from the remaining
   POSTED payments** — i.e. excluding `P`'s allocations — **plus any customer credit still applied to
   that installment** (`contract_credit_application`, T14), through the void-specific
   `Installment.resetPaidAmountForVoid` mutator, so an installment correctly drops from PAID back to
   PARTIALLY*PAID / PENDING and a MATURITY auto-close is undone if `P` was what closed it. The surviving
   applied credit is added back because a void of a \_different* payment must not drop credit-funded
   resolution and wrongly inflate the outstanding balance (PR #11 review). Each installment's `paid_at`
   is re-stamped to the earliest instant among its surviving resolving payments (or the earliest
   surviving credit application when no POSTED payment resolves it, keeping
   `ck_installment_paid_coherence` satisfied), so voiding the earliest payment advances `paid_at` to the
   next surviving one instead of leaving the voided payment's instant behind (PR #11 review).

The `payment_allocation` rows of a VOIDED payment remain in the table; the VOIDED status on the
parent is the historical marker that they no longer count toward live receivable. Reads that compute
receivable net voided payments by status, not by deleting rows.

### D2 — No catch-up accrual in the void; A-3 preserved unchanged (intentional, not a violation)

A void **raises** an installment's `penalty_base` retroactively (the resolution that lowered it is
undone). ADR-013 §1's accrue-before-resolve invariant exists only for flows that **lower** the base
(a resolution must not under-recognize already-missed dates before the base drops). A void is the
mirror case, so there is nothing to "accrue before," and the void transaction does **not** run
catch-up accrual.

Per ADR-013 §3 (A-3): existing `penalty_accrual` rows are never re-priced; the next normal daily
servicing run accrues only dates that have **no** row yet, at the base in force when those rows are
created (now the higher post-void base). Dates already accrued at the lower base stay at that lower
base, so the void produces a permanent, **explicitly accepted** undercharge on those dates — the same
conservative trade-off ADR-012/ADR-013 already accepted (the system never charges more than it should
without a trail; it may charge less). This is documented as an intentional exception to a superficial
reading of accrue-before-resolve, **not** a violation of ADR-013.

### D3 — `uq_payment_idempotency` is widened to all payment statuses so a voided key stays spent

Under ADR-017 option A a key is single-use forever, backstopped by `uq_payment_idempotency`. That
index is partial on `status = 'POSTED'` today, so after T18 deletes the claim row a voided payment
(status VOIDED) would no longer be covered and its key could mint a second payment. T16's migration
drops the partial predicate and recreates `uq_payment_idempotency` as unique across **all** payment
statuses, so the key stays spent regardless of POSTED/VOIDED and regardless of whether the claim row
still exists.

The migration must be **data-preserving and fail-safe**: a preflight query detects any existing rows
that would violate the all-status unique index before it is created; if any conflict is found the
migration **fails** (it does not delete or rewrite payment history to force itself through). An empty
preflight is the expected state (today's partial index already prevents two POSTED payments per key;
void does not exist yet, so no VOIDED duplicates can exist).

### D4 — Void is blocked if the payment's credit was consumed; otherwise the credit is reversed atomically

If `P`'s EXCESS booked a `contract_credit` row and **any** of that credit has been applied (T14) or
consumed by a settlement (T13) — i.e. its available balance is less than its original amount — the
void is rejected `409 PAYMENT_CREDIT_CONSUMED` and writes nothing. Unwinding spent credit would
cascade into other posted events, out of MVP scope (invariant 16).

If the credit is still **entirely AVAILABLE**, the void reverses it inside the same transaction as the
rest of the void (D6). The consumed-check-then-reverse must be **concurrency-safe**: the void takes a
row lock on the `contract_credit` row (e.g. `SELECT … FOR UPDATE`) before checking available balance,
so a concurrent credit-apply or settlement cannot consume the credit between the eligibility check and
the reversal. A concurrent consumer either loses the race (void reverses first; the consumer then sees
no AVAILABLE credit) or wins it (void then sees the balance reduced and returns
`PAYMENT_CREDIT_CONSUMED`). The DB `contract_credit` cap/status guards (V14) remain the backstop.

### D5 — Void is blocked if any affected installment is SETTLED/WRITTEN_OFF, or the contract is closed for any reason other than MATURITY

The void guard is evaluated over **every** installment referenced by `P`'s immutable allocations, not
one assumed target. If any such installment is SETTLED or WRITTEN_OFF, the void is rejected
`409 PAYMENT_NOT_VOIDABLE` and writes nothing.

The contract-status half of this guard is **narrower than an "ACTIVE-only" rule**, so it does not
contradict D1.3: a void proceeds when the contract is **ACTIVE**, or **CLOSED with
`closed_reason = MATURITY`**. The latter is exactly the case D1.3 recovers — voiding the final payment
of a matured contract demotes an installment below PAID and reopens the contract to ACTIVE
(`Contract.reopenFromMaturity`). A contract closed for **SETTLEMENT**, or terminated by a write-off, is
rejected `409 PAYMENT_NOT_VOIDABLE`: T16 never reverses a settlement or write-off; reversing those
closures is a separate, out-of-scope story.

> **Correction (PR #11 review).** The original wording of D5 said "or `P`'s contract is no longer ACTIVE
> (closed SETTLEMENT / MATURITY / WRITTEN_OFF)", which read as rejecting a MATURITY close too and so
> contradicted D1.3's promised reopen. The implemented guard originally took that literal reading and
> made the reopen path dead code (the `PaymentVoidIT` maturity-reopen scenario failed). The guard and
> this decision now allow a CLOSED/MATURITY contract through so D1.3 can run; only SETTLEMENT/write-off
> closures are rejected here. The eligibility snapshot (`VoidEligibility`) carries the `closed_reason` so
> the guard can tell MATURITY from SETTLEMENT.

### D6 — The whole void is one atomic transaction

The status flip (D1.1), reversal journal (D1.2), installment recalculation (D1.3) and credit reversal
(D4) succeed or roll back together in a single `@Transactional` application-service boundary (the
controller holds no transaction, per the persistence conventions). The eligibility guards (D4, D5) are
evaluated inside that transaction under the appropriate row locks so they stay valid under concurrent
operations. Optimistic-lock conflicts on the installment/contract/credit `@Version` are retried
**outside** the transaction, mirroring `PaymentRetryingService` (3 attempts, 50/150 ms), surfacing as
`409 CONCURRENT_MODIFICATION` if they persist. The void is idempotent via `IdempotencyService` exactly
as `POST /payments` (identical key replays the stored response; expired key → `IDEMPOTENCY_KEY_EXPIRED`).

---

## Alternatives Considered

- **Delete or mutate the original allocations / journal entry to "clean up" the void.** Rejected: it
  violates ADR-002 (append-only ledger), the V1 allocation immutability trigger, and the V3
  Σ-allocations invariant, and it destroys audit history. Reversal + status flip is the only
  consistent undo.
- **Re-price already-accrued penalty dates at the post-void base (reopen A-3).** Rejected: ADR-012
  alt 5/6 and ADR-013 alt 2 already rejected a second per-date base representation; it needs a
  migration and complexity for a rare case, and the accepted undercharge is conservative.
- **Run catch-up accrual inside the void transaction (symmetry with accrue-before-resolve).**
  Rejected: a void raises the base, so there is no "accrue before the base drops" risk; the daily job
  accrues the un-accrued dates correctly at the higher base. Forcing it into the void adds complexity
  for no correctness gain (D2).
- **Leave `uq_payment_idempotency` partial on POSTED.** Rejected: after T18 cleanup a voided
  payment's key would be reusable and could mint a second payment — a correctness hole ADR-017's own
  consequences flagged (D3).
- **Cascade-reverse applied credit and settlements so any payment is voidable.** Rejected for MVP: an
  unbounded cascade across posted events; blocked with a precise 409 instead (D4/D5), leaving true
  reversal-of-settlement to a dedicated future story.
- **Check consumed-credit eligibility without a row lock.** Rejected: a concurrent credit-apply or
  settlement could consume the credit between the check and the reversal, double-spending it. The row
  lock (D4) closes the race.

---

## Consequences

- **New error codes (all 409 via `GlobalExceptionHandler`):** `PAYMENT_CREDIT_CONSUMED`,
  `PAYMENT_NOT_VOIDABLE`. `CONCURRENT_MODIFICATION` and `IDEMPOTENCY_KEY_EXPIRED` are reused.
- **New `LedgerRefType` is NOT needed:** the reversal reuses the original PAYMENT `(ref_type, ref_id)`
  with `reversal_of_id` set, so the V12 `uq_journal_entry_event` partial index (non-reversal only)
  still permits it.
- **One migration (next free number):** drop + recreate `uq_payment_idempotency` across all statuses,
  with the data-preserving preflight (D3). `payment.amount` is already immutable in practice (V1 +
  V12 note: payment allows only POSTED→VOIDED status moves); confirm no setter exposes it. No
  `CREATE TABLE`.
- **ADR-013 is unchanged** by this ADR; D2 only records how void relates to the already-decided A-3.
- **ADR-017's open T16 item is closed** by D3 (voided keys stay spent). The separate T18 permanent-key
  probe question is resolved independently in the T18 spec (probe declined; truthful varied 409s
  accepted).
- **ADR-018's "void must account for settled installments" note is answered** by D5: settled/closed
  installments make the payment non-voidable in T16; reopening them is out of scope.
- **Deferred (PR #11 review, finding #5): a void idempotency key is not yet single-use-forever.** V18's
  all-status `uq_payment_idempotency` keeps a _payment-creation_ key spent, but a _void_ key lives only
  in the `IdempotencyService` claim row. Once the T18 cleanup job deletes an expired claim, the same key
  could be reused to void a _different_ payment (each such void still passes every eligibility guard and
  posts its own legitimate reversal — it is not a second reversal of the same payment, so no invariant
  is breached; it only weakens the ADR-017 single-use-forever contract for void keys). The hole is
  latent until T18 ships. Closing it needs a durable, uniquely-constrained void-key association on the
  voided payment (a new migration), tracked as a follow-up task rather than folded into T16.
- **Derived docs to update with this ADR** (per `80-documentation`): `02_TECH_SPEC.md` §1 (void is a
  `payment → contract`/`ledger` flow; no new module), `03_DOMAIN_MODEL.md` §1.7/§1.4 (Payment VOIDED
  recomputation; invariant 16 void-when-credit-consumed), `04_GAPS_ADDENDUM.md` §6 (void = reversal +
  status flip, no re-pricing), `GLOSSARY.md` (Void, Payment VOIDED), and `tasks.md` (T16 finalized
  spec).
