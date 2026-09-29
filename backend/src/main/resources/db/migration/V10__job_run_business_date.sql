-- =====================================================================================
-- Serfira Core — V10: target business date for auditable job-step invocations (T3)
-- -------------------------------------------------------------------------------------
-- A run may backfill a date other than the wall-clock date in started_at, so job_run needs
-- the explicit date its financial work targeted. Existing rows (if any) are backfilled from
-- started_at in Serfira's Asia/Jakarta business zone. No uniqueness is added: an idempotent
-- rerun and a retry after a stale RUNNING invocation must each leave their own execution row.
-- =====================================================================================

ALTER TABLE job_run
    ADD COLUMN business_date DATE NULL;

UPDATE job_run
   SET business_date = (started_at AT TIME ZONE 'Asia/Jakarta')::date
 WHERE business_date IS NULL;

ALTER TABLE job_run
    ALTER COLUMN business_date SET NOT NULL;

CREATE INDEX idx_job_run_name_business_date
    ON job_run (job_name, business_date, started_at);
