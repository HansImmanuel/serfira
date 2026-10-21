-- =====================================================================================
-- Serfira Core — V20: write-off requires a recording actor (F5, task T19)
-- -------------------------------------------------------------------------------------
-- Source of truth: 03_DOMAIN_MODEL.md §1.3/§1.12 (write-off terminal state),
-- 04_GAPS_ADDENDUM.md §4.2/§13, 60-security (audit actors come from the security context).
--
-- Numbering: this migration was authored as V19 on branch t19-write-off (V18 was the
-- highest on main at the time). The T17 consistency-check branch (PR #12) merged first and
-- also claimed V19 (V19__reconciliation_exception_open_uniqueness.sql), so this one is
-- renumbered forward-only to V20 at merge, exactly as the branch header anticipated and as
-- T13's V16->V17 note records.
--
-- V4 ck_contract_terminated_coherence only required write_off_reason when a contract is
-- TERMINATED. The write-off endpoint also records WHO stopped collection
-- (write_off_recorded_by = the JWT sub, never a request body value). This migration
-- strengthens the terminal coherence CHECK so a TERMINATED contract can never exist in the
-- database without the recording actor, independently of the application layer — the
-- two-layer invariant pattern (entity guard + DB backstop).
--
-- Forward-only, data-preserving: no contract is TERMINATED yet (the write-off feature ships
-- with this migration), so re-adding the stricter CHECK validates cleanly against an empty
-- set of terminated rows. closed_at is deliberately NOT required by the CHECK: it is the
-- audit stamp the application sets on terminate, but V4 keeps closed_at/closed_reason as a
-- CLOSED-only pair, so adding a TERMINATED closed_at requirement here would be a wider schema
-- change than T19's scope. The reason + recorded_by pair is what makes a write-off auditable.
-- =====================================================================================

ALTER TABLE contract
    DROP CONSTRAINT ck_contract_terminated_coherence;

ALTER TABLE contract
    ADD CONSTRAINT ck_contract_terminated_coherence CHECK (
        status <> 'TERMINATED' OR (write_off_reason IS NOT NULL AND write_off_recorded_by IS NOT NULL));
