-- =====================================================================================
-- Serfira Core — V11: index for the contract statement read path (T9)
-- -------------------------------------------------------------------------------------
-- GET /api/v1/contracts/{id}/statement reads journal_line filtered by contract_id and a
-- business-time entry_date window, ordered by entry_date (rekening koran, FE §2.7, ADR-013
-- A-8). The V1 indexes cover (account_code, entry_date) and (journal_entry_id) but nothing
-- on contract_id, so the statement would scan. This composite index serves both the filter
-- and the chronological order. Forward-only; V1-V10 are untouched.
-- =====================================================================================

CREATE INDEX idx_journal_line_contract_date
    ON journal_line (contract_id, entry_date);
