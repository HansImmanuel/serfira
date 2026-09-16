-- =====================================================================================
-- Serfira Core — V5: settlement & settlement_quote immutability
-- -------------------------------------------------------------------------------------
-- Sprint 2 readiness / code review (H-5). Source of truth:
--   * 03_DOMAIN_MODEL.md §1.5 ("Quote component snapshot bersifat immutable;
--     status boleh berubah QUOTED -> EXECUTED/EXPIRED")
--   * 03_DOMAIN_MODEL.md §1.6 / ADR-002 (settlement is an executed transaction record)
--
-- Decisions encoded here:
--   1. settlement is fully immutable (UPDATE/DELETE blocked at the database level),
--      mirroring journal_entry — corrections would flow through a future reversal
--      record, never an in-place rewrite.
--   2. settlement_quote stays mutable ONLY on {status, version, updated_at, updated_by}
--      so a quote can transition QUOTED -> EXECUTED/EXPIRED (and take optimistic-lock
--      bumps) while every component snapshot column stays frozen.
-- =====================================================================================

-- settlement: full immutability -------------------------------------------------------
CREATE TRIGGER trg_settlement_immutable
    BEFORE UPDATE OR DELETE ON settlement
    FOR EACH ROW EXECUTE FUNCTION block_modification();

-- settlement_quote: status/version/audit-update mutable, snapshot columns frozen ------
CREATE FUNCTION settlement_quote_mutability() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.quote_no <> OLD.quote_no
       OR NEW.contract_id IS DISTINCT FROM OLD.contract_id
       OR NEW.quoted_at IS DISTINCT FROM OLD.quoted_at
       OR NEW.valid_until IS DISTINCT FROM OLD.valid_until
       OR NEW.contract_version IS DISTINCT FROM OLD.contract_version
       OR NEW.outstanding_principal <> OLD.outstanding_principal
       OR NEW.unpaid_billed_interest <> OLD.unpaid_billed_interest
       OR NEW.accrued_interest <> OLD.accrued_interest
       OR NEW.penalty_outstanding <> OLD.penalty_outstanding
       OR NEW.rebate_amount <> OLD.rebate_amount
       OR NEW.admin_fee <> OLD.admin_fee
       OR NEW.available_credit <> OLD.available_credit
       OR NEW.credit_used <> OLD.credit_used
       OR NEW.gross_amount <> OLD.gross_amount
       OR NEW.cash_due <> OLD.cash_due
       OR NEW.created_at <> OLD.created_at
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
    THEN
        RAISE EXCEPTION 'settlement_quote snapshot columns are immutable (only status/version/updated_* may change)';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_settlement_quote_mutability
    BEFORE UPDATE ON settlement_quote
    FOR EACH ROW EXECUTE FUNCTION settlement_quote_mutability();

CREATE TRIGGER trg_settlement_quote_immutable
    BEFORE DELETE ON settlement_quote
    FOR EACH ROW EXECUTE FUNCTION block_modification();