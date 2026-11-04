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
-- row in idempotency_keys. The association is keyed on the void (endpoint, key); the payment it voided is
-- read from the TOP-LEVEL id of the stored PaymentResponse envelope (see the backfill note below), not by
-- a substring match, so one claim maps to exactly one payment. Two failure modes are checked FIRST and
-- fail the migration with a clear message, WITHOUT deleting or rewriting any payment or idempotency
-- history (both are empty in the expected state):
--   (1) two void claim rows sharing the same (endpoint, key) — impossible under the live
--       IdempotencyService, which rejects a reused void key while the claim lives, but checked so the
--       UNIQUE constraint can never fail with a raw violation;
--   (2) a void claim whose stored response has NO extractable top-level payment id (malformed/legacy
--       JSON). Such a claim cannot be mapped to a payment unambiguously; it is reported here rather than
--       silently skipped, so history is never guessed at. (The backfill itself also skips it, so even if
--       this guard were relaxed the migration stays fail-safe.)
DO $$
DECLARE
    duplicate_keys BIGINT;
    unmappable_claims BIGINT;
BEGIN
    SELECT COUNT(*) INTO duplicate_keys
      FROM (
            SELECT k.endpoint, k.key
              FROM idempotency_keys k
             WHERE k.endpoint = 'POST /api/v1/payments/{id}/void'
             GROUP BY k.endpoint, k.key
            HAVING COUNT(*) > 1
           ) AS duplicate_void_keys;

    IF duplicate_keys > 0 THEN
        RAISE EXCEPTION
            'V22 preflight: % void (endpoint, key) pair(s) already map to more than one claim row; '
            'the durable void-key backstop cannot be built without discarding history. Resolve the '
            'duplicates manually before re-running (no rows were changed).',
            duplicate_keys;
    END IF;

    -- A COMPLETED void claim that resolved a payment must carry that payment's id at the top level of its
    -- stored PaymentResponse JSON. One with a response but no extractable top-level id is ambiguous history.
    SELECT COUNT(*) INTO unmappable_claims
      FROM idempotency_keys k
     WHERE k.endpoint = 'POST /api/v1/payments/{id}/void'
       AND k.response_json IS NOT NULL
       AND (k.response_json::jsonb ->> 'id') IS NULL;

    IF unmappable_claims > 0 THEN
        RAISE EXCEPTION
            'V22 preflight: % void claim row(s) have a stored response with no top-level payment id; '
            'the voided payment cannot be reconstructed unambiguously. Inspect those idempotency_keys '
            'rows manually before re-running (no rows were changed).',
            unmappable_claims;
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

-- Append-only backstop: block row-level UPDATE/DELETE, matching every comparable immutable table in this
-- schema (journal_entry, journal_line, payment_allocation, settlement_allocation, penalty_adjustment —
-- V1; penalty_accrual — V9; settlement, settlement_quote — V5; settlement_credit_application — V17;
-- system_parameter — V12). The entity's updatable=false column mappings do not stop a native SQL
-- UPDATE/DELETE; this trigger does (PR #16 finding 5). INSERT stays allowed — the void service writes one
-- row per completed void. block_modification() is a FOR EACH ROW trigger, so it does NOT fire on the
-- statement-level TRUNCATE ... CASCADE the IT fixtures use to reset the schema (V1/V3 note).
CREATE TRIGGER trg_payment_void_idempotency_immutable
    BEFORE UPDATE OR DELETE ON payment_void_idempotency
    FOR EACH ROW EXECUTE FUNCTION block_modification();

-- -------------------------------------------------------------------------------------
-- Backfill durable rows for any payment already voided before V22.
-- -------------------------------------------------------------------------------------
-- A payment voided before this migration has a surviving claim row in idempotency_keys (unless the T18
-- job already deleted it). Reconstruct the association from that claim so an already-spent void key stays
-- spent the moment V22 applies, rather than only for voids performed after it. A claim whose row T18 has
-- already deleted cannot be reconstructed and is accepted as a pre-existing, now-closed gap (the window
-- T31 removes going forward). The preflight above guarantees at most one claim per (endpoint, key), so no
-- UNIQUE violation is possible here.
--
-- The payment is matched by the TOP-LEVEL id of the stored response, not by a substring. The stored
-- response_json is the bare PaymentResponse record serialized by IdempotencyService (Jackson global
-- SNAKE_CASE), BEFORE the controller wraps it in the {data, error} ApiResponse envelope — so the payment
-- id is the top-level "id" field, read as response_json::jsonb ->> 'id'. Matching that exact id (a payment
-- PK) to payment.id maps each claim to exactly one payment, where the old '%' || id || '%' LIKE could also
-- match the id embedded in an allocation's installment_id, a contract id, etc. and map one claim to several
-- payments — violating uq_payment_void_idempotency and failing the migration. created_by is carried over
-- from the claim so the reconstructed association keeps the original actor (PR #16 finding 4). A claim with
-- no extractable top-level id is skipped here and already rejected by the preflight above (fail-safe).
INSERT INTO payment_void_idempotency (endpoint, idempotency_key, payment_id, created_at, created_by)
SELECT k.endpoint, k.key, p.id, k.created_at, k.created_by
  FROM payment p
  JOIN idempotency_keys k
    ON k.endpoint = 'POST /api/v1/payments/{id}/void'
   AND k.response_json IS NOT NULL
   AND (k.response_json::jsonb ->> 'id') = p.id::text
 WHERE p.status = 'VOIDED';
