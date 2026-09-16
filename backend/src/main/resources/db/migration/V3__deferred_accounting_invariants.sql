-- =====================================================================================
-- Serfira Core — V3: deferred accounting invariants (commit-time enforcement)
-- -------------------------------------------------------------------------------------
-- Sprint 2 readiness / code review (CRIT-2, H-4). Source of truth:
--   * 03_DOMAIN_MODEL.md §3 invariants 1, 3, 6 and ADR-002 ("Σ debit = Σ kredit")
--   * 02_TECH_SPEC.md §5 (constraint penting)
--
-- Decisions encoded here:
--   1. The two most expensive-to-repair invariants — Σ debit = Σ credit per
--      journal_entry and Σ allocations = payment.amount — plus the per-installment
--      component caps are now enforced by DEFERRABLE INITIALLY DEFERRED constraint
--      triggers (checked at COMMIT, not per statement). journal_entry/journal_line and
--      payment_allocation rows are immutable (V1 triggers), so a wrong committed
--      balance could never be repaired in place — the database must be the backstop.
--   2. ck_installment_amounts is dropped and re-added as a deferred trigger. The
--      immediate CHECK was correct but created a per-statement ordering trap for the
--      C2 allocation flow (recognized_interest/penalty and paid_amount legitimately
--      update in separate statements within one transaction). Deferred evaluation
--      keeps the same guard without dictating write ordering (replaces the NOTE at
--      V1 lines 176–178).
--   3. Constraint triggers are FOR EACH ROW, fire only at COMMIT, and re-read the
--      table so the final transaction state is what gets validated.
--   4. PostgreSQL does not require a special TRUNCATE path here: TRUNCATE does not
--      fire row-level constraint triggers (verified on PG 16 in the A3 spike), so the
--      shared IT cleanup keeps working.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Σ debit = Σ credit per journal_entry (Domain Model invariant 1)
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_journal_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    entry_id      UUID;
    total_debit   NUMERIC(19,2);
    total_credit  NUMERIC(19,2);
BEGIN
    entry_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.journal_entry_id ELSE NEW.journal_entry_id END;

    SELECT COALESCE(SUM(debit), 0), COALESCE(SUM(credit), 0)
      INTO total_debit, total_credit
      FROM journal_line
     WHERE journal_entry_id = entry_id;

    IF total_debit <> total_credit THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debit = %, credit = %',
            entry_id, total_debit, total_credit;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_entry_balance_deferred
    AFTER INSERT OR UPDATE OR DELETE ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_journal_entry_balanced();

-- -------------------------------------------------------------------------------------
-- Σ allocations = payment.amount for every payment that has allocations
-- (Domain Model invariant 6; EXCESS allocations are included in the total).
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_payment_allocation_totals() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    payment_id_v UUID;
    allocated    NUMERIC(19,2);
    expected     NUMERIC(19,2);
BEGIN
    payment_id_v := CASE WHEN TG_OP = 'DELETE' THEN OLD.payment_id ELSE NEW.payment_id END;

    SELECT COALESCE(SUM(amount), 0) INTO allocated
      FROM payment_allocation
     WHERE payment_id = payment_id_v;

    SELECT amount INTO expected
      FROM payment
     WHERE id = payment_id_v;

    IF expected IS NULL OR allocated <> expected THEN
        RAISE EXCEPTION 'payment % allocations total % does not match payment amount %',
            payment_id_v, allocated, expected;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_payment_allocation_total_deferred
    AFTER INSERT OR UPDATE OR DELETE ON payment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_payment_allocation_totals();

-- -------------------------------------------------------------------------------------
-- Per-installment component caps (Domain Model invariant 3, strict form):
--   Σ PRINCIPAL <= principal_amount
--   Σ INTEREST  <= recognized_interest_amount
--   Σ PENALTY   <= penalty_amount - Σ penalty_adjustment (effective gross penalty)
-- EXCESS allocations carry no installment and are only capped by the total check.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_payment_allocation_component_caps() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    inst_id             UUID;
    principal_cap       NUMERIC(19,2);
    interest_cap        NUMERIC(19,2);
    penalty_cap         NUMERIC(19,2);
    allocated_principal NUMERIC(19,2);
    allocated_interest  NUMERIC(19,2);
    allocated_penalty   NUMERIC(19,2);
    adjustments         NUMERIC(19,2);
BEGIN
    inst_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.installment_id ELSE NEW.installment_id END;

    IF inst_id IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT principal_amount, recognized_interest_amount, penalty_amount
      INTO principal_cap, interest_cap, penalty_cap
      FROM installment
     WHERE id = inst_id;

    SELECT
        COALESCE(SUM(amount) FILTER (WHERE allocation_type = 'PRINCIPAL'), 0),
        COALESCE(SUM(amount) FILTER (WHERE allocation_type = 'INTEREST'), 0),
        COALESCE(SUM(amount) FILTER (WHERE allocation_type = 'PENALTY'), 0)
      INTO allocated_principal, allocated_interest, allocated_penalty
      FROM payment_allocation
     WHERE installment_id = inst_id;

    SELECT COALESCE(SUM(amount), 0) INTO adjustments
      FROM penalty_adjustment
     WHERE installment_id = inst_id;

    IF allocated_principal > principal_cap THEN
        RAISE EXCEPTION 'installment % principal allocations % exceed principal %',
            inst_id, allocated_principal, principal_cap;
    END IF;
    IF allocated_interest > interest_cap THEN
        RAISE EXCEPTION 'installment % interest allocations % exceed recognized interest %',
            inst_id, allocated_interest, interest_cap;
    END IF;
    IF allocated_penalty > penalty_cap - adjustments THEN
        RAISE EXCEPTION 'installment % penalty allocations % exceed effective penalty %',
            inst_id, allocated_penalty, penalty_cap - adjustments;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_payment_allocation_caps_deferred
    AFTER INSERT OR UPDATE OR DELETE ON payment_allocation
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_payment_allocation_component_caps();

-- -------------------------------------------------------------------------------------
-- Replaces ck_installment_amounts (dropped below) with a commit-time guard (H-4):
-- the same predicate, evaluated only at COMMIT so recognition + allocation may touch
-- amounts in any statement order within one transaction.
-- -------------------------------------------------------------------------------------
ALTER TABLE installment DROP CONSTRAINT ck_installment_amounts;

CREATE FUNCTION assert_installment_amounts() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    inst_id      UUID;
    v_principal  NUMERIC(19,2);
    v_interest   NUMERIC(19,2);
    v_recognized NUMERIC(19,2);
    v_penalty    NUMERIC(19,2);
    v_paid       NUMERIC(19,2);
    v_settled    NUMERIC(19,2);
    v_written    NUMERIC(19,2);
BEGIN
    inst_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.id ELSE NEW.id END;

    SELECT principal_amount, interest_amount, recognized_interest_amount, penalty_amount,
           paid_amount, settled_amount, written_off_amount
      INTO v_principal, v_interest, v_recognized, v_penalty,
           v_paid, v_settled, v_written
      FROM installment
     WHERE id = inst_id;

    IF NOT FOUND THEN
        RETURN NULL;  -- row deleted (V1 does not block installment deletes); nothing to validate
    END IF;

    IF v_principal < 0
       OR v_interest < 0
       OR v_recognized < 0
       OR v_recognized > v_interest
       OR v_penalty < 0
       OR v_paid < 0
       OR v_settled < 0
       OR v_written < 0
       OR v_paid + v_settled + v_written
          > v_principal + v_recognized + v_penalty
    THEN
        RAISE EXCEPTION 'installment % violates amount invariants', inst_id;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_installment_amounts_deferred
    AFTER INSERT OR UPDATE OR DELETE ON installment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_installment_amounts();