-- =====================================================================================
-- Serfira Core — V1: baseline schema
-- -------------------------------------------------------------------------------------
-- Sprint 0 / A3. Source of truth:
--   * 03_DOMAIN_MODEL.md §1 (entities), §1.12 (COA), §1.14 (counter), §1.15 (support)
--   * 04_GAPS_ADDENDUM.md §1.2 (system_parameter), §1.3 (seed values), §3 (auth),
--     §7.2 (reconciliation_exception), §10 (job_run / outbox_events), §17 (schema summary)
-- Design decisions (also mirrored in README/ADR):
--   1. Enum-like domains are VARCHAR + CHECK constraints (migration- and Hibernate-friendly;
--      PostgreSQL native enums are painful to evolve and awkward to map with Hibernate).
--   2. Monetary columns: NUMERIC(19,2); rates: NUMERIC(7,4) as decimal fractions (1.5% = 0.0150).
--   3. Audit columns created_at/created_by/updated_at/updated_by are TIMESTAMPTZ/UUID on every
--      business table; created_by/updated_by reference app_user and stay NULL only for bootstrap
--      rows (SYSTEM user, Addendum §3.3). journal_* keep updated_at/updated_by NULL (immutable).
--   4. Strict accounting invariants (Σ debit = Σ credit; resolved_amount <= recognized_total;
--      Σ allocations = payment amount) are validated in the application service layer +
--      integration tests (stories C1/C2) and, where possible, tightened by DB constraints below.
--   5. V99__demo_seed.sql (Sprint 7) deliberately does NOT live here: baseline stays
--      production-clean and is activated by the "demo" Spring profile only.
-- =====================================================================================

-- =====================================================================================
-- Immutability guard: posted/append-only records can never be UPDATEd or DELETEd.
-- Corrections always flow through reversals (ADR-002, Domain Model invariants 12, 7, 10).
-- =====================================================================================
CREATE FUNCTION block_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'immutable table: UPDATE and DELETE are not allowed';
END;
$$ LANGUAGE plpgsql;

-- =====================================================================================
-- app_user (Addendum §3.1)
-- =====================================================================================
CREATE TABLE app_user (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username              VARCHAR(60)  NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    role                  VARCHAR(20)  NOT NULL,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ  NULL,
    last_login_at         TIMESTAMPTZ  NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            UUID         NULL REFERENCES app_user (id),
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN_OPERASIONAL', 'FINANCE', 'MANAJEMEN', 'SYSTEM'))
);

-- =====================================================================================
-- customer (03_DOMAIN_MODEL.md §1.1). nik is encrypted at rest (app-level converter,
-- Sprint 2+) and NEVER used for uniqueness — nik_hash (HMAC-SHA-256, secret-keyed) is.
-- =====================================================================================
CREATE TABLE customer (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name   VARCHAR(120) NOT NULL,
    nik         VARCHAR(16)  NOT NULL,
    nik_hash    CHAR(64)     NOT NULL,
    phone       VARCHAR(20)  NULL,
    address     TEXT         NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID         NULL REFERENCES app_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_customer_nik_hash UNIQUE (nik_hash)
);

-- =====================================================================================
-- asset (03_DOMAIN_MODEL.md §1.2); serial_no/plate_no unique only when present.
-- =====================================================================================
CREATE TABLE asset (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_type  VARCHAR(20)  NOT NULL,
    brand       VARCHAR(80)  NOT NULL,
    model       VARCHAR(80)  NOT NULL,
    serial_no   VARCHAR(40)  NULL,
    plate_no    VARCHAR(20)  NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  UUID         NULL REFERENCES app_user (id),
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  UUID         NULL REFERENCES app_user (id),
    CONSTRAINT ck_asset_type CHECK (asset_type IN ('MOTORCYCLE', 'CAR', 'ELECTRONICS', 'OTHER'))
);

CREATE UNIQUE INDEX uq_asset_serial_no ON asset (serial_no) WHERE serial_no IS NOT NULL;
CREATE UNIQUE INDEX uq_asset_plate_no ON asset (plate_no) WHERE plate_no IS NOT NULL;

-- =====================================================================================
-- contract (03_DOMAIN_MODEL.md §1.3). grace/penalty are snapshots of global configuration
-- at activation time (Addendum §1.1); version = optimistic lock for concurrent writes.
-- =====================================================================================
CREATE TABLE contract (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_no           VARCHAR(30)   NOT NULL,
    customer_id           UUID          NOT NULL REFERENCES customer (id),
    asset_id              UUID          NOT NULL REFERENCES asset (id),
    asset_price           NUMERIC(19,2) NOT NULL,
    principal             NUMERIC(19,2) NOT NULL,
    down_payment          NUMERIC(19,2) NOT NULL DEFAULT 0,
    tenor_months          INT           NOT NULL,
    interest_scheme       VARCHAR(20)   NOT NULL,
    interest_rate         NUMERIC(7,4)  NOT NULL,
    grace_period_days     INT           NOT NULL,
    penalty_rate_daily    NUMERIC(7,4)  NOT NULL,
    start_date            DATE          NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    write_off_reason      TEXT          NULL,
    write_off_recorded_by UUID          NULL REFERENCES app_user (id),
    closed_at             TIMESTAMPTZ   NULL,
    closed_reason         VARCHAR(20)   NULL,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL,
    created_by            UUID          NULL REFERENCES app_user (id),
    updated_at            TIMESTAMPTZ   NOT NULL,
    updated_by            UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_contract_no UNIQUE (contract_no),
    CONSTRAINT ck_contract_asset_price CHECK (
        asset_price > 0
        AND principal > 0
        AND down_payment >= 0
        AND down_payment < asset_price
        AND asset_price = principal + down_payment
    ),
    CONSTRAINT ck_contract_rates CHECK (interest_rate >= 0 AND penalty_rate_daily >= 0),
    CONSTRAINT ck_contract_tenor CHECK (tenor_months > 0),
    CONSTRAINT ck_contract_grace CHECK (grace_period_days >= 0),
    CONSTRAINT ck_contract_scheme CHECK (interest_scheme IN ('FLAT', 'EFFECTIVE')),
    CONSTRAINT ck_contract_status CHECK (status IN ('DRAFT', 'ACTIVE', 'CLOSED', 'TERMINATED')),
    CONSTRAINT ck_contract_closed_reason CHECK (closed_reason IS NULL OR closed_reason IN ('MATURITY', 'SETTLEMENT'))
);

-- =====================================================================================
-- installment (03_DOMAIN_MODEL.md §1.4). One schedule per contract (unique period_no);
-- resolved-amount non-negative bound mirrors invariant 3 (strict form enforced in service).
-- =====================================================================================
CREATE TABLE installment (
    id                         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id                UUID          NOT NULL REFERENCES contract (id),
    period_no                  INT           NOT NULL,
    due_date                   DATE          NOT NULL,
    principal_amount           NUMERIC(19,2) NOT NULL,
    interest_amount            NUMERIC(19,2) NOT NULL,
    recognized_interest_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    penalty_amount             NUMERIC(19,2) NOT NULL DEFAULT 0,
    paid_amount                NUMERIC(19,2) NOT NULL DEFAULT 0,
    settled_amount             NUMERIC(19,2) NOT NULL DEFAULT 0,
    written_off_amount         NUMERIC(19,2) NOT NULL DEFAULT 0,
    status                     VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    paid_at                    TIMESTAMPTZ   NULL,
    settled_at                 TIMESTAMPTZ   NULL,
    written_off_at             TIMESTAMPTZ   NULL,
    version                    BIGINT        NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ   NOT NULL,
    created_by                 UUID          NULL REFERENCES app_user (id),
    updated_at                 TIMESTAMPTZ   NOT NULL,
    updated_by                 UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_installment_period UNIQUE (contract_id, period_no),
    CONSTRAINT ck_installment_amounts CHECK (
        principal_amount >= 0
        AND interest_amount >= 0
        AND recognized_interest_amount >= 0
        AND recognized_interest_amount <= interest_amount
        AND penalty_amount >= 0
        AND paid_amount >= 0
        AND settled_amount >= 0
        AND written_off_amount >= 0
        AND paid_amount + settled_amount + written_off_amount
            <= principal_amount + recognized_interest_amount + penalty_amount
    ),
    CONSTRAINT ck_installment_status CHECK (status IN
        ('PENDING', 'PARTIALLY_PAID', 'PAID', 'OVERDUE', 'SETTLED', 'WRITTEN_OFF'))
);
-- NOTE for the C2 allocation story: ck_installment_amounts is checked per statement. Allocation
-- code must update recognized_interest_amount (and penalty_amount) in the same statement as —
-- or before — paid_amount, otherwise a transient intermediate state violates the CHECK.

CREATE INDEX idx_installment_contract_due ON installment (contract_id, due_date);

-- =====================================================================================
-- accounts — chart of accounts (03_DOMAIN_MODEL.md §1.12, ADR-002). Seeded in V1.
-- =====================================================================================
CREATE TABLE accounts (
    code          VARCHAR(30)  PRIMARY KEY,
    name          VARCHAR(80)  NOT NULL,
    account_type  VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    UUID         NULL REFERENCES app_user (id),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    UUID         NULL REFERENCES app_user (id),
    CONSTRAINT ck_accounts_type CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'))
);

-- =====================================================================================
-- payment (03_DOMAIN_MODEL.md §1.7). Voiding is a reversal — never a DELETE.
-- =====================================================================================
CREATE TABLE payment (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_no       VARCHAR(30)   NOT NULL,
    contract_id      UUID          NOT NULL REFERENCES contract (id),
    amount           NUMERIC(19,2) NOT NULL,
    channel          VARCHAR(20)   NOT NULL,
    paid_at          TIMESTAMPTZ   NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'POSTED',
    voided_at        TIMESTAMPTZ   NULL,
    void_reason      TEXT          NULL,
    idempotency_key  VARCHAR(80)   NOT NULL,
    version          BIGINT        NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_payment_no UNIQUE (payment_no),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_channel CHECK (channel IN ('CASH', 'BANK_TRANSFER', 'VA_STUB', 'EWALLET_STUB')),
    CONSTRAINT ck_payment_status CHECK (status IN ('POSTED', 'VOIDED'))
);

-- Defense-in-depth for payment retry safety: one POSTED payment per idempotency key, even if the
-- idempotency_keys row insert were bypassed. Voided payments may share a key with the retry.
CREATE UNIQUE INDEX uq_payment_idempotency ON payment (idempotency_key) WHERE status = 'POSTED';

CREATE INDEX idx_payment_contract ON payment (contract_id);

-- =====================================================================================
-- payment_allocation (03_DOMAIN_MODEL.md §1.8). Append-only: row stays after a void as
-- history and merely stops counting as active. installment_id is NULL exactly for EXCESS.
-- =====================================================================================
CREATE TABLE payment_allocation (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id       UUID          NOT NULL REFERENCES payment (id),
    installment_id   UUID          NULL REFERENCES installment (id),
    allocation_type  VARCHAR(20)   NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_payment_allocation_type CHECK (allocation_type IN ('PENALTY', 'INTEREST', 'PRINCIPAL', 'EXCESS')),
    CONSTRAINT ck_payment_allocation_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_allocation_excess CHECK ((allocation_type = 'EXCESS') = (installment_id IS NULL))
);

CREATE INDEX idx_payment_allocation_payment ON payment_allocation (payment_id);
CREATE INDEX idx_payment_allocation_installment ON payment_allocation (installment_id);

-- =====================================================================================
-- penalty_accrual (03_DOMAIN_MODEL.md §1.9). One incremental daily accrual per
-- (installment_id, accrual_date); amount is the delta, not the cumulative balance.
-- =====================================================================================
CREATE TABLE penalty_accrual (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    installment_id UUID          NOT NULL REFERENCES installment (id),
    accrual_date   DATE          NOT NULL,
    days_late      INT           NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL,
    created_by     UUID          NULL REFERENCES app_user (id),
    updated_at     TIMESTAMPTZ   NOT NULL,
    updated_by     UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_penalty_accrual UNIQUE (installment_id, accrual_date),
    CONSTRAINT ck_penalty_accrual_amount CHECK (amount > 0),
    CONSTRAINT ck_penalty_accrual_days CHECK (days_late >= 0)
);

-- =====================================================================================
-- penalty_adjustment (03_DOMAIN_MODEL.md §1.10, Addendum §16). Immutable; corrections are
-- new adjustments, never edits of history.
-- =====================================================================================
CREATE TABLE penalty_adjustment (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    installment_id   UUID          NOT NULL REFERENCES installment (id),
    adjustment_type  VARCHAR(20)   NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    reason           TEXT          NOT NULL,
    approved_by      UUID          NOT NULL REFERENCES app_user (id),
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_penalty_adjustment_type CHECK (adjustment_type IN ('WAIVE', 'REDUCE')),
    CONSTRAINT ck_penalty_adjustment_amount CHECK (amount > 0)
);

-- =====================================================================================
-- contract_credit / contract_credit_application (Addendum §2.1–2.2)
-- Excess payment becomes a customer credit (TITIPAN_NASABAH). amount is immutable —
-- status transitions AVAILABLE -> APPLIED / REFUNDED only.
-- =====================================================================================
CREATE TABLE contract_credit (
    id                           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id                  UUID          NOT NULL REFERENCES contract (id),
    source_payment_allocation_id UUID          NOT NULL REFERENCES payment_allocation (id),
    amount                       NUMERIC(19,2) NOT NULL,
    status                       VARCHAR(20)   NOT NULL DEFAULT 'AVAILABLE',
    version                      BIGINT        NOT NULL DEFAULT 0,
    created_at                   TIMESTAMPTZ   NOT NULL,
    created_by                   UUID          NULL REFERENCES app_user (id),
    updated_at                   TIMESTAMPTZ   NOT NULL,
    updated_by                   UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_contract_credit_source UNIQUE (source_payment_allocation_id),
    CONSTRAINT ck_contract_credit_amount CHECK (amount > 0),
    CONSTRAINT ck_contract_credit_status CHECK (status IN ('AVAILABLE', 'APPLIED', 'REFUNDED'))
);

CREATE INDEX idx_contract_credit_contract ON contract_credit (contract_id);

CREATE TABLE contract_credit_application (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    credit_id       UUID          NOT NULL REFERENCES contract_credit (id),
    installment_id  UUID          NOT NULL REFERENCES installment (id),
    amount          NUMERIC(19,2) NOT NULL,
    applied_at      TIMESTAMPTZ   NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    created_by      UUID          NULL REFERENCES app_user (id),
    updated_at      TIMESTAMPTZ   NOT NULL,
    updated_by      UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_contract_credit_application_amount CHECK (amount > 0)
);

CREATE INDEX idx_contract_credit_application_credit ON contract_credit_application (credit_id);

-- =====================================================================================
-- settlement_quote / settlement / settlement_allocation / settlement_credit_application
-- (03_DOMAIN_MODEL.md §1.5–1.6, Addendum §13). Quote is an immutable component snapshot;
-- only its status moves QUOTED -> EXECUTED/EXPIRED.
-- =====================================================================================
CREATE TABLE settlement_quote (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_no                VARCHAR(40)   NOT NULL,
    contract_id             UUID          NOT NULL REFERENCES contract (id),
    quoted_at               TIMESTAMPTZ   NOT NULL,
    valid_until             TIMESTAMPTZ   NOT NULL,
    contract_version        BIGINT        NOT NULL,
    outstanding_principal   NUMERIC(19,2) NOT NULL,
    unpaid_billed_interest  NUMERIC(19,2) NOT NULL,
    accrued_interest        NUMERIC(19,2) NOT NULL,
    penalty_outstanding     NUMERIC(19,2) NOT NULL,
    rebate_amount           NUMERIC(19,2) NOT NULL,
    admin_fee               NUMERIC(19,2) NOT NULL,
    available_credit        NUMERIC(19,2) NOT NULL,
    credit_used             NUMERIC(19,2) NOT NULL DEFAULT 0,
    gross_amount            NUMERIC(19,2) NOT NULL,
    cash_due                NUMERIC(19,2) NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'QUOTED',
    version                 BIGINT        NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ   NOT NULL,
    created_by              UUID          NULL REFERENCES app_user (id),
    updated_at              TIMESTAMPTZ   NOT NULL,
    updated_by              UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_settlement_quote_no UNIQUE (quote_no),
    CONSTRAINT ck_settlement_quote_amounts CHECK (
        outstanding_principal >= 0 AND unpaid_billed_interest >= 0 AND accrued_interest >= 0
        AND penalty_outstanding >= 0 AND rebate_amount >= 0 AND admin_fee >= 0
        AND available_credit >= 0 AND credit_used >= 0 AND gross_amount >= 0 AND cash_due >= 0
    ),
    CONSTRAINT ck_settlement_quote_credit_used CHECK (credit_used <= available_credit),
    CONSTRAINT ck_settlement_quote_status CHECK (status IN ('QUOTED', 'EXECUTED', 'EXPIRED'))
);

CREATE INDEX idx_settlement_quote_contract ON settlement_quote (contract_id, quoted_at);

-- =====================================================================================
-- settlement — transaction record: quote execution, cash + credit components.
-- =====================================================================================
CREATE TABLE settlement (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_no     VARCHAR(40)   NOT NULL,
    contract_id       UUID          NOT NULL REFERENCES contract (id),
    quote_id          UUID          NOT NULL REFERENCES settlement_quote (id),
    cash_received     NUMERIC(19,2) NOT NULL,
    credit_used       NUMERIC(19,2) NOT NULL DEFAULT 0,
    rebate_amount     NUMERIC(19,2) NOT NULL,
    admin_fee         NUMERIC(19,2) NOT NULL,
    executed_at       TIMESTAMPTZ   NOT NULL,
    idempotency_key   VARCHAR(80)   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    created_by        UUID          NULL REFERENCES app_user (id),
    updated_at        TIMESTAMPTZ   NOT NULL,
    updated_by        UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_settlement_no UNIQUE (settlement_no),
    CONSTRAINT uk_settlement_quote_id UNIQUE (quote_id),
    CONSTRAINT ck_settlement_amounts CHECK (
        cash_received >= 0 AND credit_used >= 0 AND rebate_amount >= 0 AND admin_fee >= 0
    )
);

-- Settlements are never voided, so the idempotency key is unique without any partial predicate.
CREATE UNIQUE INDEX uq_settlement_idempotency ON settlement (idempotency_key);

CREATE INDEX idx_settlement_contract_executed ON settlement (contract_id, executed_at);

-- =====================================================================================
-- settlement_allocation — immutable per-installment resolution of a settlement.
-- =====================================================================================
CREATE TABLE settlement_allocation (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id    UUID          NOT NULL REFERENCES settlement (id),
    installment_id   UUID          NOT NULL REFERENCES installment (id),
    allocation_type  VARCHAR(20)   NOT NULL,
    amount           NUMERIC(19,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_settlement_allocation_type CHECK (allocation_type IN ('PENALTY', 'INTEREST', 'PRINCIPAL')),
    CONSTRAINT ck_settlement_allocation_amount CHECK (amount > 0)
);

CREATE INDEX idx_settlement_allocation_settlement ON settlement_allocation (settlement_id);
CREATE INDEX idx_settlement_allocation_installment ON settlement_allocation (installment_id);

-- =====================================================================================
-- settlement_credit_application — which credit source was consumed by which settlement.
-- =====================================================================================
CREATE TABLE settlement_credit_application (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    settlement_id      UUID          NOT NULL REFERENCES settlement (id),
    contract_credit_id UUID          NOT NULL REFERENCES contract_credit (id),
    amount             NUMERIC(19,2) NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    created_by         UUID          NULL REFERENCES app_user (id),
    updated_at         TIMESTAMPTZ   NOT NULL,
    updated_by         UUID          NULL REFERENCES app_user (id),
    CONSTRAINT uk_settlement_credit_application UNIQUE (settlement_id, contract_credit_id),
    CONSTRAINT ck_settlement_credit_application_amount CHECK (amount > 0)
);

-- =====================================================================================
-- journal_entry / journal_line — double-entry ledger (03_DOMAIN_MODEL.md §1.13, ADR-002).
-- IMMUTABLE: UPDATE/DELETE are blocked by triggers; corrections use reversal entries
-- (reversal_of_id). journal_line.entry_date is denormalized from journal_entry so the
-- documented (account_code, entry_date) index can be served; because lines are immutable,
-- it cannot drift after insert (set by the C1 posting service).
-- =====================================================================================
CREATE TABLE journal_entry (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_date     TIMESTAMPTZ  NOT NULL,
    ref_type       VARCHAR(40)  NOT NULL,
    ref_id         UUID         NOT NULL,
    description    VARCHAR(255) NULL,
    reversal_of_id UUID         NULL REFERENCES journal_entry (id),
    posted_at      TIMESTAMPTZ  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     UUID         NULL REFERENCES app_user (id),
    updated_at     TIMESTAMPTZ  NULL,
    updated_by     UUID         NULL REFERENCES app_user (id)
);

CREATE INDEX idx_journal_entry_ref ON journal_entry (ref_type, ref_id);
CREATE INDEX idx_journal_entry_reversal ON journal_entry (reversal_of_id);

CREATE TABLE journal_line (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id UUID          NOT NULL REFERENCES journal_entry (id),
    entry_date       TIMESTAMPTZ   NOT NULL,
    account_code     VARCHAR(30)   NOT NULL REFERENCES accounts (code),
    debit            NUMERIC(19,2) NOT NULL DEFAULT 0,
    credit           NUMERIC(19,2) NOT NULL DEFAULT 0,
    contract_id      UUID          NULL REFERENCES contract (id),
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_journal_line_non_negative CHECK (debit >= 0 AND credit >= 0),
    CONSTRAINT ck_journal_line_not_zero CHECK (NOT (debit = 0 AND credit = 0)),
    CONSTRAINT ck_journal_line_not_both CHECK (NOT (debit > 0 AND credit > 0))
);

CREATE INDEX idx_journal_line_entry ON journal_line (journal_entry_id);
CREATE INDEX idx_journal_line_account_date ON journal_line (account_code, entry_date);

-- =====================================================================================
-- document_number_counter (03_DOMAIN_MODEL.md §1.14, TECH SPEC §1.1) — transactional,
-- concurrency-safe business number generator (story A4). Gaps after rollback are allowed;
-- uniqueness over gapless numbering.
-- =====================================================================================
CREATE TABLE document_number_counter (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    counter_key  VARCHAR(80)  NOT NULL,
    last_value   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   UUID         NULL REFERENCES app_user (id),
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_document_number_counter_key UNIQUE (counter_key)
);

-- =====================================================================================
-- system_parameter (Addendum §1.2–1.3). Append-only configuration: latest row with
-- effective_date <= today wins; seeds below are portfolio defaults.
-- =====================================================================================
CREATE TABLE system_parameter (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    param_key       VARCHAR(60)  NOT NULL,
    param_value     VARCHAR(200) NOT NULL,
    effective_date  DATE         NOT NULL,
    description     TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      UUID         NULL REFERENCES app_user (id),
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_system_parameter UNIQUE (param_key, effective_date)
);

-- =====================================================================================
-- idempotency_keys (03_DOMAIN_MODEL.md §1.15). Uniqueness per (endpoint, key) is the
-- retry-safety net for POST payment/settlement/credit-application (TECH SPEC §2.2/§5).
-- =====================================================================================
CREATE TABLE idempotency_keys (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    key           VARCHAR(80)  NOT NULL,
    endpoint      VARCHAR(120) NOT NULL,
    request_hash  VARCHAR(64)  NULL,
    response_json TEXT         NULL,
    status        VARCHAR(20)  NULL,
    expires_at    TIMESTAMPTZ  NULL,
    request_id    VARCHAR(64)  NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    UUID         NULL REFERENCES app_user (id),
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_idempotency_key_endpoint UNIQUE (endpoint, key)
);

-- =====================================================================================
-- refresh_token (Addendum §3.1). Hashed at rest, rotated on use.
-- =====================================================================================
CREATE TABLE refresh_token (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID         NOT NULL REFERENCES app_user (id),
    token_hash     VARCHAR(64)  NOT NULL,
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ  NULL,
    replaced_by_id UUID         NULL REFERENCES refresh_token (id),
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     UUID         NULL REFERENCES app_user (id),
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     UUID         NULL REFERENCES app_user (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

-- =====================================================================================
-- job_run (Addendum §10) — one row per daily job execution (billing/penalty/aging/
-- consistency check) so scheduling is observable and auditable.
-- =====================================================================================
CREATE TABLE job_run (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    job_name          VARCHAR(60)  NOT NULL,
    started_at        TIMESTAMPTZ  NOT NULL,
    finished_at       TIMESTAMPTZ  NULL,
    records_processed INT          NOT NULL DEFAULT 0,
    records_failed    INT          NOT NULL DEFAULT 0,
    status            VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        UUID         NULL REFERENCES app_user (id),
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        UUID         NULL REFERENCES app_user (id),
    CONSTRAINT ck_job_run_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_job_run_name_started ON job_run (job_name, started_at);

-- =====================================================================================
-- reconciliation_exception (Addendum §7.2). The consistency-check job only records
-- discrepancies; it never auto-corrects.
-- =====================================================================================
CREATE TABLE reconciliation_exception (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    contract_id      UUID          NOT NULL REFERENCES contract (id),
    check_date       DATE          NOT NULL,
    check_type       VARCHAR(40)   NOT NULL,
    expected_amount  NUMERIC(19,2) NOT NULL,
    actual_amount    NUMERIC(19,2) NOT NULL,
    diff_amount      NUMERIC(19,2) NOT NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    resolved_note    TEXT          NULL,
    resolved_by      UUID          NULL REFERENCES app_user (id),
    resolved_at      TIMESTAMPTZ   NULL,
    created_at       TIMESTAMPTZ   NOT NULL,
    created_by       UUID          NULL REFERENCES app_user (id),
    updated_at       TIMESTAMPTZ   NOT NULL,
    updated_by       UUID          NULL REFERENCES app_user (id),
    CONSTRAINT ck_recon_exception_type CHECK (check_type IN
        ('PAYMENT_CASH', 'ALLOCATION_PAYMENT', 'RECEIVABLE_INSTALLMENT')),
    CONSTRAINT ck_recon_exception_status CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED'))
);

CREATE INDEX idx_recon_exception_contract_date ON reconciliation_exception (contract_id, check_date);

-- =====================================================================================
-- outbox_events (ADR-001 mitigasi; 03_DOMAIN_MODEL.md §1.15). Append-only event queue:
-- publisher (Fase 3) drains PENDING with retry_count/status. Table only in Sprint 0 —
-- no Kafka/infra yet.
-- =====================================================================================
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(60)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(80)  NOT NULL,
    payload_json   JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sent_at        TIMESTAMPTZ  NULL,
    retry_count    INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     UUID         NULL REFERENCES app_user (id),
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     UUID         NULL REFERENCES app_user (id),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);

-- =====================================================================================
-- Immutability triggers (posted/append-only records — see ADR-002, Domain Model §1.8/1.10/
-- 1.13 & invariant 12): journal_entry, journal_line, payment_allocation,
-- settlement_allocation, penalty_adjustment.
-- =====================================================================================
CREATE TRIGGER trg_journal_entry_immutable
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION block_modification();

CREATE TRIGGER trg_journal_line_immutable
    BEFORE UPDATE OR DELETE ON journal_line
    FOR EACH ROW EXECUTE FUNCTION block_modification();

CREATE TRIGGER trg_payment_allocation_immutable
    BEFORE UPDATE OR DELETE ON payment_allocation
    FOR EACH ROW EXECUTE FUNCTION block_modification();

CREATE TRIGGER trg_settlement_allocation_immutable
    BEFORE UPDATE OR DELETE ON settlement_allocation
    FOR EACH ROW EXECUTE FUNCTION block_modification();

CREATE TRIGGER trg_penalty_adjustment_immutable
    BEFORE UPDATE OR DELETE ON penalty_adjustment
    FOR EACH ROW EXECUTE FUNCTION block_modification();

-- =====================================================================================
-- Seed: SYSTEM bootstrap user (Addendum §3.3). Non-interactive job principal; its
-- password_hash "{noop}!" can never authenticate (literal-match against "!" only).
-- =====================================================================================
INSERT INTO app_user (id, username, password_hash, full_name, role, is_active,
                      failed_login_attempts, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001',
        'SYSTEM', '{noop}!', 'System Job Principal', 'SYSTEM', TRUE, 0,
        clock_timestamp(), clock_timestamp());

-- =====================================================================================
-- Seed: chart of accounts (03_DOMAIN_MODEL.md §1.12 / ADR-002, incl. Addendum §2.3/§4.3).
-- =====================================================================================
INSERT INTO accounts (code, name, account_type, created_at, updated_at) VALUES
    ('KAS',                       'Cash on hand',                         'ASSET',    clock_timestamp(), clock_timestamp()),
    ('PIUTANG_POKOK',             'Receivable — principal',               'ASSET',    clock_timestamp(), clock_timestamp()),
    ('PIUTANG_BUNGA',             'Receivable — interest (recognized)',   'ASSET',    clock_timestamp(), clock_timestamp()),
    ('PIUTANG_DENDA',             'Receivable — penalty',                 'ASSET',    clock_timestamp(), clock_timestamp()),
    ('TITIPAN_NASABAH',           'Customer credit / overpayment',        'LIABILITY', clock_timestamp(), clock_timestamp()),
    ('PENDAPATAN_BUNGA',          'Revenue — interest',                   'INCOME',   clock_timestamp(), clock_timestamp()),
    ('PENDAPATAN_DENDA',          'Revenue — penalty',                    'INCOME',   clock_timestamp(), clock_timestamp()),
    ('PENDAPATAN_ADMIN',          'Revenue — settlement admin fee',       'INCOME',   clock_timestamp(), clock_timestamp()),
    ('DISKON_PELUNASAN',          'Expense — settlement rebate',          'EXPENSE',  clock_timestamp(), clock_timestamp()),
    ('BIAYA_PENGHAPUSAN_PIUTANG', 'Expense — receivable write-off',       'EXPENSE',  clock_timestamp(), clock_timestamp());

-- =====================================================================================
-- Seed: system_parameter (Addendum §1.3) — portfolio defaults; append new rows (with a
-- new effective_date) instead of editing these to adjust configuration.
-- =====================================================================================
INSERT INTO system_parameter (param_key, param_value, effective_date, description, created_at, updated_at) VALUES
    ('DEFAULT_GRACE_PERIOD_DAYS',   '3',      DATE '2026-01-01', 'Grace period (days) before penalty accrual starts', clock_timestamp(), clock_timestamp()),
    ('DEFAULT_PENALTY_RATE_DAILY',  '0.0010', DATE '2026-01-01', 'Daily penalty rate as decimal fraction (0.1% = 0.0010)', clock_timestamp(), clock_timestamp()),
    ('SETTLEMENT_ADMIN_FEE',        '150000', DATE '2026-01-01', 'Flat settlement admin fee (Rp)', clock_timestamp(), clock_timestamp()),
    ('SETTLEMENT_REBATE_RATE',      '0.5000', DATE '2026-01-01', 'Rebate rate applied to eligible settlement interest', clock_timestamp(), clock_timestamp()),
    ('SETTLEMENT_QUOTE_TTL_MINUTES','15',     DATE '2026-01-01', 'Settlement quote validity (minutes)', clock_timestamp(), clock_timestamp()),
    ('IDEMPOTENCY_KEY_RETENTION_DAYS','7',    DATE '2026-01-01', 'Idempotency-key retention before cleanup', clock_timestamp(), clock_timestamp());