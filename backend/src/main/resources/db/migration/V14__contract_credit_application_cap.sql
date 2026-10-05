-- =====================================================================================
-- Serfira Core — V14: contract_credit application cap + status guard (commit-time)
-- -------------------------------------------------------------------------------------
-- Sprint 5 / E3 (task T14). Source of truth:
--   * 03_DOMAIN_MODEL.md §3 invariant 11 (Σ contract_credit_application.amount <= credit amount)
--   * 04_GAPS_ADDENDUM.md §2.1 (status AVAILABLE -> APPLIED only when the balance reaches 0)
--
-- Decisions encoded here:
--   1. The two credit invariants that are expensive to repair — a credit over-applied past its
--      amount, and a status that disagrees with the balance — are enforced by DEFERRABLE
--      INITIALLY DEFERRED constraint triggers (checked at COMMIT), mirroring the V3 pattern. The
--      entity guards in ContractCredit / ContractCreditApplication are the first layer; these are
--      the independent database backstop for writes that bypass the entities (raw SQL).
--   2. No CREATE TABLE: both tables already exist (V1). Neither is in the V1/V5 immutability set,
--      so contract_credit.status/version stay mutable and are policed by the status guard below.
--   3. CREDIT_APPLICATION is deliberately NOT added to uq_journal_entry_event (V12): the
--      LedgerPostingService service guard enforces one non-reversal entry per (ref_type, ref_id),
--      consistent with PAYMENT (ADR-008 d5). Each apply uses a fresh contract_credit_application id
--      as ref_id, so every apply is already a distinct event.
--   4. Triggers are FOR EACH ROW, fire only at COMMIT, and re-read the tables so the final
--      transaction state is what gets validated (same approach as V3).
-- =====================================================================================

-- -------------------------------------------------------------------------------------
-- Σ contract_credit_application.amount <= contract_credit.amount per credit (invariant 11)
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_contract_credit_application_cap() RETURNS trigger
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

    SELECT COALESCE(SUM(amount), 0) INTO applied
      FROM contract_credit_application
     WHERE credit_id = credit_id_v;

    IF applied > cap THEN
        RAISE EXCEPTION 'contract_credit % applications % exceed credit amount %',
            credit_id_v, applied, cap;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_contract_credit_application_cap_deferred
    AFTER INSERT OR UPDATE OR DELETE ON contract_credit_application
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_contract_credit_application_cap();

-- -------------------------------------------------------------------------------------
-- Status mirrors the balance (Addendum §2.1):
--   * a fully-consumed credit (balance = 0, i.e. applied = amount) must be APPLIED;
--   * a credit with balance > 0 must not be APPLIED.
-- REFUNDED is out of MVP scope and left untouched by this guard. Mirrors ContractCredit's
-- entity transition exactly so the two layers agree.
-- -------------------------------------------------------------------------------------
CREATE FUNCTION assert_contract_credit_status() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    applied NUMERIC(19,2);
    balance NUMERIC(19,2);
BEGIN
    IF NEW.status = 'REFUNDED' THEN
        RETURN NULL;  -- refund flow owns its own rule (out of MVP)
    END IF;

    SELECT COALESCE(SUM(amount), 0) INTO applied
      FROM contract_credit_application
     WHERE credit_id = NEW.id;

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

CREATE CONSTRAINT TRIGGER trg_contract_credit_status_deferred
    AFTER INSERT OR UPDATE ON contract_credit
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_contract_credit_status();
