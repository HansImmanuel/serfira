-- =====================================================================================
-- Serfira Core — V21: index for the idempotency-key cleanup job (F4, T18)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T18 (F4 idempotency key cleanup job),
--   * 02_TECH_SPEC.md §2.5 (idempotency retention),
--   * ADR-017 (keys are single-use forever; the business-row backstops hold even when the
--     claim row is gone — the 2026-10-11 resolution declines a permanent-key probe),
--   * ADR-013 A-9 (one job_run row per invocation).
--
-- Why this migration exists:
--   The T18 cleanup job physically deletes expired idempotency_keys rows in bounded keyset
--   pages: DELETE ... WHERE id IN (SELECT id FROM idempotency_keys WHERE expires_at < now
--   ORDER BY expires_at, id LIMIT n). Without an index on expires_at that page scan walks the
--   whole table every run. This btree index serves both the expires_at predicate and the
--   (expires_at, id) keyset order, keeping each page cheap as the table grows.
--
-- This is additive hygiene only: NO CREATE TABLE, NO change to any unique index or backstop
-- (uq_contract_idempotency, the V18 all-status uq_payment_idempotency). Forward-only; applies
-- cleanly from an empty database (idempotency_keys exists since V1).
-- =====================================================================================

CREATE INDEX idx_idempotency_keys_expires_at
    ON idempotency_keys (expires_at);
