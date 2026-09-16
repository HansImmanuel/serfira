-- =====================================================================================
-- Serfira Core — V4: record state/timestamp coherence
-- -------------------------------------------------------------------------------------
-- Sprint 2 readiness / code review (H-1 DB half, M-4). Source of truth:
--   * 03_DOMAIN_MODEL.md §1.3–1.4, §1.7 (status fields and their timestamp/marker pairs)
--   * 04_GAPS_ADDENDUM.md §4.2 (write-off fields only set at TERMINATED)
--
-- Decisions encoded here:
--   1. Same-row CHECKs close the status/timestamp gaps the enum-only CHECKs leave open
--      (e.g. status='CLOSED' with closed_at IS NULL). Cross-row transitions
--      (DRAFT -> ACTIVE -> CLOSED/TERMINATED, maturity-close only when all installments
--      are resolved) remain an application-service concern (Sprint 2 B5 + Sprint 4 C4).
--   2. Rules are status-led implications so legitimate flows are never blocked
--      mid-transition: a single-row UPDATE that moves status AND sets the marker
--      columns together always sees a coherent final row.
--   3. installment.status does not forbid paid_at while OVERDUE (partial payments are
--      allowed on overdue installments), and does not forbid paid_at while SETTLED
--      (settling a partially-paid installment keeps its payment history).
-- =====================================================================================

-- contract (03_DOMAIN_MODEL.md §1.3, 04_GAPS_ADDENDUM.md §4.2) -----------------------
ALTER TABLE contract
    ADD CONSTRAINT ck_contract_draft_coherence CHECK (
        status <> 'DRAFT' OR (start_date IS NULL AND closed_at IS NULL AND closed_reason IS NULL)),
    ADD CONSTRAINT ck_contract_active_coherence CHECK (
        status <> 'ACTIVE' OR (start_date IS NOT NULL AND closed_at IS NULL AND closed_reason IS NULL)),
    ADD CONSTRAINT ck_contract_closed_coherence CHECK (
        status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_reason IS NOT NULL)),
    ADD CONSTRAINT ck_contract_terminated_coherence CHECK (
        status <> 'TERMINATED' OR write_off_reason IS NOT NULL);

-- installment (03_DOMAIN_MODEL.md §1.4, M-4 period bound) -----------------------------
ALTER TABLE installment
    ADD CONSTRAINT ck_installment_period_no_min CHECK (period_no >= 1),
    ADD CONSTRAINT ck_installment_pending_coherence CHECK (
        status <> 'PENDING' OR (paid_at IS NULL AND settled_at IS NULL AND written_off_at IS NULL)),
    ADD CONSTRAINT ck_installment_paid_coherence CHECK (
        status NOT IN ('PAID', 'PARTIALLY_PAID') OR paid_at IS NOT NULL),
    ADD CONSTRAINT ck_installment_settled_coherence CHECK (
        status <> 'SETTLED' OR settled_at IS NOT NULL),
    ADD CONSTRAINT ck_installment_written_off_coherence CHECK (
        status <> 'WRITTEN_OFF' OR written_off_at IS NOT NULL);

-- payment (03_DOMAIN_MODEL.md §1.7): void is a reversal, never a silent flag ---------
ALTER TABLE payment
    ADD CONSTRAINT ck_payment_posted_coherence CHECK (
        status <> 'POSTED' OR (voided_at IS NULL AND void_reason IS NULL)),
    ADD CONSTRAINT ck_payment_voided_coherence CHECK (
        status <> 'VOIDED' OR (voided_at IS NOT NULL AND void_reason IS NOT NULL));