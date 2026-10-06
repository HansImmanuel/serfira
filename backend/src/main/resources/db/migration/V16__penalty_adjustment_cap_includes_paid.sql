-- =====================================================================================
-- Serfira Core — V16: penalty-adjustment cap includes penalty already PAID (E5, PR #7 review F2)
-- -------------------------------------------------------------------------------------
-- Sprint 5 / E5 review-fix. Source of truth:
--   * ADR-019 Context (the formula: effectivePenalty = grossAccruedPenalty
--                       − activePenaltyAllocation − penaltyAdjustment)
--   * 03_DOMAIN_MODEL.md §3 invariant 9 (effective penalty >= 0)
--
-- Thin, forward-only, applies cleanly from empty. NO new object: this supersedes V15's gross-only cap
-- in place with CREATE OR REPLACE FUNCTION, keeping the existing DEFERRABLE INITIALLY DEFERRED trigger
-- trg_penalty_adjustment_cap_deferred wired (V15 is applied and MUST NOT be edited).
--
-- Why (F2): V15's assert_penalty_adjustment_cap() capped Σ penalty_adjustment.amount <= penalty_amount,
-- omitting the activePenaltyAllocation term of ADR-019's own formula. On an installment with partly-PAID
-- penalty a full-gross waiver passed the cap, over-credited PIUTANG_DENDA (negative receivable), and
-- poisoned the V3 payment-allocation cap. The predicate below subtracts Σ PENALTY payment_allocation as
-- well, so the DB backstop bounds a waiver by the REMAINING effective penalty.
--
-- Numerically identical by construction to (a) the application pre-check in PenaltyAdjustmentService and
-- (b) V3's assert_payment_allocation_component_caps() PENALTY cap: it reads payment_allocation directly
-- (the DB has no module boundary) with the SAME FILTER (WHERE allocation_type = 'PENALTY') as V3, counting
-- every allocation row regardless of payment status (ADR-009 risk note; void is T16, so this equals the
-- POSTED-only application port today).
-- =====================================================================================

CREATE OR REPLACE FUNCTION assert_penalty_adjustment_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    inst_id        UUID;
    gross          NUMERIC(19,2);
    adjusted       NUMERIC(19,2);
    paid_penalty   NUMERIC(19,2);
    remaining      NUMERIC(19,2);
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

    -- Σ PENALTY payment_allocation for the installment — mirrors V3's FILTER exactly (no status filter).
    SELECT COALESCE(SUM(amount) FILTER (WHERE allocation_type = 'PENALTY'), 0) INTO paid_penalty
      FROM payment_allocation
     WHERE installment_id = inst_id;

    remaining := gross - paid_penalty;

    IF adjusted > remaining THEN
        RAISE EXCEPTION 'installment % penalty adjustments % exceed remaining penalty % (gross % − paid penalty %); effective would be negative',
            inst_id, adjusted, remaining, gross, paid_penalty;
    END IF;
    RETURN NULL;
END;
$$;
