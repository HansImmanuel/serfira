-- =====================================================================================
-- Serfira Core — V13: widen ck_job_run_status to allow 'ABANDONED' (T26 / CR-07)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T26 (review finding CR-07),
--   * docs/adr/ADR-013-phase1-close-semantics.md A-9 (job_run close semantics; this
--     migration is cited by the T26 implementation note on A-9),
--   * 04_GAPS_ADDENDUM.md §10 (job_run audit rows).
--
-- Why this migration exists:
--   A crashed daily-servicing run leaves its three job_run rows RUNNING forever
--   (finished_at NULL). CR-07 introduces a third terminal status, ABANDONED, used ONLY
--   for crash recovery: the next locked run marks any pre-existing RUNNING row of the
--   daily-servicing job names ABANDONED at start-up (the ShedLock guarantees no other run
--   is live, so a RUNNING row can only be a crash remnant). ABANDONED is deliberately kept
--   distinct from FAILED — FAILED still means "ran to completion with records_failed > 0"
--   (A-9), so conflating "the JVM died" with "finished with failures" would corrupt the
--   audit signal. Abandonment is applied at the Java layer (JobRun.abandon via the injected
--   Clock), so this migration only widens the CHECK; there is no data backfill.
--
-- Numbering: V12 is the latest applied migration, so the next free number is V13.
-- Migrations are forward-only; V1–V12 are never edited.
--
-- Applies cleanly on an empty database and on a database already at V12: DROP CONSTRAINT
-- then ADD a widened IN-list never rejects existing rows (their status is one of the
-- original three), and the widened list is a strict superset of the baseline
--   CONSTRAINT ck_job_run_status CHECK (status IN ('RUNNING','COMPLETED','FAILED'))
-- defined in V1.
-- =====================================================================================

ALTER TABLE job_run DROP CONSTRAINT ck_job_run_status;
ALTER TABLE job_run ADD CONSTRAINT ck_job_run_status
    CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'ABANDONED'));
