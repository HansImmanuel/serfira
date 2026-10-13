-- =====================================================================================
-- Serfira Core — V18: widen uq_payment_idempotency to all payment statuses (T16, E4)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T16 (E4 payment void),
--   * ADR-020 D3 (a voided payment's idempotency key must stay spent after the T18 cleanup
--     job deletes the idempotency_keys claim row),
--   * ADR-017 (idempotency keys are single-use forever; the business-row backstops hold even
--     when the claim row is gone),
--   * V1__baseline.sql line 223 (the original partial index on status = 'POSTED').
--
-- Why this migration exists:
--   V1 created uq_payment_idempotency as a PARTIAL unique index on (idempotency_key) WHERE
--   status = 'POSTED'. That was correct while void did not exist: a POSTED payment was the only
--   kind, so "one POSTED payment per key" was "one payment per key". Once T16 can flip a payment
--   POSTED -> VOIDED, the predicate stops covering it, so after the T18 cleanup job deletes the
--   idempotency_keys claim the key would be free to mint a SECOND payment (the IdempotencyService
--   layer is gone, and the partial index no longer matches the VOIDED row). ADR-020 D3 closes that
--   hole: recreate the index WITHOUT the status predicate, so the key is unique across ALL payment
--   statuses and stays spent whether the payment is POSTED or VOIDED, and whether or not the claim
--   row still exists.
--
--   This keeps the two-layer idempotency model (ADR-017) coherent: IdempotencyService bounds replay
--   while the claim row lives; this permanent business-row backstop is the authority once it is gone.
--
-- No CREATE TABLE. Forward-only; applies cleanly from an empty database (the payment table and the
-- V1 index always exist before V18).
--
-- -------------------------------------------------------------------------------------
-- DATA-PRESERVING, FAIL-SAFE PREFLIGHT (ADR-020 D3).
-- -------------------------------------------------------------------------------------
-- Dropping the status predicate makes the index stricter (POSTED + VOIDED now share one namespace).
-- If any idempotency_key already had two payment rows that the partial index tolerated (it can only
-- happen for keys with no POSTED row, which the pre-void system never produced), recreating the index
-- would fail at CREATE time with a cryptic duplicate-key error. Instead, detect such a collision FIRST
-- and fail the migration with a clear message, WITHOUT deleting or rewriting any payment history: a
-- voided payment is real financial history and must never be silently discarded to force the index
-- through. An empty preflight is the expected state (today's partial index already blocks two POSTED
-- payments per key, and no VOIDED rows can exist yet).
DO $$
DECLARE
    collisions BIGINT;
BEGIN
    SELECT COUNT(*) INTO collisions
      FROM (
            SELECT idempotency_key
              FROM payment
             GROUP BY idempotency_key
            HAVING COUNT(*) > 1
           ) AS duplicate_keys;

    IF collisions > 0 THEN
        RAISE EXCEPTION
            'V18 preflight: % idempotency_key value(s) already map to more than one payment row; '
            'the all-status uq_payment_idempotency cannot be created without discarding payment '
            'history. Resolve the duplicates manually before re-running (no rows were changed).',
            collisions;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- Drop the POSTED-only partial index and recreate it across all statuses.
-- -------------------------------------------------------------------------------------
DROP INDEX uq_payment_idempotency;

CREATE UNIQUE INDEX uq_payment_idempotency ON payment (idempotency_key);
