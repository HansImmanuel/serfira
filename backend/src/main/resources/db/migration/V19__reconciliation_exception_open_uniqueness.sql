-- =====================================================================================
-- Serfira Core — V19: one OPEN reconciliation_exception per (contract, date, check) (T17, F1)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/04_GAPS_ADDENDUM.md §7.1 (the three independent checks A/B/C) and §7.3 (the job
--     records drift and never auto-corrects; a human resolves it),
--   * docs/tasks.md T17 (F1 consistency check job),
--   * V1__baseline.sql reconciliation_exception (ck_recon_exception_type / ck_recon_exception_status,
--     idx_recon_exception_contract_date),
--   * PR #12 review (duplicate OPEN rows on a same-date re-run).
--
-- Why this migration exists:
--   The daily consistency check (DailyServicingOrchestrator fourth step) re-runs for the same
--   business_date as a supported workflow (DailyServicingJobIT.rerunningTheSameDate...). Each run
--   inserts a reconciliation_exception row per drifting check. Without a uniqueness guard a re-run
--   of a still-drifting contract adds a SECOND OPEN row for the same (contract_id, check_date,
--   check_type), so an operator has to resolve the same drift twice. One OPEN row per that key is the
--   intended invariant: the drift is one fact, recorded once, until a human resolves it.
--
--   The index is PARTIAL on status = 'OPEN'. RESOLVED/INVESTIGATING history must stay unconstrained:
--   a drift that was resolved and recurs on a later date, or the same key reopened after resolution,
--   are legitimate separate rows. Only two currently-OPEN rows for the same key are forbidden.
--
--   This is the DB backstop; ConsistencyCheckService also skips the insert when an OPEN row already
--   exists for the key (steering: concurrency-sensitive uniqueness is enforced in the DB, not by the
--   application check alone). The daily job is serialized by ShedLock, so the two layers never race in
--   practice; the index guarantees the invariant regardless.
--
-- No CREATE TABLE. Forward-only; applies cleanly from an empty database (reconciliation_exception
-- always exists from V1 before V19).
--
-- -------------------------------------------------------------------------------------
-- FAIL-SAFE PREFLIGHT: a pre-V19 database could already hold duplicate OPEN rows from an earlier
-- re-run. Creating the index would then fail at CREATE time with a cryptic duplicate-key error.
-- Detect such duplicates FIRST and fail with a clear message WITHOUT deleting any exception history:
-- a recorded drift is an audit fact and must not be silently discarded to force the index through.
-- An empty preflight is the expected state.
-- -------------------------------------------------------------------------------------
DO $$
DECLARE
    collisions BIGINT;
BEGIN
    SELECT COUNT(*) INTO collisions
      FROM (
            SELECT contract_id, check_date, check_type
              FROM reconciliation_exception
             WHERE status = 'OPEN'
             GROUP BY contract_id, check_date, check_type
            HAVING COUNT(*) > 1
           ) AS duplicate_open;

    IF collisions > 0 THEN
        RAISE EXCEPTION
            'V19 preflight: % (contract_id, check_date, check_type) key(s) already have more than one '
            'OPEN reconciliation_exception; the uniqueness index cannot be created without discarding '
            'exception history. Resolve or close the duplicates manually before re-running (no rows '
            'were changed).',
            collisions;
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- One OPEN reconciliation_exception per (contract_id, check_date, check_type).
-- -------------------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_recon_exception_open
    ON reconciliation_exception (contract_id, check_date, check_type)
    WHERE status = 'OPEN';
