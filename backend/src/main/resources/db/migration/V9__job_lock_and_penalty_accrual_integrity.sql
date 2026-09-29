-- =====================================================================================
-- Serfira Core — V9: ShedLock lock table and penalty_accrual integrity (D2 prerequisite, T2)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T2 (Sprint 4b), ADR-012 decision 5 / invariant 8 (penalty_accrual is
--     append-only; reductions are penalty_adjustment rows, E5),
--   * 03_DOMAIN_MODEL.md §1.9 (PenaltyAccrual), §3 invariant 8,
--   * build.gradle.kts: shedlock-provider-jdbc-template:6.9.0.
--
-- Decisions encoded here:
--   1. The ShedLock lock table uses the exact schema documented by the JdbcTemplate provider
--      for PostgreSQL (net.javacrumbs.shedlock, README "JdbcTemplate" section): a single table
--      named `shedlock` with `name` as primary key, `lock_until`/`locked_at` as TIMESTAMP (no
--      time zone — ShedLock manages the value itself; usingDbTime() at the provider is
--      configured, if at all, in T3, not here) and `locked_by` VARCHAR(255). No FK, audit
--      columns, or additional constraints are added: this table is owned by the ShedLock
--      library, not by Serfira's own domain model, and the library issues raw UPDATE/INSERT
--      statements against exactly these four columns.
--   2. `penalty_accrual` becomes immutable like its append-only siblings (journal_entry,
--      journal_line, payment_allocation, settlement_allocation, penalty_adjustment — V1). A
--      reduction is always a new `penalty_adjustment` row (E5), never an UPDATE/DELETE here.
--   3. `ck_penalty_accrual_days` is tightened from `days_late >= 0` to `days_late >= 1`,
--      matching the application-level guard already enforced by `PenaltyAccrual` (constructor
--      throws below 1) and by `PenaltyCharge`/`PenaltyTerms` (a charge only ever exists for a
--      day past the grace window). The looser V1 constraint was never intentional — TS §4.3
--      and DM §1.9 both describe `days_late` as the count of days already past grace, which is
--      undefined (and never produced) for day 0.
--   4. `PenaltyAccrual.version` (`@jakarta.persistence.Version`) is left mapped, not removed.
--      It is harmless: the entity has no setters and `PenaltyAccrualService` only ever calls
--      `repository.save(new PenaltyAccrual(...))` on a transient instance, so JPA only ever
--      issues INSERT for this entity — never an UPDATE that the new trigger would reject. The
--      annotation stays as defensive documentation of "this row is versioned like every other
--      audited entity" rather than a signal that updates are expected.
-- =====================================================================================

-- ShedLock JDBC template lock table (D2 scheduler, T3). Schema per the pinned provider version's
-- documented PostgreSQL table (net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.0).
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

-- penalty_accrual: append-only, matching its sibling posted/append-only tables (V1 comment above
-- their triggers). The trigger does not fire on TRUNCATE (V1/V3 note, relied on by IT cleanup).
CREATE TRIGGER trg_penalty_accrual_immutable
    BEFORE UPDATE OR DELETE ON penalty_accrual
    FOR EACH ROW EXECUTE FUNCTION block_modification();

-- Tighten days_late to the value space the application ever produces (invariant 8, ADR-012).
ALTER TABLE penalty_accrual
    DROP CONSTRAINT ck_penalty_accrual_days;

ALTER TABLE penalty_accrual
    ADD CONSTRAINT ck_penalty_accrual_days CHECK (days_late >= 1);
