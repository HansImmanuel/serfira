-- =====================================================================================
-- Serfira Core — V22: durable single-use-forever backstop for void idempotency keys (T31)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T31 (close ADR-020 "Deferred finding #5"),
--   * ADR-017 (idempotency keys are single-use forever; a permanent business-row backstop
--     holds even when the idempotency_keys claim row is gone),
--   * ADR-020 D3/D6 (void is one atomic transaction; the voided key must stay spent after the
--     T18 cleanup job deletes the claim row), and its Consequences "Deferred finding #5",
--   * V18__payment_idempotency_all_statuses.sql (the create-path precedent this mirrors),
--   * V1__baseline.sql idempotency_keys ((endpoint, key) is the retry-safety key).
--
-- Why this migration exists:
--   V18 made a payment-CREATION key single-use forever via the all-status uq_payment_idempotency
--   index on payment.idempotency_key. A payment-VOID key had no equivalent durable backstop: it
--   lived only in the idempotency_keys claim row, which the T18 cleanup job (F4) deletes once it
--   expires. After that deletion the IdempotencyService layer no longer recognizes the key, so the
--   same void key could be reused to void a DIFFERENT payment. Each such void still passes every
--   ADR-020 D4/D5 eligibility guard and posts its own legitimate reversal, so no ledger invariant is
--   breached — but it silently breaks the ADR-017 single-use-forever contract for void keys.
--
--   A void key is a DIFFERENT key from the create key and is scoped to the void endpoint, so it does
--   not belong on the payment row's create-key column (payment.idempotency_key, guarded by
--   uq_payment_idempotency). This migration instead creates a small side table keyed on the void
--   endpoint scope + the key, uniquely associated with the voided payment id — the same (endpoint,
--   key) shape as idempotency_keys, but as a permanent business-row backstop rather than a TTL claim.
--   The void application service writes one row inside the void transaction (ADR-020 D6), so the
--   association commits or rolls back with the status flip / reversal. The UNIQUE (endpoint,
--   idempotency_key) constraint is the single-use-forever authority once the claim row is gone: a
--   second void that reuses the key for a different payment violates it and is surfaced as
--   409 IDEMPOTENCY_KEY_EXPIRED; an identical replay of the original void finds its own row and
--   replays without a second reversal.
--
--   This keeps the two-layer idempotency model (ADR-017) coherent for void exactly as V18 does for
--   create: IdempotencyService bounds replay while the claim row lives; this permanent business-row
--   backstop is the authority once it is gone.
--
-- Forward-only; applies cleanly from an empty database (payment and app_user always exist before V22).
--
-- -------------------------------------------------------------------------------------
-- DATA-PRESERVING, FAIL-SAFE PREFLIGHT (mirrors V18).
-- -------------------------------------------------------------------------------------
-- Before V22 ships, no void has ever written a durable association row (the table is created here), so
-- the backfill below can only reconstruct one row per already-voided payment from its surviving claim
-- row in idempotency_keys. If two VOIDED payments were somehow voided under the SAME (endpoint, key) —
-- impossible under the live IdempotencyService, which already rejects a reused void key while the claim
-- lives, but checked here so the migration can never silently discard history — the UNIQUE constraint
-- backfill would fail. Detect that collision FIRST and fail the migration with a clear message, WITHOUT
-- deleting or rewriting any payment or idempotency history. An empty preflight is the expected state.
DO $$
DECLARE
    collisions BIGINT;
BEGIN
    SELECT COUNT(*) INTO collisions
      FROM (
            SELECT k.endpoint, k.key
              FROM idempotency_keys k
             WHERE k.endpoint = 'POST /api/v1/payments/{id}/void'
             GROUP BY k.endpoint, k.key
            HAVING COUNT(*) > 1
           ) AS duplicate_void_keys;

    IF collisions > 0 THEN
        RAISE EXCEPTION
            'V22 preflight: % void (endpoint, key) pair(s) already map to more than one claim row; '
            'the durable void-key backstop cannot be built without discarding history. Resolve the '
            'duplicates manually before re-running (no rows were changed).',
            collisions;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- The durable void-key backstop table.
-- -------------------------------------------------------------------------------------
-- One row per completed void: the endpoint-scoped key, the payment it voided, and the audit columns
-- (created_by = the JWT sub that performed the void; written by the application, never a request body).
-- payment_id references payment (id); it is NOT unique on its own, because two DIFFERENT keys may each
-- legitimately be associated with the same payment over its lifetime in edge cases — the uniqueness that
-- enforces single-use-forever is on (endpoint, idempotency_key), not on the payment.
-- The row is append-only: it is written once when the void completes and never updated or deleted (the
-- void is a reversal, not a mutable record), so it carries created_at/created_by only, matching the
-- ImmutableAuditable base class — no updated_at/updated_by columns.
CREATE TABLE payment_void_idempotency (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint         VARCHAR(120) NOT NULL,
    idempotency_key  VARCHAR(80)  NOT NULL,
    payment_id       UUID         NOT NULL REFERENCES payment (id),
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uq_payment_void_idempotency UNIQUE (endpoint, idempotency_key)
);

CREATE INDEX idx_payment_void_idempotency_payment ON payment_void_idempotency (payment_id);

-- -------------------------------------------------------------------------------------
-- Backfill durable rows for any payment already voided before V22.
-- -------------------------------------------------------------------------------------
-- A payment voided before this migration has a surviving claim row in idempotency_keys (unless the T18
-- job already deleted it). Reconstruct the association from that claim so an already-spent void key stays
-- spent the moment V22 applies, rather than only for voids performed after it. A claim whose row T18 has
-- already deleted cannot be reconstructed and is accepted as a pre-existing, now-closed gap (the window
-- T31 removes going forward). The preflight above guarantees at most one claim per (endpoint, key), so no
-- UNIQUE violation is possible here.
INSERT INTO payment_void_idempotency (endpoint, idempotency_key, payment_id, created_at)
SELECT k.endpoint, k.key, p.id, k.created_at
  FROM payment p
  JOIN idempotency_keys k
    ON k.endpoint = 'POST /api/v1/payments/{id}/void'
   AND k.response_json IS NOT NULL
   AND k.response_json LIKE '%' || p.id::text || '%'
 WHERE p.status = 'VOIDED';
