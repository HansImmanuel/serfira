-- =====================================================================================
-- Serfira Core — V6: contract.planned_start_date (planned vs effective start date)
-- -------------------------------------------------------------------------------------
-- Sprint 2 / B5. Source of truth:
--   * 01_PRD.md C-1 — "tanggal mulai" is captured when the contract is created,
--   * 03_DOMAIN_MODEL.md §1.3 — start_date is the activation date,
--   * 06_FRONTEND_SPEC.md §2.3 (create form has "Tanggal Mulai") / §2.4 (activation dialog).
--
-- Decisions encoded here:
--   1. `planned_start_date` is the date the operator authored while drafting. It is REQUIRED
--      by the create API (POST /api/v1/contracts) but nullable in the column so pre-B5 DRAFT
--      rows and raw-SQL fixtures remain insertable — the write path guarantees presence, not
--      the column.
--   2. `start_date` keeps its documented meaning: the effective activation date that pins the
--      schedule. The V4 CHECK `DRAFT => start_date IS NULL` is deliberately unchanged.
--   3. Activation defaults the effective start date to `planned_start_date` unless the caller
--      supplies an explicit override (a real disbursement date).
-- =====================================================================================

ALTER TABLE contract ADD COLUMN planned_start_date DATE NULL;

COMMENT ON COLUMN contract.planned_start_date IS
    'Date planned while drafting (PRD C-1). Required by the create API. Activation uses it as '
    'the effective start_date unless an explicit override is supplied.';