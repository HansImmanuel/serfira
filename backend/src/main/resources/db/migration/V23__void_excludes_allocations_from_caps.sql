-- =====================================================================================
-- Serfira Core — V23: payment-allocation cap triggers exclude VOIDED payments (T31, PR #16)
-- -------------------------------------------------------------------------------------
-- Source of truth:
--   * ADR-020 (payment void: a voided payment's payment_allocation rows stay in the table as
--     immutable history; the VOIDED status on the parent is the marker that they no longer
--     count toward live receivable — reads net voided payments by status, not by deleting rows),
--   * ADR-021 (this correction: the DB cap backstops must exclude VOIDED allocations, matching
--     the POSTED-only application ports),
--   * ADR-009 (allocation risk note), ADR-019 (effective-penalty formula),
--   * 03_DOMAIN_MODEL.md §3 invariants 3 (per-installment component caps) and 9 (effective
--     penalty >= 0),
--   * V3__deferred_accounting_invariants.sql (original caps), V16__penalty_adjustment_cap_
--     includes_paid.sql (penalty cap).
--
-- Why (latent bug, predates T31, exposed when the T31 ITs were finally run with Docker):
--   V3's assert_payment_allocation_component_caps() and V16's assert_penalty_adjustment_cap()
--   both sum payment_allocation rows WITHOUT filtering by the owning payment's status. V16's
--   comment claimed "void is T16, so this equals the POSTED-only application port today" — that
--   is now FALSE: void (T16, ADR-020) is live. A VOIDED payment's immutable allocation rows
--   still counted against the per-installment caps, so re-paying the same installment after a
--   void summed the voided rows + the new rows and tripped the DEFERRABLE trigger at COMMIT,
--   surfacing as HTTP 500. The application already counts only POSTED allocations
--   (PaymentAllocationRepository.sumActiveByInstallmentIds(PaymentStatus.POSTED, ...)); these
--   DB backstops were the only stale readers. This migration joins payment_allocation -> payment
--   and filters payment.status = 'POSTED' (PaymentStatus persists @Enumerated(STRING)), making
--   each cap numerically identical to its application port.
--
-- Forward-only; applies cleanly from an empty database. No new object: both functions are
-- superseded in place with CREATE OR REPLACE FUNCTION, keeping the existing DEFERRABLE INITIALLY
-- DEFERRED triggers (trg_payment_allocation_caps_deferred, trg_penalty_adjustment_cap_deferred)
-- wired to the same names. assert_payment_allocation_totals() (V3, per-payment Σ = amount) is NOT
-- touched: it is correct for a voided payment (its rows still total its amount).
-- =====================================================================================

-- Per-installment component caps (DM §3 invariant 3): now excludes VOIDED allocations.
CREATE OR REPLACE FUNCTION assert_payment_allocation_component_caps() RETURNS trigger
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

    -- Count only allocations whose owning payment is POSTED: a VOIDED payment's rows stay as
    -- history but no longer count (ADR-020). Matches sumActiveByInstallmentIds(POSTED, ...).
    SELECT
        COALESCE(SUM(pa.amount) FILTER (WHERE pa.allocation_type = 'PRINCIPAL'), 0),
        COALESCE(SUM(pa.amount) FILTER (WHERE pa.allocation_type = 'INTEREST'), 0),
        COALESCE(SUM(pa.amount) FILTER (WHERE pa.allocation_type = 'PENALTY'), 0)
      INTO allocated_principal, allocated_interest, allocated_penalty
      FROM payment_allocation pa
      JOIN payment p ON p.id = pa.payment_id
     WHERE pa.installment_id = inst_id
       AND p.status = 'POSTED';

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

-- Penalty-adjustment / waiver cap (ADR-019 formula, DM §3 invariant 9): the paid-penalty term
-- now counts only PENALTY allocations whose owning payment is POSTED (supersedes V16's no-filter
-- subquery and its now-false "void is T16, so this equals the POSTED-only application port today"
-- comment — void is live, so the status filter is required for the DB backstop to stay numerically
-- identical to sumActiveByInstallmentIds(PaymentStatus.POSTED, ...)).
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

    -- Σ PENALTY payment_allocation for the installment, POSTED payments only — mirrors V23's
    -- caps and the POSTED-only application port (ADR-020; supersedes V16's no-status-filter sum).
    SELECT COALESCE(SUM(pa.amount) FILTER (WHERE pa.allocation_type = 'PENALTY'), 0)
      INTO paid_penalty
      FROM payment_allocation pa
      JOIN payment p ON p.id = pa.payment_id
     WHERE pa.installment_id = inst_id
       AND p.status = 'POSTED';

    remaining := gross - paid_penalty;

    IF adjusted > remaining THEN
        RAISE EXCEPTION 'installment % penalty adjustments % exceed remaining penalty % (gross % − paid penalty %); effective would be negative',
            inst_id, adjusted, remaining, gross, paid_penalty;
    END IF;
    RETURN NULL;
END;
$$;
