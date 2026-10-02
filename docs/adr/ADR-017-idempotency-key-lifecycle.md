# ADR-017: Idempotency Key Lifecycle — Single-Use Forever (amends ADR-007 decision 9)

- **Status:** Accepted
- **Date:** 2026-10-02
- **Deciders:** Hans (solo developer)

---

## Context

ADR-007 built the documented `idempotency_keys` mechanism (TS §2.5): a row per `(endpoint, key)`
carrying `request_hash`, the stored response and an `expires_at = claim time +
IDEMPOTENCY_KEY_RETENTION_DAYS` (seeded 7). Decision 9 left what happens _after_ the retention window
unspecified. The implementation filled that gap with a **takeover**: once `expires_at` had passed, a
reused key's row was overwritten and the operation **re-run** as if it were a brand-new request.

The 2026-09-30 external review (CR-04) and ambiguity A-13 showed the takeover produces a wrong,
misleading answer and wasted work, even though it never moves money twice:

- **`POST /contracts`:** the re-run always fails, because the permanent backstop
  `uq_contract_idempotency` (ADR-007 decision 8, V7) still holds — it returned 409 `DUPLICATE_CONTRACT`
  or `CONFLICT`, not a truthful "this key is spent".
- **`POST /payments`:** the re-run bills and accrues again, then hits `uq_payment_idempotency` (V1,
  partial on `status = 'POSTED'`). Because `PaymentConflictClassifier` treated **every** SQLSTATE
  `23505` as retryable, `PaymentRetryingService` retried the violation three times — each attempt
  redoing billing and accrual and rolling back — then returned 409 `CONCURRENT_MODIFICATION`, telling
  the client to retry a request that can never succeed.

Two smaller defects travelled with this (CR-13): `PaymentRetryingService.BACKOFF_MILLIS` carried a
third `400 ms` slot that is unreachable with three attempts, and the `IdempotencyService` /
`IdempotencyKeyRepository` Javadoc described the takeover as if ADR-007 had decided it (X-11).

The owner resolved A-13 on 2026-09-30 as **option A**. This ADR records that decision; it amends
ADR-007 decision 9 and leaves decisions 1–8 unchanged.

---

## Decision

1. **D1 — A key is single-use forever, per endpoint. Retention only bounds replay.** Within the
   window a retry replays the stored response (or conflicts on a different body); past the window the
   key is **spent**, not reusable. There is no takeover and the operation is never re-run. Example:
   key `abc` creates `PAY-202609-0001` on 1 Oct; a retry of `abc` on 5 Oct replays that response; a
   retry on 9 Oct (past the 7-day window) is rejected, with no billing, accrual or payment work,
   whether or not the body matches.

2. **D2 — Reusing an expired key is 409 `IDEMPOTENCY_KEY_EXPIRED`, raised before the supplier runs.**
   `IdempotencyService.execute` detects the existing row whose `expires_at` has passed and throws
   `IdempotencyKeyExpiredException` (a new `SerfiraException`, 409) before `operation.get()`. So no
   business code executes for an expired key. The dedicated code lets a client distinguish "mint a
   fresh key" from a genuine state conflict. The `reclaimExpired` takeover query is removed.

3. **D3 — The permanent business-row backstops return the same code once the row is gone.** After the
   F4 cleanup job (story T18) deletes an `idempotency_keys` row, the in-memory guard can no longer
   see the key, so the DB backstops become the authority. `uq_contract_idempotency` (contract) and
   `uq_payment_idempotency` (payment) are each translated to `IDEMPOTENCY_KEY_EXPIRED`, so a reused
   spent key gives one deterministic answer regardless of whether the row still exists. The contract
   translator (`translateCreateViolation`) and a new payment translator
   (`translatePaymentViolation`) do this; no schema change is needed, because the backstops already
   exist (ADR-007 decision 8).

4. **D4 — `uq_payment_idempotency` is never retried.** `PaymentConflictClassifier` now matches on the
   constraint name: only `uk_penalty_accrual` (the genuine payment-versus-job accrual race) and
   optimistic-lock failures are retryable. A `uq_payment_idempotency` `23505` is a spent-key conflict
   a retry cannot clear, so it is translated to `IDEMPOTENCY_KEY_EXPIRED` and surfaced once, with no
   repeated billing/accrual work.

5. **D5 — Tidy `PaymentRetryingService.BACKOFF_MILLIS` to `{50, 150}` and correct its Javadoc**
   (CR-13). With three attempts only two inter-attempt pauses occur, so the third slot was dead code.
   No behaviour change: the realized sequence was already 50 ms then 150 ms.

---

## Alternatives Considered

- **Option B — a key stays reusable after retention; relax the business-row backstops.** Rejected: it
  weakens ADR-007 decision 8 (the "at most one contract/payment per key" guarantee) and reintroduces
  the risk of a second business row under a recycled key. The whole point of the backstops is that a
  key can never produce two business rows.
- **Option C — keep keys reusable but tie an internal claim id onto each business row.** Rejected: it
  needs a migration and new columns to tell "same logical request" from "recycled key", for a case
  (deliberate key reuse after a week) that is not a real client workflow.
- **Keep the takeover but return a truthful code.** Rejected: the takeover's defining act is
  re-running the mutation, which is exactly what option A forbids. Fixing only the code would still
  redo billing and accrual on every expired payment retry.
- **A new `EXPIRED` status on the row instead of reading `expires_at`.** Rejected: `expires_at` is
  already the authoritative signal and needs no write; adding a status transition would mean a write
  on the read path and a new state to keep consistent. The `status` column stays `IN_PROGRESS` /
  `COMPLETED`.

---

## Consequences

- Clients that recycled a key after the retention window previously got a misleading 409
  (`DUPLICATE_CONTRACT` / `CONCURRENT_MODIFICATION`); they now get a precise 409
  `IDEMPOTENCY_KEY_EXPIRED` and must mint a fresh key. This is documented in TS §2.5 and in the
  endpoints' OpenAPI.
- An expired payment retry does **no** billing or accrual work and makes **one** attempt (no retry
  loop), so the wasted work CR-04 described is gone.
- No migration: the backstops and the `idempotency_keys` schema are unchanged. The void story (T16)
  must keep `uq_payment_idempotency` meaningful after T18 deletes the claim row — e.g. by widening the
  partial index to all statuses — so a voided payment's key stays spent under option A. The settlement
  story (T13) inherits the same rule; `uq_settlement_idempotency` (fully unique, V1) is already
  consistent with it.
- `PaymentConflictClassifier` and `penalty`'s `DailyServicingConflictClassifier` are no longer
  identical (only the payment path has an idempotency constraint to exclude). This keeps them separate
  by design; the two-call-site rule against a premature `shared` abstraction still holds.
- X-11 is resolved: the `IdempotencyService` and `IdempotencyKeyRepository` Javadoc now describe
  single-use-forever semantics instead of the undecided takeover.
- **After the claim row is deleted (post-T18), the exact 409 code depends on which truthful guard the
  re-run hits first; it is not guaranteed to be `IDEMPOTENCY_KEY_EXPIRED`.** While the row still
  exists, the reuse is rejected with `IDEMPOTENCY_KEY_EXPIRED` before any business code. Once the row
  is gone, the backstop is only reached if execution gets that far: a same-request contract retry
  trips the live-contract precheck first and returns `DUPLICATE_CONTRACT`; a payment retry against a
  contract that has since gone CLOSED/TERMINATED trips billing's `CONTRACT_STATE_INVALID` first. Both
  are accepted: they are truthful, actionable 409s, and the defect option A targets was the
  _misleading_ `CONCURRENT_MODIFICATION`/takeover re-run, not the choice between two honest conflict
  codes (the spec's "verified behavior today" already lists `DUPLICATE_CONTRACT` as a legitimate
  outcome). A pre-business-logic permanent-key probe would make the code uniform but adds a read to
  both hot write paths for a case that cannot occur until T18 exists; it is deliberately **not** done
  here and is left for T18 to decide if uniformity is wanted. (Flagged by the PR bot review, 2026-10-02.)
