# Product: Serfira

Serfira is the servicing core of a loan management system for an Indonesian multifinance company (vehicle and consumer-goods financing). It covers everything after a loan is approved: installment schedules, payments and allocation, late penalties, aging, early settlement, and a double-entry ledger.

It is a portfolio project built on one principle: **money must always balance**. Financial rules are enforced twice, in pure Java engines and again as PostgreSQL constraints/triggers.

## Core domain concepts
- **Contract**: financing agreement for one customer and one asset (principal, down payment, tenor, rate). Lifecycle: DRAFT → ACTIVE → closed (MATURITY / SETTLEMENT / WRITTEN_OFF).
- **Schedule**: monthly installments generated at activation. `FLAT` (interest on original principal) or `EFFECTIVE` (annuity).
- **Allocation**: payment waterfall is penalty → interest → principal, oldest due installment first. Overpayment becomes customer credit (`TITIPAN_NASABAH`) and is never auto-applied to future installments.
- **Penalty**: daily late fee on overdue amounts after a grace period.
- **Aging**: delinquency buckets Current, 1–30, 31–60, 61–90, >90 days.
- **Ledger**: every financial event posts a balanced, immutable journal entry. Corrections are reversal entries, never updates.

## Key business rules
- Interest is receivable only once billed on its due date. Future scheduled interest is never recognized early.
- Payments bill due interest and accrue penalties lazily in the same transaction, so allocation is correct even if the nightly job has not run.
- `paid_at` and all business timestamps come from the server clock (Asia/Jakarta). Clients cannot backdate or future-date.
- PII (NIK, phone) is encrypted at rest and masked in API responses. Never put plaintext PII in logs, SQL, or URLs.

## Status
- Built: `contract`, `payment`, `penalty`, `ledger`, `shared`.
- Planned: `settlement`, `reporting`, login/RBAC, Next.js frontend (`frontend/` does not exist yet).
- Out of scope: loan origination, credit scoring, treasury, field collection, real payment gateways, multi-currency.

## Source of truth
Specs in `docs/` are authoritative and written in Bahasa Indonesia: PRD (`01_PRD.md`), Tech Spec (`02_TECH_SPEC.md`), Domain Model (`03_DOMAIN_MODEL.md`), Gaps Addendum (`04_GAPS_ADDENDUM.md`), plus ADRs in `docs/adr/`. Code comments cite these (e.g. `TS §2.1`, `DM §1.4`, `PRD P-3`, `ADR-012`). Check the relevant spec/ADR before changing business behavior. Progress is tracked in `docs/PROGRESS.md` and `docs/tasks.md`.
