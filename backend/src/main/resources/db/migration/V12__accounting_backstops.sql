-- =====================================================================================
-- Serfira Core — V12: complete the database accounting backstops (T25)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T25 (Sprint 4d; review findings CR-05, CR-06, CR-12 / X-12),
--   * 03_DOMAIN_MODEL.md §3 invariants 1 (Σ debit = Σ credit) and 3/6 (Σ allocations =
--     payment.amount), §1.2 (system_parameter append-only),
--   * ADR-008 decision 5 (amended by this task: a scoped DB-level duplicate guard is now
--     added for the ref types that exist today),
--   * 04_GAPS_ADDENDUM.md §1.2 (system_parameter append-only).
--
-- Why this migration exists:
--   V3 enforces Σ debit = Σ credit and Σ allocations = amount with DEFERRABLE constraint
--   triggers, but only on the CHILD tables (journal_line, payment_allocation). A parent row
--   with NO children never fires those triggers, so a journal_entry with zero lines or a
--   payment with zero allocations commits today (CR-06). This migration adds the matching
--   parent-side deferred checks. It also adds a scoped duplicate-event guard the ledger only
--   had in Java (CR-05), and makes system_parameter append-only in the database, not just by
--   convention (CR-12 / X-12).
--
-- Numbering: this is the task's "V11" by content, but V11 is already taken
-- (V11__journal_line_contract_statement_index.sql, T9). Applied migrations are never renamed,
-- so the next free number is V12.
--
-- Decisions encoded here:
--   1. The parent-side triggers are DEFERRABLE INITIALLY DEFERRED, FOR EACH ROW, on INSERT.
--      They fire at COMMIT and re-read the final transaction state, so the normal write path
--      (insert the entry, then its lines, in one transaction) passes. INSERT is the only event
--      that needs covering: journal_entry and payment can never be UPDATEd/DELETEd to zero
--      children (journal_entry is immutable per V1; payment allows only POSTED→VOIDED status
--      moves, never removal of allocations, and allocations are immutable per V1).
--   2. The duplicate-event unique index is scoped to the ref types the ledger posts today
--      (CONTRACT_ACTIVATION, BILLING, PENALTY_ACCRUAL, PAYMENT), non-reversal entries only.
--      ADR-008 decision 5 kept settlement free to post several entries per event (E2); limiting
--      the index to current types keeps that decision open. A reversal (reversal_of_id set)
--      intentionally shares (ref_type, ref_id) with the entry it reverses, so it is excluded.
--   3. system_parameter gets block_modification() for UPDATE/DELETE, like its append-only
--      siblings (V1). New configuration is a new row with a later effective_date (Addendum §1.2),
--      never an edit of history.
--   4. Rejection timing differs by guard, and tests assert both the SQLSTATE and (for the two
--      deferred triggers) that the failure is at COMMIT: the parent-side constraint triggers are
--      DEFERRED, so they raise `raise_exception` (SQLSTATE P0001) at COMMIT; the
--      `uq_journal_entry_event` unique index raises `unique_violation` (23505) at the INSERT
--      statement; and `trg_system_parameter_immutable` is a BEFORE trigger that raises P0001 at the
--      UPDATE/DELETE statement. Only the first group is commit-time; the T25 Acceptance Criteria
--      wording ("fails at commit") is literally true only for the parent-side checks.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Parent-side: a journal_entry must have ≥ 2 balanced lines (invariant 1).
-- V3's trg_journal_entry_balance_deferred fires on journal_line only, so an entry with no
-- lines slips through. This re-reads the lines at COMMIT and rejects an empty or single-line
-- entry; the balance equality is still owned by V3 but re-checked here for a self-contained guard.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_journal_entry_has_balanced_lines() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    line_count   INTEGER;
    total_debit  NUMERIC(19,2);
    total_credit NUMERIC(19,2);
BEGIN
    SELECT COUNT(*), COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0)
      INTO line_count, total_debit, total_credit
      FROM journal_line
     WHERE journal_entry_id = NEW.id;

    IF line_count < 2 THEN
        RAISE EXCEPTION 'journal entry % must have at least 2 lines, found %', NEW.id, line_count;
    END IF;
    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debit = %, credit = %',
            NEW.id, total_debit, total_credit;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entry_has_lines_deferred
    AFTER INSERT ON journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_has_balanced_lines();

-- -------------------------------------------------------------------------------------
-- Parent-side: Σ allocations = payment.amount for every payment (invariant 6).
-- V3's trg_payment_allocation_total_deferred fires on payment_allocation only, so a payment
-- with no allocations slips through. This re-reads the allocations at COMMIT from the payment
-- side and rejects a payment whose allocations do not sum to its amount (zero included).
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_payment_has_allocations() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    allocated NUMERIC(19,2);
BEGIN
    SELECT COALESCE(SUM(amount), 0) INTO allocated
      FROM payment_allocation
     WHERE payment_id = NEW.id;

    IF allocated <> NEW.amount THEN
        RAISE EXCEPTION 'payment % allocations total % does not match payment amount %',
            NEW.id, allocated, NEW.amount;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_payment_has_allocations_deferred
    AFTER INSERT ON payment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_payment_has_allocations();

-- -------------------------------------------------------------------------------------
-- Event uniqueness (CR-05, amends ADR-008 decision 5): at most one non-reversal entry per
-- (ref_type, ref_id), scoped to the ref types posted today. Settlement is deliberately left
-- out so E2 can still post several entries per settlement. A reversal entry (reversal_of_id
-- set) shares (ref_type, ref_id) with its original and is therefore excluded from the index.
-- No existing data can violate it: journal entries are posted one-per-event by the service
-- today, so the partial index builds cleanly on an empty or V11 database.
-- -------------------------------------------------------------------------------------
CREATE UNIQUE INDEX uq_journal_entry_event
    ON journal_entry (ref_type, ref_id)
    WHERE reversal_of_id IS NULL
      AND ref_type IN ('CONTRACT_ACTIVATION', 'BILLING', 'PENALTY_ACCRUAL', 'PAYMENT');

-- -------------------------------------------------------------------------------------
-- system_parameter append-only (CR-12 / X-12). Latest row with effective_date <= today wins;
-- configuration changes append a new row, they never UPDATE/DELETE history (Addendum §1.2).
-- The trigger does not fire on TRUNCATE (relied on by shared IT cleanup, V1/V3 note).
-- -------------------------------------------------------------------------------------
CREATE TRIGGER trg_system_parameter_immutable
    BEFORE UPDATE OR DELETE ON system_parameter
    FOR EACH ROW EXECUTE FUNCTION block_modification();
