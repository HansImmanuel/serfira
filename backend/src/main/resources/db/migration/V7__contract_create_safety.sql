-- =====================================================================================
-- Serfira Core — V7: contract creation safety (retry safety + one live contract per asset)
-- -------------------------------------------------------------------------------------
-- Sprint 2 / B5. Source of truth:
--   * 02_TECH_SPEC.md §2.2 (Idempotency-Key) and §2.5 (request_hash / stored response),
--   * 03_DOMAIN_MODEL.md §1.3 + §3 invariant 18 (new, documented in this migration),
--   * 04_GAPS_ADDENDUM.md §1.4 (business number generator).
--
-- Decisions encoded here:
--   1. At most one live (DRAFT | ACTIVE) contract per (customer_id, asset_id). Create resolves
--      the financed asset by identity (serial_no, else plate_no) and REUSES that row, so "same
--      asset" is a row-level fact rather than a string comparison. Refinancing after
--      CLOSED/TERMINATED stays possible, and a repossessed asset may later be financed for a
--      different customer.
--   2. Defense-in-depth for create retry safety, mirroring `uq_payment_idempotency`
--      (V1 lines 221-223): at most one contract per idempotency key, even if the
--      `idempotency_keys` row insert were bypassed. NULL is allowed so pre-B5 rows and raw-SQL
--      fixtures stay insertable; the API requires the header, so it is never NULL on the write
--      path.
-- =====================================================================================

ALTER TABLE contract ADD COLUMN idempotency_key VARCHAR(80) NULL;

CREATE UNIQUE INDEX uq_contract_idempotency
    ON contract (idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Invariant 18 (03_DOMAIN_MODEL.md §3): a financed asset carries at most one live contract.
CREATE UNIQUE INDEX uq_contract_live_asset
    ON contract (customer_id, asset_id) WHERE status IN ('DRAFT', 'ACTIVE');