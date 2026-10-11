-- =====================================================================================
-- Serfira Core — V17: settlement-execution database backstops (T13, E2)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * docs/tasks.md T13 (E2 settlement execution),
--   * ADR-018 D1 (exactly one non-reversal SETTLEMENT journal entry per settlement),
--     D5/D10 (settlement consumes all AVAILABLE credit; recorded in settlement_credit_application),
--   * 03_DOMAIN_MODEL.md §1.6, §3 invariants 1 and 11,
--   * V12__accounting_backstops.sql (uq_journal_entry_event, scoped to the then-current ref types),
--   * V14__contract_credit_application_cap.sql (the credit cap + AVAILABLE->APPLIED status guard).
--
-- Numbering: the task text calls this "V16", but V16 is already taken by the E5 review fix
-- (V16__penalty_adjustment_cap_includes_paid.sql). Flyway is forward-only and numbers are never
-- reused, so T13's migration is V17. Verified by listing src/main/resources/db/migration.
--
-- No CREATE TABLE: every settlement/credit table already exists since V1. This migration only
-- extends existing backstops. Forward-only; it applies cleanly from an empty database because
-- V12 (uq_journal_entry_event) and V14 (the credit guards) are always applied before V17.
--
-- -------------------------------------------------------------------------------------
-- DECISION 1 (ADR-018 D1): extend uq_journal_entry_event to cover SETTLEMENT.
-- -------------------------------------------------------------------------------------
-- V12 scoped the partial unique index to CONTRACT_ACTIVATION/BILLING/PENALTY_ACCRUAL/PAYMENT and
-- deliberately left SETTLEMENT out so ADR-008 d5 could stay open (several entries per settlement were
-- still permitted then). ADR-018 D1 closes that: a settlement is never voided and posts exactly one
-- balanced entry, so one non-reversal SETTLEMENT entry per settlement must be a hard DB backstop, not
-- only the LedgerPostingService service guard. A partial unique index cannot be altered in place, so
-- drop and recreate it with 'SETTLEMENT' appended; the WHERE reversal_of_id IS NULL predicate is kept,
-- so a future settlement-reversal (none exists in the MVP) could still share (SETTLEMENT, ref_id) with
-- its original. CREDIT_APPLICATION stays out, as V14 decided (its service guard covers it, each apply
-- using a fresh ref_id). uk_settlement_quote_id (one settlement per quote) and uq_settlement_idempotency
-- already exist from V1 and are unchanged.
-- -------------------------------------------------------------------------------------
DROP INDEX uq_journal_entry_event;

CREATE UNIQUE INDEX uq_journal_entry_event
    ON journal_entry (ref_type, ref_id)
    WHERE reversal_of_id IS NULL
      AND ref_type IN ('CONTRACT_ACTIVATION', 'BILLING', 'PENALTY_ACCRUAL', 'PAYMENT', 'SETTLEMENT');

-- -------------------------------------------------------------------------------------
-- DECISION 2 (ADR-018 D5/D10): count settlement consumption in the contract_credit balance.
-- -------------------------------------------------------------------------------------
-- A settlement consumes AVAILABLE credit and records it in settlement_credit_application (not
-- contract_credit_application, which is the regular-apply history, E3). The contract module then flips
-- the consumed contract_credit to APPLIED. V14's status guard and application cap only counted
-- contract_credit_application, so after a settlement they would (a) reject the APPLIED flip because the
-- balance still looked non-zero, and (b) miss settlement consumption in the Σ-applications cap. Both are
-- redefined here to count BOTH tables, so a credit's "applied" total is its full consumption across the
-- regular-apply and settlement paths, and AVAILABLE->APPLIED is coherent exactly when that total equals
-- the credit amount. The entity guard in ContractCredit.recordApplication mirrors this (it flips to
-- APPLIED when the caller-computed balance reaches 0; the settlement path consumes the whole balance).
-- Available balance read in Java (ContractCreditService.availableCredit) filters on status = AVAILABLE,
-- so a credit flipped APPLIED by a settlement is already excluded there without further change.
-- -------------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION assert_contract_credit_application_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    credit_id_v UUID;
    cap         NUMERIC(19,2);
    applied     NUMERIC(19,2);
BEGIN
    credit_id_v := CASE WHEN TG_OP = 'DELETE' THEN OLD.credit_id ELSE NEW.credit_id END;

    SELECT amount INTO cap
      FROM contract_credit
     WHERE id = credit_id_v;

    IF NOT FOUND THEN
        RETURN NULL;  -- credit row gone; nothing to validate against
    END IF;

    SELECT COALESCE((SELECT SUM(amount) FROM contract_credit_application WHERE credit_id = credit_id_v), 0)
         + COALESCE((SELECT SUM(amount) FROM settlement_credit_application WHERE contract_credit_id = credit_id_v), 0)
      INTO applied;

    IF applied > cap THEN
        RAISE EXCEPTION 'contract_credit % applications % exceed credit amount %',
            credit_id_v, applied, cap;
    END IF;
    RETURN NULL;
END;
$$;

-- Also fire the cap check when a settlement_credit_application row is written (it was previously only on
-- contract_credit_application). A deferred constraint trigger re-reads both tables at COMMIT.
CREATE CONSTRAINT TRIGGER trg_settlement_credit_application_cap_deferred
    AFTER INSERT OR UPDATE OR DELETE ON settlement_credit_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_contract_credit_application_cap();

CREATE OR REPLACE FUNCTION assert_contract_credit_status() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    applied NUMERIC(19,2);
    balance NUMERIC(19,2);
BEGIN
    IF NEW.status = 'REFUNDED' THEN
        RETURN NULL;  -- refund flow owns its own rule (out of MVP)
    END IF;

    SELECT COALESCE((SELECT SUM(amount) FROM contract_credit_application WHERE credit_id = NEW.id), 0)
         + COALESCE((SELECT SUM(amount) FROM settlement_credit_application WHERE contract_credit_id = NEW.id), 0)
      INTO applied;

    balance := NEW.amount - applied;

    IF NEW.status = 'APPLIED' AND balance <> 0 THEN
        RAISE EXCEPTION 'contract_credit % is APPLIED but its balance is % (amount %, applied %)',
            NEW.id, balance, NEW.amount, applied;
    END IF;
    IF NEW.status = 'AVAILABLE' AND balance = 0 THEN
        RAISE EXCEPTION 'contract_credit % is fully consumed (amount %, applied %) and must be APPLIED',
            NEW.id, NEW.amount, applied;
    END IF;
    RETURN NULL;
END;
$$;
