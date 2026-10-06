-- =====================================================================================
-- Serfira Core — V15: penalty waiver accounting (E5, task T15)
-- -------------------------------------------------------------------------------------
-- Sprint 5 / E5. Source of truth:
--   * ADR-019 (effective-penalty port; the waiver journal shape D3)
--   * 03_DOMAIN_MODEL.md §1.10 (penalty_adjustment), §3 invariant 9 (effective penalty >= 0)
--   * 04_GAPS_ADDENDUM.md §16.4 (penalty waiver audit)
--
-- Thin, forward-only, applies cleanly from empty. NO CREATE TABLE: penalty_adjustment already
-- exists (V1) with its CHECK constraints and the V1 immutability trigger
-- (trg_penalty_adjustment_immutable). This migration only adds the waiver expense account and the
-- standalone effective-penalty cap on the adjustment write path.
--
-- Decisions encoded here (pinned in the T15 task note):
--   D-A. New EXPENSE account BEBAN_WAIVER_DENDA, NOT a reuse of DISKON_PELUNASAN. DISKON_PELUNASAN has
--        a committed, distinct meaning (settlement rebate, ADR-018 D3); reusing it would conflate two
--        economic events. The waiver journal is Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA
--        (ref_type=PENALTY_WAIVER, ref_id=penalty_adjustment.id). The `name` below MUST stay byte-for-byte
--        equal to LedgerAccount.BEBAN_WAIVER_DENDA.displayName() (LedgerAccountNameIT / LedgerPostingIT).
--   D-B. Standalone cap trigger on penalty_adjustment (this file). V3's cap is a trigger on
--        payment_allocation and subtracts Σ penalty_adjustment only when an allocation is written; an
--        adjustment INSERT changes no allocation row, so V3 does not catch an over-waive. This DEFERRABLE
--        INITIALLY DEFERRED trigger enforces Σ penalty_adjustment.amount <= installment.penalty_amount per
--        installment at COMMIT (invariant 9), mirroring V14's assert_contract_credit_application_cap and
--        the entity/service guards.
--   D-C. uq_journal_entry_event (V12) is NOT extended with PENALTY_WAIVER. Each adjustment uses a fresh
--        penalty_adjustment.id as ref_id, so every waiver is already a distinct event, and
--        LedgerPostingService already rejects a second non-reversal entry per (ref_type, ref_id)
--        (ADR-008 d5), exactly as CREDIT_APPLICATION/PAYMENT. The index backstop is left to a later task
--        if ever wanted.
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- D-A: the penalty-waiver expense account.
-- -------------------------------------------------------------------------------------
INSERT INTO accounts (code, name, account_type, created_at, updated_at) VALUES
    ('BEBAN_WAIVER_DENDA', 'Expense — penalty waiver', 'EXPENSE', clock_timestamp(), clock_timestamp());

-- -------------------------------------------------------------------------------------
-- D-B: Σ penalty_adjustment.amount <= installment.penalty_amount per installment (invariant 9).
-- Re-reads the final transaction state at COMMIT, so the whole set of adjustments for an installment is
-- validated together regardless of insert order (same approach as V3/V14).
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_penalty_adjustment_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    inst_id    UUID;
    gross      NUMERIC(19,2);
    adjusted   NUMERIC(19,2);
BEGIN
    inst_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.installment_id ELSE NEW.installment_id END;

    SELECT penalty_amount INTO gross
      FROM installment
     WHERE id = inst_id;

    IF NOT FOUND THEN
        RETURN NULL;  -- installment row gone; nothing to validate against
    END IF;

    SELECT COALESCE(SUM(amount), 0) INTO adjusted
      FROM penalty_adjustment
     WHERE installment_id = inst_id;

    IF adjusted > gross THEN
        RAISE EXCEPTION 'installment % penalty adjustments % exceed gross penalty % (effective would be negative)',
            inst_id, adjusted, gross;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_penalty_adjustment_cap_deferred
    AFTER INSERT OR UPDATE OR DELETE ON penalty_adjustment
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_penalty_adjustment_cap();
