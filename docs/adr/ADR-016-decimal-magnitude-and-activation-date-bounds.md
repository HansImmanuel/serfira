# ADR-016: Decimal Magnitude Bounds and the Activation-Date Window

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Hans (solo developer)

---

## Context

A 2026-10-01 deep scan flagged four externally reachable resource-exhaustion paths (CWE-400). An
authenticated caller could, with a few small requests, force the shared JVM or database to do unbounded work:

- **Compact decimal magnitude.** `BigDecimal` accepts scientific notation, so an eleven-character body value
  such as `1e100000000` denotes a hundred-million-digit integer, and `1e-100000000` denotes a value with a
  hundred-million-digit scale. The write paths rescale request money/rate (`setScale`, `toPlainString`) before
  bounding magnitude:
  - `CanonicalRequestJson.money` / `.rate` run inside `canonicalize(...)`, which `ContractCommandService.create`
    evaluates **before** the aggregate `productTerms` validation.
  - `PaymentApplicationService.requireAmount` checks `amount.scale() > 2`, but a value like `1e100000000` has
    scale `-100000000`, so the scale check passes and `setScale` then builds the giant integer.
  A request-size limit cannot contain amplification encoded this compactly.
- **Historical activation backlog.** `Contract.activate` accepted any effective start date. The schedule's due
  dates follow from it, and `PenaltyCalculator.charges` emits one `PenaltyCharge` per overdue day with no cap;
  `PenaltyAccrualService` then writes one accrual row and `LedgerPostingService` one flushed journal per
  charge. A centuries-old start date, once nudged by a single payment or the daily job, becomes millions of
  writes in one transaction — and the daily servicing job inherits the backlog, so the damage is not confined
  to the attacker's own request.

The authorization gap the same review raised (any authenticated principal could write) is closed separately by
ADR-015; this ADR addresses only the magnitude and date-range exhaustion.

---

## Decision

1. **D1 — Validate the decimal domain with metadata, before any rescale.** A shared guard,
   `shared.money.DecimalBounds`, rejects a `BigDecimal` outside its SQL numeric domain using only
   `BigDecimal.precision()` and `BigDecimal.scale()`. The integer-digit count is `precision() − scale()`, which
   stays correct for the negative scales scientific notation produces and never materializes the unscaled
   value, so the check is constant-time.
   - Money → `NUMERIC(19,2)`: at most 2 fractional digits and 17 integer digits.
   - Rate → `NUMERIC(7,4)`: at most 4 fractional digits and 3 integer digits.
   A violation is **400 `VALIDATION_ERROR`**. The guard checks magnitude only; positivity, `0 ≤ down_payment <
   asset_price`, and rate ∈ `[0,1]` stay in their existing aggregate validation, which now runs on an
   already-bounded value.
2. **D2 — Guard at the service boundary first, and at the formatter as a backstop.**
   `ContractCommandService.create` bounds `asset_price`, `down_payment`, and `interest_rate` before
   `canonicalize(...)` is evaluated; `PaymentApplicationService.requireAmount` bounds `amount` before
   `setScale`. `CanonicalRequestJson.money`/`.rate` also call the guard before rescaling, so no present or
   future caller of those formatters can trigger expansion. The duplicate check is cheap and defensive, not a
   substitute for boundary validation.
3. **D3 — Bound how far activation may be backdated.** `ContractCommandService.activate` rejects an effective
   start date earlier than `today − MAX_BACKDATED_ACTIVATION_YEARS` (currently **1 year**, relative to the
   business clock) as **400**, before the schedule is generated and before any contract state changes. One year
   covers the legitimate "signed last period, entered now" backfill. A larger historical backfill is a
   deliberate, bounded operation that MVP does not offer. Future dates are **allowed**: a planned start that has
   not arrived has no overdue days, so it creates no backlog, and the Domain Model already lets activation pin a
   future effective date (DM §1.3, ADR-006).

The bound is relative to the business clock, so it cannot be a DB CHECK and lives in the application layer. The
idempotent re-activation of an already-ACTIVE contract returns before the guard, so the date it was validated
against at first activation is not re-checked.

---

## Alternatives Considered

- **Rely on the request-size limit / HTTP body cap.** Rejected: `1e100000000` is eleven characters; the
  amplification is in interpretation, not transport size.
- **Rely on `@DecimalMin`/`@DecimalMax` bean validation.** Rejected: those bound the numeric range, not the
  represented digit count, and `1e-100000000` sits inside the rate's `[0,1]` range while still forcing an
  enormous power-of-ten divisor during rounding. The scale/precision check is the cheap, correct bound.
- **Catch the cost by timing out the request.** Rejected: the work runs on shared threads and can allocate
  gigabytes of heap before any timeout fires; prevention must precede the allocation.
- **Reject only the exact scientific-notation forms.** Rejected: the invariant is "fits the column", not "is
  not written in exponent form"; bounding the domain is both simpler and complete.
- **Allow any historical activation date and batch the penalty backlog** (D3). Rejected for MVP: resumable
  bounded backfill is real future work, but until it exists an unbounded historical date is a liability, and the
  servicing job — not just the caller — pays for it. A one-year window keeps the common backfill working while
  removing the amplification.
- **Make the activation window a `system_parameter`.** Rejected for now: it is a safety bound, not a tunable
  business policy, so a code constant with this ADR is clearer. It can move to configuration if an operational
  need appears.

---

## Consequences

- Money finer than scale 2 or beyond 17 integer digits, and rates finer than scale 4 or beyond 3 integer
  digits, are now 400 at the boundary instead of a stall or a late database error. Legitimate values are
  unaffected: the bounds are exactly the existing column domains.
- Activation (or creation whose planned date is used at activation) with an effective start date more than one
  year old is 400. Operators backfilling older contracts need the future bounded-backfill path.
- `DecimalBounds` is a new `shared.money` concern with no dependency beyond `shared.error`, so no module boundary
  changes.
- The guards are defensive duplication by design (D2); the service boundary remains the authoritative check.
