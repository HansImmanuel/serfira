# Serfira tasks

Canonical implementation backlog. From Sprint 4b onward this file supersedes the per-sprint allocation in
`05_SPRINT_PLAN.md`. The story IDs used there (A1…G6) are kept as cross-references. Business rules stay in
`01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → ADRs. This file points to
those documents and does not restate them.

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`.
Estimates use the sprint plan's points (1 pt ≈ 2–3 h; target velocity 8–13 pts per sprint).

Completed-task detail (full implementation notes for DONE work, the resolved X-table, the CR-01…CR-16
review table, and historical verification snapshots) is archived in `tasks-archive.md`. This file keeps the
live backlog: the current state snapshot, the compact Completed Work index, open tasks, and open
ambiguities.

---

## Current Project State

**Snapshot:** `main` @ `6186fc1` (T16 merged via PR #11) plus the T17 implementation (F1 consistency check:
a fourth read-only `consistency-check` daily step reconciling the three Addendum §7.1 invariants per contract
through the pure `ConsistencyCheckEngine`, recording drift to `reconciliation_exception` and never fixing it;
three new read ports — `payment.PaymentReconciliationPort`, `ledger.ContractLedgerBalancePort`,
`contract.ReceivableReconciliationPort`; `penalty`-owned `ReconciliationException` writer; no migration, no
HTTP endpoint) on branch `t17-consistency-check-job`, pending PR. Earlier baseline detail below is retained
from the T16/T13 snapshots.

**Pre-T16 baseline:** `main` @ `0b282c0` plus the T7 implementation (ADR-015: RBAC matrix, roles-claim converter,
fail-closed JWT identity, `support/TestJwts`, retired audit probe), the T8 implementation (new read-only
`reporting` module with the aging report), the T9 implementation (contract statement read through a new
`ledger` statement port, V11 index), the T24 implementation (ADR-017: idempotency keys are single-use
forever, 409 `IDEMPOTENCY_KEY_EXPIRED`, the payment classifier no longer retries `uq_payment_idempotency`,
`BACKOFF_MILLIS` tidied), T25 (V12 accounting backstops), T26 (V13 `ABANDONED` + daily job robustness),
T29 (statement query `cast` fix), and the T11 implementation (Phase-1 test hardening: child-table
immutability tests, specific-exception assertions, `Phase1ExitScenariosIT`, `PiiLoggingIT`) — all on branch
`t11-phase1-exit-verification` @ `a8fa1a6`, pending merge.

**Latest verification — 2026-10-13 (T16), Docker NOT available in the environment, so the Testcontainers
`*IT` suites did not run (per the tech-stack steering fallback):**

- `.\gradlew compileJava compileTestJava`: pass (whole main + test tree compiles with the T16 void path, the
  new `contract` `PaymentVoidPort` + credit-reversal seams, the `ledger` `postReversal` helper, V18, and the
  two new error codes).
- `.\gradlew test --tests "*Test"` (all pure unit suites, no Docker): **BUILD SUCCESSFUL, 57 suites / 427
  tests, 0 failures / 0 errors** (verified by aggregating the JUnit result XML), including the new
  `InstallmentVoidRecomputeTest` (6), `PaymentVoidTest` (4), `ContractReopenFromMaturityTest` (3) and
  `PaymentVoidRetryingServiceTest` (4).
- Not run: the Testcontainers `*IT` suites — specifically the new `PaymentVoidIT` and the extended
  `EndpointRoleMatrixIT`, confirmed to fail only at `DockerClientProviderStrategy` (no Docker daemon), not on
  code. They **compile** and must be run under Docker in CI before the PR merges.

Older per-task verification snapshots (T8, T9, T24, T25, T29, T11) live in `tasks-archive.md`.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `shared`. The `settlement`
  module now holds both the quote half (E1, T12) and execution (E2, T13): `POST /api/v1/settlements`
  re-prices and revalidates against the quote snapshot, consumes all available credit, posts the one balanced
  SETTLEMENT journal, settles installments and closes the contract SETTLEMENT. `frontend/` is empty.
- Migrations V1–V18. Baseline + accounting triggers (V3), state coherence (V4), settlement immutability (V5),
  contract-create safety (V6/V7), SYSTEM hardening (V8), ShedLock + penalty-accrual integrity (V9),
  `job_run.business_date` (V10), the `journal_line (contract_id, entry_date)` statement index (V11, T9), the
  parent-side accounting backstops + `uq_journal_entry_event` + `system_parameter` append-only (V12, T25),
  the `ck_job_run_status` widen for `ABANDONED` (V13, T26), the `contract_credit_application` cap +
  status-guard deferred triggers (V14, T14), the `BEBAN_WAIVER_DENDA` COA seed + `penalty_adjustment`
  deferred cap trigger (V15, T15), the penalty-adjustment-cap-includes-paid fix (V16, E5 review), and the
  settlement-execution backstops (V17, T13: `uq_journal_entry_event` extended to `SETTLEMENT`, and the V14
  credit cap/status guards taught to count `settlement_credit_application`), and the all-status
  `uq_payment_idempotency` (V18, T16: drops the `status = 'POSTED'` partial predicate, with a fail-safe
  preflight, so a voided payment's idempotency key stays spent after T18 cleanup — ADR-020 D3).
- Daily servicing (T3, hardened by T26, extended by T17): one renewable ShedLock for cron + backfill; every
  ACTIVE contract processed atomically billing → penalty → aging → consistency-check with five-attempt
  backoff retry, failure isolation, SYSTEM audit, four `job_run` rows; crashed `RUNNING` rows closed
  `ABANDONED`; keyset paging by `serfira.jobs.daily-servicing.batch-size` (default 500). The fourth step
  (T17, F1): read-only reconciliation of the three Addendum §7.1 invariants per contract via the pure
  `ConsistencyCheckEngine`, recording drift to `reconciliation_exception` (never fixing); its
  `records_failed` counts drifted contracts, so the `job_run` row is a portfolio drift alarm.
- Payment path: lazy billing + accrual inside the idempotent transaction (T4, ADR-014); write-path conflict
  retry → 409 `CONCURRENT_MODIFICATION` (T5); single-use-forever idempotency keys → 409
  `IDEMPOTENCY_KEY_EXPIRED` past retention (T24, ADR-017).
- Reads: aging report `GET /api/v1/reports/aging` (T8, read-only `reporting` module) and contract statement
  `GET /api/v1/contracts/{id}/statement` (T9, ledger-literal via `ledger.application.ContractStatementPort`;
  query fixed for PostgreSQL in T29).
- DB accounting backstops (T25, V12): parent-side deferred ≥2-balanced-lines / Σ-allocations checks, scoped
  `uq_journal_entry_event`, `system_parameter` append-only.
- Credit (T14, E3): a payment's EXCESS books a durable `contract_credit` row via `ContractCreditPort`
  (new `payment → contract` edge, sub-ledger not a journal); `POST /api/v1/contracts/{id}/credit/apply`
  applies it to recognized receivable (pure `CreditApplicationEngine`, one `CREDIT_APPLICATION` journal per
  call), `GET /api/v1/contracts/{id}/credit` reads balance + history. V14 enforces the Σ-applications cap
  and the AVAILABLE→APPLIED status rule.
- Penalty waive/reduce (T15, E5): `POST /api/v1/penalty-adjustments` (ADMIN_OPERASIONAL) records an
  append-only `PenaltyAdjustment` and posts one `Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA` entry
  (`PENALTY_WAIVER`). Effective penalty (`max(0, penalty_amount − Σ adjustment)`, invariant 9) is now owned
  by `penalty` and exposed through `EffectivePenaltyPort`; the former native `penalty_adjustment` read in
  `contract` is retired and all read paths go through the port (new `contract → penalty` edge, CR-14
  closed). V15 seeds `BEBAN_WAIVER_DENDA` and adds the deferred Σ-adjustment cap trigger.
- Effective-penalty read consistency (T30, ADR-019 D4, closes PR #7 F1): the schedule response
  (`GET /api/v1/contracts/{id}/installments`) and the aging report (`GET /api/v1/reports/aging`) now net
  active penalty adjustments through `EffectivePenaltyPort` instead of reporting gross, so a
  waived-but-otherwise-paid installment shows zero remaining penalty and no aging bucket, agreeing with the
  payment-receivable snapshot.
- Payment void (T16, E4): a payment's EXCESS/allocation and journal are append-only, so a void is a reversal
  — `POST /api/v1/payments/{id}/void` flips the payment `POSTED → VOIDED`, posts one mirrored reversal
  journal (Dr/Cr swapped, `reversal_of_id` set), recomputes each affected installment from the remaining
  POSTED payments (demoting PAID→PARTIALLY_PAID→PENDING and reopening a MATURITY auto-close), and reverses the
  payment's own EXCESS credit when it is still entirely AVAILABLE (409 `PAYMENT_CREDIT_CONSUMED` otherwise;
  409 `PAYMENT_NOT_VOIDABLE` when an affected installment is SETTLED/WRITTEN_OFF or the contract is not
  ACTIVE). Idempotent; no penalty re-pricing (ADR-020 D2); V18 keeps a voided key spent.
- Endpoints (13 incl. settlement execution): `POST /api/v1/contracts`,
  `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, `POST /api/v1/payments/{id}/void`,
  `GET /api/v1/reports/aging`, `POST /api/v1/contracts/{id}/credit/apply`,
  `GET /api/v1/contracts/{id}/credit`, `POST /api/v1/penalty-adjustments`, `POST /api/v1/settlements/quote`,
  `POST /api/v1/settlements`.
- Security: JWT HS256 resource server, default-deny, Addendum §3.4 role matrix enforced on all thirteen
  endpoints (T7, ADR-015; the two credit rows added by T14, the penalty-adjustment row by T15, the
  settlement-quote row by T12, the settlement-execution row by T13, and the payment-void row by T16 — all
  ADMIN_OPERASIONAL only). `sub` must be a UUID with `exp`. No login/refresh/logout, no `iss`/`aud`
  validation yet (ADR-005; T21).

Full per-task implementation notes for all DONE work are archived in `tasks-archive.md`.

**Current sprint:** Sprint 4 ("Penalty, Aging & Phase-1 Close") is complete. C4/D1 and re-planned T1–T6 are
DONE (Sprint 4b), Sprint 4c (T23, T7, T8, T9) is complete, and **Sprint 4d is complete**: T24 (idempotency
key lifecycle), T25 (DB accounting backstops), T26 (daily job robustness), T29 (fix the pre-existing
statement query, X-13) and T11 (Phase-1 test hardening + exit verification) are all DONE. T10 stays
DEFERRED (T1.a kept ADR-012 decision 2; no code change required). **Sprint 5 (Settlement, Credit & Waiver,
T12–T15) is complete** (2026-10-05, ADR-018 + ADR-019; branches `t14-contract-credit`,
`t15-penalty-waive-effective-port`, `t12-settlement-quote`, `t13-settlement-execution`), as is **T30**
(route the schedule response + aging report through `EffectivePenaltyPort`, closing the ADR-019 D4 / PR #7
F1 follow-up; branch `t30-effective-penalty-reads`). **Sprint 6 (Void, Consistency & Write-off,
T16–T19 + T27a) is planned** (2026-10-11, ADR-020 for void; ADR-017 T18 item resolved; see the Sprint 6
section) and in progress in order T16 → T17 → T19 → T18 → T27a. **T16 (E4 payment void) is DONE** (merged via
PR #11) and **T17 (F1 consistency check) is DONE** (branch `t17-consistency-check-job`, pending PR); T19, T18
and T27a remain TODO. T20 (observability) and the remaining T27 (security scans + Compose/OpenAPI smoke) move
to Sprint 6b alongside T21 (auth).

**Blockers and critical gaps:** none open. The two that remained after T6 (the unenforced Addendum §3.4
matrix, and a non-UUID `sub` writing as `SYSTEM`) were closed by T7 (ADR-015).

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-3** (observability:
`X-Request-Id`, JSON logs, `idempotency_keys.request_id` — open, owned by T20) and **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). The resolved X-table with per-item detail is in `tasks-archive.md`.

---

## Completed Work

These items are preserved and must not be re-implemented. Detail lives in `05_SPRINT_PLAN.md §5` and the
ADRs listed.

| Story | Title                                                                                                                                                                                                                                                                                                                                                                                                                                      | Status | Reference                                                           |
| ----- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------ | ------------------------------------------------------------------- |
| A1    | Repo, CI, docker-compose, README, `docs/adr/`                                                                                                                                                                                                                                                                                                                                                                                              | DONE   | Sprint 0                                                            |
| A2    | Injectable Clock (Asia/Jakarta), audit base, error envelope                                                                                                                                                                                                                                                                                                                                                                                | DONE   | ADR-003                                                             |
| A3    | Flyway baseline, seeds, COA                                                                                                                                                                                                                                                                                                                                                                                                                | DONE   | V1                                                                  |
| A4    | Business document number generator                                                                                                                                                                                                                                                                                                                                                                                                         | DONE   | `DocumentNumberGeneratorIT`                                         |
| —     | Pre-Sprint-2 hardening: PII at rest, resource server                                                                                                                                                                                                                                                                                                                                                                                       | DONE   | ADR-004, ADR-005, V2–V5                                             |
| B1–B4 | Entities, FLAT/EFFECTIVE engine, due dates, golden tests                                                                                                                                                                                                                                                                                                                                                                                   | DONE   | Sprint 1                                                            |
| B5    | Contract create/activate/list/detail/schedule API                                                                                                                                                                                                                                                                                                                                                                                          | DONE   | ADR-006, ADR-007, V6, V7                                            |
| C1    | Ledger posting + disbursement journal at activation                                                                                                                                                                                                                                                                                                                                                                                        | DONE   | ADR-008                                                             |
| C2    | Allocation engine                                                                                                                                                                                                                                                                                                                                                                                                                          | DONE   | ADR-009                                                             |
| C3    | `POST /payments` + idempotency                                                                                                                                                                                                                                                                                                                                                                                                             | DONE   | ADR-010                                                             |
| C4    | Due-date billing + maturity auto-close. **The scheduler half is moved to T3**                                                                                                                                                                                                                                                                                                                                                              | DONE   | ADR-011                                                             |
| D1    | Penalty calculator + per-date idempotent accrual. **The payment-before-job risk is closed by T4; component-exact precision remains deferred in T10**                                                                                                                                                                                                                                                                                       | DONE   | ADR-012, ADR-014                                                    |
| T1–T3 | Phase-1 decisions, V9 integrity/ShedLock schema, daily billing→penalty job + V10 audit date                                                                                                                                                                                                                                                                                                                                                | DONE   | ADR-013, V9/V10                                                     |
| T4    | Lazy penalty accrual in the idempotent payment transaction                                                                                                                                                                                                                                                                                                                                                                                 | DONE   | ADR-014                                                             |
| T5    | Payment write-path conflict retry                                                                                                                                                                                                                                                                                                                                                                                                          | DONE   | ADR-014 decision 7                                                  |
| T6    | Aging status step in the daily job                                                                                                                                                                                                                                                                                                                                                                                                         | DONE   | ADR-013 implementation note T6                                      |
| T23   | Spring Boot 4 dependency alignment (ShedLock 7.10.1, springdoc 3.1.1)                                                                                                                                                                                                                                                                                                                                                                      | DONE   | PR #1, `OpenApiSmokeIT`                                             |
| T7    | RBAC enforcement + JWT identity hardening (roles claim, matcher table, fail-closed `sub`/`exp`)                                                                                                                                                                                                                                                                                                                                            | DONE   | ADR-015 (CR-01, CR-10, X-10)                                        |
| T8    | Aging report (`GET /api/v1/reports/aging`): new read-only `reporting` module, per-installment buckets                                                                                                                                                                                                                                                                                                                                      | DONE   | ADR-013 impl note T8 (A-6/A-7)                                      |
| T9    | Contract statement (`GET /api/v1/contracts/{id}/statement`): ledger-literal rows via a `ledger` read port, filters + paging                                                                                                                                                                                                                                                                                                                | DONE   | ADR-013 impl note T9 (A-8)                                          |
| T24   | Idempotency key lifecycle: single-use forever (option A), 409 `IDEMPOTENCY_KEY_EXPIRED`, classifier no longer retries `uq_payment_idempotency`                                                                                                                                                                                                                                                                                             | DONE   | ADR-017 (A-13, CR-04, CR-13)                                        |
| T25   | DB accounting backstops: V12 parent-side deferred checks, scoped `uq_journal_entry_event`, `system_parameter` append-only                                                                                                                                                                                                                                                                                                                  | DONE   | ADR-008 d5 note (CR-05/06/12)                                       |
| T26   | Daily job robustness: `ABANDONED` status + V13, stale-row/loop-escape finalization, keyset batching, retry backoff                                                                                                                                                                                                                                                                                                                         | DONE   | ADR-013 A-9 note T26 (CR-07/08/09)                                  |
| T29   | Fix contract statement query on PostgreSQL: `cast(...)`-typed nullable binds in `findContractStatement`/`countQuery`, clearing `42P18`                                                                                                                                                                                                                                                                                                     | DONE   | X-13 (bug task)                                                     |
| T11   | Phase-1 test hardening + exit verification: child-table immutability tests, specific-exception assertions, PRD §7 scenarios over HTTP, PII log guard                                                                                                                                                                                                                                                                                       | DONE   | commit `a8fa1a6`                                                    |
| T14   | E3 Excess → `contract_credit` + credit-apply/read endpoints: `ContractCreditPort`, `CreditApplicationEngine`, V14 cap/status triggers, RBAC rows                                                                                                                                                                                                                                                                                           | DONE   | ADR-009, Addendum §2 (branch `t14-contract-credit`)                 |
| T15   | E5 Penalty waive/reduce + `EffectivePenaltyPort`: `PenaltyAdjustment` entity, `POST /penalty-adjustments`, `BEBAN_WAIVER_DENDA`/V15 cap, retired contract native read (CR-14)                                                                                                                                                                                                                                                              | DONE   | ADR-019, Addendum §16.4 (branch `t15-penalty-waive-effective-port`) |
| T30   | Route schedule response + aging report through the adjustment-aware effective penalty via `EffectivePenaltyPort` (closes ADR-019 D4 / PR #7 F1); schedule `penalty_amount` + aging outstanding now net active waivers                                                                                                                                                                                                                      | DONE   | ADR-019 D4 (branch `t30-effective-penalty-reads`)                   |
| T12   | E1 Settlement quote: new `settlement` module, pure `SettlementQuoteEngine` (ADR-018 D2/D6/D7), `POST /settlements/quote`, new `contract` seams `SettlementReceivablePort` + `ContractCreditPort.availableCredit`, accrue-before-resolve, no journal/no migration                                                                                                                                                                           | DONE   | ADR-018 (branch `t12-settlement-quote`)                             |
| T13   | E2 Settlement execution: `POST /api/v1/settlements`, re-price + revalidate (ADR-018 D9), one balanced SETTLEMENT journal (D1/D4), `settlement`/`settlement_allocation`/`settlement_credit_application` writers, pure `SettlementResolutionEngine` (D5), consume-all-credit + `SettlementClosePort` SETTLED-close (D10), V17 (`uq_journal_entry_event`+SETTLEMENT, credit guards count settlement consumption), 4 new error codes, RBAC row | DONE   | ADR-018 (branch `t13-settlement-execution`)                         |
| T16   | E4 Payment void: `POST /api/v1/payments/{id}/void`, mirrored reversal journal (`LedgerPostingService.postReversal`), installment recompute-from-remaining-POSTED + MATURITY reopen (`PaymentVoidPort`), conditional AVAILABLE-credit reversal under row lock (`ContractCreditPort.reverseAvailableCreditFromAllocations`), 2 new error codes, V18 all-status `uq_payment_idempotency`, RBAC row                                            | DONE   | ADR-020 (branch `t16-payment-void`)                                 |
| T17   | F1 Consistency check job: fourth daily step (`consistency-check`), read-only three-check reconciliation (PAYMENT_CASH / ALLOCATION_PAYMENT / RECEIVABLE_INSTALLMENT) via pure `ConsistencyCheckEngine`, drift recorded to `reconciliation_exception` (never fixed), three new read ports (`PaymentReconciliationPort`, `ContractLedgerBalancePort`, `ReceivableReconciliationPort`), no migration, no endpoint                             | DONE   | Addendum §7.1 (branch `t17-consistency-check-job`)                  |
| —     | Phase A hygiene: V8 SYSTEM hardening, idempotency retention takeover (semantics revisited in T24), open-in-view off                                                                                                                                                                                                                                                                                                                        | DONE   | `cdce254`                                                           |

---

## Sprint 4 — Phase-1 Close (4b/4c/4d): COMPLETE

All of Sprint 4's re-planned work is DONE and indexed in the Completed Work table above. Full per-task
specs and implementation notes are archived in `tasks-archive.md`. Summary of the three sub-sprints:

- **4b — Penalty correctness & daily job (T1–T6, DONE).** Phase-1 decisions (ADR-013); V9 ShedLock +
  penalty-accrual integrity; the daily billing → penalty → aging job; lazy accrual in the payment path
  (ADR-014); payment write-path conflict retry; the aging step. Exit met: PRD scenario 2 end-to-end with no
  manual accrual, a same-date rerun writes nothing, `job_run` rows per step.
- **4c — Stack alignment, access control & Phase-1 reads (T23, T7, T8, T9, DONE).** Boot 4 dependency
  alignment (ShedLock 7.10.1, springdoc 3.1.1); RBAC + fail-closed JWT identity (ADR-015); the aging report;
  the contract statement. Exit met: Addendum §3.4 matrix enforced, `/v3/api-docs` covered by a test.
- **4d — Integrity hardening & exit (T24, T25, T26, T29, T11, DONE; T10 DEFERRED).** Idempotency key
  lifecycle (ADR-017); DB accounting backstops (V12); daily-job robustness (V13); the statement-query
  PostgreSQL fix; Phase-1 test hardening + exit verification. Phase-1 exit criteria proven (PRD §7).

### T10 — Component-exact penalty base (conditional, DEFERRED)

Status: DEFERRED — T1.a / ADR-013 kept ADR-012 decision 2 unchanged, so no code change is required unless a
**new** business decision explicitly demands component-exact penalty precision (base from unpaid principal +
recognized interest only, excluding penalty already paid, per a literal TS §4.3 reading; and/or a per-date
historical base). If that decision is ever taken, this task reopens at ~3 pts: carry the component split (and
business dates, if a historical base is chosen) across the `contract` port, change `InstallmentBalance.penaltyBase`,
and prove the next-day charge after a penalty-only payment equals `rate × unpaid (principal + recognized
interest)` (worked check: 1,573.33, not 1,570.19). Depends on T1.a, T4. ADR-012 alternatives 5/6 list the
rejected options T1.a would have to supersede.

## Sprint 5 — Settlement, Credit & Waiver (Epic E): PLANNED

Planned 2026-10-05 via `/grill-with-docs`. Decisions recorded in **ADR-018** (settlement quote/execution
semantics) and **ADR-019** (effective-penalty port), with new/clarified GLOSSARY terms. Implementation
order is **T14 → T15 → T12 → T13**: credit and waiver are upstream of settlement, which must price against
the final effective-penalty formula and be able to consume credit. One branch and one PR per task.

**Scope split (confirmed):** Sprint 5 = T12–T15 (11 pts). T16 (void, 5 pts) stays in Sprint 6 — it depends
on the credit-consumed check (invariant 16) that T14 establishes.

**Module placement:** a new `settlement` module (`api/application/domain/infrastructure`, ADR-001)
depending on `ledger`, `contract` (ports), `penalty` (`EffectivePenaltyPort`). Credit apply lives in
`contract` (data owner of `contract_credit`); waive/reduce lives in `penalty` (data owner of
`penalty_adjustment`). TS §1 dependency rules gain the `settlement` edges.

**Shared facts (verified against the schema, 2026-10-05; see
`.agents/tasks/sprint5-settlement/ledger-conventions.md`):** all settlement/credit tables already exist in
V1 (`contract_credit`, `contract_credit_application`, `settlement_quote`, `settlement`,
`settlement_allocation`, `settlement_credit_application`) with full columns, CHECK constraints and
immutability triggers (V5). `penalty_adjustment` also exists (V1) with its immutability trigger and is
read natively by the V3 cap trigger and `InstallmentRepository`. `LedgerRefType` already includes
`SETTLEMENT`, `PENALTY_WAIVER`, `CREDIT_APPLICATION`. The COA is complete (incl. `PENDAPATAN_BUNGA`,
`PENDAPATAN_ADMIN`, `DISKON_PELUNASAN`). So Sprint 5 adds **behaviour + Java modules**, not tables; its
migrations are thin.

### T14 — E3 Excess → `contract_credit` + credit-apply endpoint (5 pts) — DONE

**Status: DONE** (branch `t14-contract-credit`). Implementation note: `ContractCreditPort` (new
`payment → contract` edge) books one AVAILABLE `contract_credit` per EXCESS allocation (sub-ledger, not a
second journal); `POST /api/v1/contracts/{id}/credit/apply` (ADMIN*OPERASIONAL) applies available credit
via the new pure `CreditApplicationEngine` (PENALTY → INTEREST → PRINCIPAL, oldest due first), reducing
only recognized receivable through `InstallmentReceivablePort.applyPaymentResolution` and posting one
`Dr TITIPAN_NASABAH / Cr PIUTANG*\*` entry (`ref_type=CREDIT_APPLICATION`, `ref_id`= first application id;
service guard, ADR-008 d5);`GET /api/v1/contracts/{id}/credit`(ADMIN_OPERASIONAL + FINANCE) returns
balance + history. Status flips AVAILABLE → APPLIED only at balance 0. Migration V14 adds the deferred`Σ applications ≤ amount`cap (invariant 11) + the status guard (no`CREATE TABLE`, `uq_journal_entry_event`untouched). Optimistic-lock retry`ContractCreditRetryingService` (3 attempts, 50/150 ms). Verification
(Docker available):`CreditApplicationEngineTest`(16) +`ContractCreditIT`(10) +`EndpointRoleMatrixIT`+`OpenApiSmokeIT`pass; full`./gradlew test`= **665 tests, 0 failures**;`./gradlew check` green.

**Dependencies:** none open (C2/C3 EXCESS path DONE; tables exist). First task of Sprint 5.

**Scope.** Turn the existing `EXCESS` payment allocation into a durable `contract_credit` liability, and
add an explicit endpoint to apply available credit to recognized receivable. Owner module: `contract`.

**Business rules (Addendum §2, §16.3; ADR-009; GLOSSARY ContractCredit).**

- When a payment produces an `EXCESS` allocation, write one `contract_credit` row
  (`status = AVAILABLE`, `amount = excess`, `source_payment_allocation_id` = the EXCESS allocation,
  `uk_contract_credit_source` already enforces one credit per EXCESS allocation). This is the first writer
  of the table; the EXCESS→`TITIPAN_NASABAH` journal is unchanged (ADR-008; the credit row is the
  liability's sub-ledger, not a second journal).
- Available balance = `amount − Σ contract_credit_application.amount`. Status moves `AVAILABLE → APPLIED`
  only when the balance reaches 0. `REFUNDED` is out of MVP.
- `POST /api/v1/contracts/{id}/credit/apply` applies available credit to the **oldest installment with
  recognized receivable**, reusing the payment waterfall **PENALTY → INTEREST → PRINCIPAL** (ADR-009),
  oldest due first (`due_date`, tie-break `period_no`). Partial application is allowed: a single call may
  apply less than the full balance (caller supplies an `amount`, or the endpoint applies the full balance
  — pin in the request DTO), and one credit may fund many applications across installments. Credit is
  **never auto-applied**; it only moves on an explicit call (invariant 6, PRD §5.4).
- Application only reduces **recognized** receivable (never future unrecognized interest). Journal per
  apply call: `Dr TITIPAN_NASABAH / Cr PIUTANG_DENDA|PIUTANG_BUNGA|PIUTANG_POKOK` (`ref_type = CREDIT_APPLICATION`,
  `ref_id` = the application or a batch id — pin one entry per apply call). Resolution raises
  `installment.paid_amount` through `InstallmentReceivablePort.applyPaymentResolution` (same mutator as a
  payment; it already derives PAID/PARTIALLY_PAID and the MATURITY close).
- `GET /api/v1/contracts/{id}/credit` returns balance + application history.
- RBAC (Addendum §3.4): `POST …/credit/apply` ADMIN_OPERASIONAL only; `GET …/credit`
  ADMIN_OPERASIONAL + FINANCE. Add both rows to `ResourceServerSecurityConfiguration` and
  `EndpointRoleMatrixIT`.

**Migration V14.** Add the DB cap enforcing `Σ contract_credit_application.amount ≤ contract_credit.amount`
(invariant 11, not enforced today) — a deferred constraint trigger mirroring the entity guard. Mirror the
AVAILABLE→APPLIED status rule as a guard. (No `CREATE TABLE`.) If one-entry-per-apply is enforced at the DB
level, this is also where `CREDIT_APPLICATION` could be added to `uq_journal_entry_event` — decide during
implementation; default is to rely on the service guard (ADR-008 d5), consistent with how PAYMENT behaves.

**Acceptance criteria.**

- An overpayment books a `contract_credit` AVAILABLE row equal to the EXCESS amount, linked to its source
  allocation; no second journal beyond the existing EXCESS→`TITIPAN_NASABAH`.
- Applying credit to the oldest recognized installment posts a balanced `Dr TITIPAN_NASABAH / Cr PIUTANG_*`
  entry, raises `paid_amount`, derives the right installment status, and records a
  `contract_credit_application` row. Partial application leaves the credit AVAILABLE with reduced balance;
  full application flips it to APPLIED.
- Applying more than the available balance, or against an installment with no recognized receivable, is
  rejected (400/409) and writes nothing.
- The DB cap rejects `Σ applications > amount` even via raw SQL.
- Role matrix enforced; denied calls store nothing.

**Tests.** Unit: a credit-apply allocator mirrors `PaymentAllocationEngine` ordering (pure). IT
(`ContractCreditIT`, Testcontainers, real commits): overpayment→credit; partial then full apply across two
installments; over-apply rejected; apply-with-no-recognized-receivable rejected; journal balances and
Σ allocations rule; raw-SQL cap violation; role matrix cells. Reuse `TestJwts`, `FixedClock`.

### T15 — E5 Penalty waive/reduce + `EffectivePenaltyPort` (2 pts → ~3 with the seam)

**Status: DONE** (branch `t15-penalty-waive-effective-port`). Implementation note: new `PenaltyAdjustment`
JPA entity (`penalty.domain`, extends `Auditable`; append-only, V1 immutability trigger) + repository;
`PenaltyAdjustmentService` (`@Transactional`) records a WAIVE/REDUCE and posts one correcting entry
**`Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA`** (`ref_type=PENALTY_WAIVER`, `ref_id=penalty_adjustment.id`),
with `approved_by` from the JWT `sub` via `AuditContext` (never the body); `POST /api/v1/penalty-adjustments`
(ADMIN_OPERASIONAL only). **Expense account = new `BEBAN_WAIVER_DENDA` (EXPENSE), seeded in V15** — not a
reuse of `DISKON_PELUNASAN`, which ADR-018 D3 reserves for the settlement rebate (reusing it would conflate
two economic events). New `penalty.application.EffectivePenaltyPort` /`EffectivePenaltyService`
(`effective = max(0, penalty_amount − Σ adjustment)`, invariant 9, clamped ≥ 0) is now the single
application source; the native `InstallmentRepository.sumPenaltyAdjustmentsByInstallmentIds` read is
**removed** and both call sites (`InstallmentReceivableService`, `ContractCreditCommandService`) route through
the port — new `contract → penalty` port edge, closes CR-14. Over-waive → 409 `CONFLICT`, writes nothing.
Migration **V15** (no `CREATE TABLE`): seeds `BEBAN_WAIVER_DENDA` + adds the deferred cap trigger
`assert_penalty_adjustment_cap` (`Σ penalty_adjustment.amount ≤ installment.penalty_amount`, standalone
because V3's cap only fires on `payment_allocation`); **`uq_journal_entry_event` was NOT extended** — the
`LedgerPostingService` service guard already enforces one entry per `(ref_type, ref_id)` (ADR-008 d5,
mirrors T14). `ContractInstallmentTotals` still omits adjustments (known follow-up, ADR-019). Test change:
`PaymentApiIT.anUnusableReceivableSnapshotIsRejectedAndRollsTheWholePaymentBack` was repurposed to
`anOverWaiverExceedingAccruedPenaltyIsRejectedByTheDbCap` — the old corrupt-snapshot premise is now
unreachable because the V15 cap rejects the over-waive seed at commit (P0001); the test now asserts that
cap directly (invariant 9 genuinely changed, so this is a spec-driven expected-value change, not a
weakened test). Verification (Docker available): `EffectivePenaltyFormulaTest` (5, no Docker) +
`PenaltyAdjustmentIT` (7, incl. port-parity) + `EndpointRoleMatrixIT` + `LedgerPostingIT` +
`LedgerAccountNameIT` pass; full `./gradlew test` = **680 tests across 84 suites, 0 failures / 0 errors**
(up from 665 after T14); `./gradlew check` green.

**Dependencies:** none open (`penalty_adjustment` table + V3 cap exist). Do after T14, before T12.

**Scope.** Add the first writer of `penalty_adjustment` (waive/reduce), expose effective penalty through a
`penalty`-owned port, and retire the temporary native read in `contract`. Owner module: `penalty`.
Decisions in **ADR-019**.

**Business rules (Addendum §16.4; ADR-019; GLOSSARY PenaltyAdjustment, effective_penalty).**

- New `PenaltyAdjustment` JPA entity in `penalty.domain` (extends `Auditable` — the table has
  `updated_at NOT NULL`, ADR-008 correction). Append-only (`WAIVE`/`REDUCE`, `amount > 0`, required
  `reason`, `approved_by` = JWT `sub`); V1 immutability trigger already blocks UPDATE/DELETE.
- `POST /api/v1/penalty-adjustments` (ADMIN_OPERASIONAL only, Addendum §3.4) records an adjustment and
  posts a correcting journal `Dr <waiver expense> / Cr PIUTANG_DENDA` (`ref_type = PENALTY_WAIVER`,
  `ref_id = penalty_adjustment.id`). **Pin the expense account during implementation**: reuse
  `DISKON_PELUNASAN` vs. a new `BEBAN_WAIVER_DENDA` (ADR-019 D3 fixes the shape, not the final code; if a
  new account is chosen it is seeded in V15). The waiver reverses recognized penalty receivable, never
  the append-only accrual history.
- Introduce `penalty.application.EffectivePenaltyPort` (`loadEffectivePenalty(contractId)` →
  per-installment `grossAccrued`, `adjustment`, `effective`). Replace the native
  `InstallmentRepository.sumPenaltyAdjustmentsByInstallmentIds` read; `contract` totals, settlement, and
  reporting read effective penalty through this one port (closes CR-14). The V3 cap trigger stays as the
  DB backstop (two-layer invariant pattern).
- Effective penalty = `penalty_amount − active penalty allocation − Σ adjustment` (invariant 9); waiver
  must not drive effective penalty below 0 (guard + DB check).

**Migration V15.** Thin: the DB cap ensuring `Σ adjustment ≤ gross recognized penalty` per installment
(if not already covered by V3), plus the COA seed **only if** a new waiver expense account is chosen, plus
optionally extending `uq_journal_entry_event` to `PENALTY_WAIVER`. (No `CREATE TABLE`.)

**Acceptance criteria.**

- A waive/reduce records an append-only `penalty_adjustment` and a balanced `… / Cr PIUTANG_DENDA` entry;
  effective penalty drops by the adjustment and never goes negative.
- A later payment's penalty cap reflects the adjustment (V3 trigger already subtracts it; confirm parity
  with the port).
- The `contract` receivable snapshot's effective penalty now comes through the port; no native
  `penalty_adjustment` read remains in `contract`.
- Role matrix enforced; UPDATE/DELETE of an adjustment rejected at the DB.

**Tests.** Unit: effective-penalty formula (pure), negative-guard. IT (`PenaltyAdjustmentIT`): waive then
pay (cap reflects it); reduce; over-waive rejected; adjustment immutability (SQLSTATE P0001); journal
balances; role matrix. Port parity test: `EffectivePenaltyService` equals the V3 trigger's subtraction.

### T30 — Route schedule response + aging report through the adjustment-and-paid-aware effective penalty (2 pts)

Status: DONE (branch `t30-effective-penalty-reads`; closes the PR #7 review finding F1 and the PR #8 review
findings F1–F4; ADR-019 D4).

**Dependencies:** T15 (`EffectivePenaltyPort` + the paid-aware remaining definition exist).

**Scope.** Close the last read/write inconsistency from ADR-019 D4: the payment-receivable snapshot already
reads effective penalty through `EffectivePenaltyPort`, but two `contract` read paths still show **gross**
penalty, so a waived-but-otherwise-paid installment still appears to owe penalty and can land in an aging
bucket. Route them through the adjustment-aware (and, with F2, paid-aware) effective penalty. Owner module:
`contract` (reads), feeding the per-installment `Σ adjustment` already available via `EffectivePenaltyPort`.

**Business rules (ADR-019 D4; PR #7 review finding F1).**

- `contract.api.InstallmentResponse.from(...)` currently builds the balance with the zero-adjustment
  `InstallmentBalance.of(installment)` and returns `installment.getPenaltyAmount()` (gross) directly; it
  must report the port's effective (remaining) penalty — `max(0, gross − Σ adjustment − Σ paid penalty)` —
  on `penalty_amount`, matching the receivable path, while `outstanding` nets only the Σ adjustment through
  `InstallmentBalance.of(installment, Σ adjustment)` (paidAmount already nets the paid penalty).
- `contract.application.AgingReportSourceService.findOutstandingInstallments(...)` likewise computes
  `InstallmentBalance.of(installment).outstanding()` (zero-adjustment); it must feed the per-installment
  `Σ adjustment` so a fully-waived installment no longer shows positive outstanding / an aging bucket.
- Because the adjustment total is `penalty`-owned, `contract` reads it through `EffectivePenaltyPort`
  (the same source as the write-time cap) — no second formula, no native `penalty_adjustment` read.

**Acceptance criteria.**

- The schedule response and the aging report show the same effective (remaining) penalty as the
  payment-receivable snapshot after a waiver; a fully-waived-but-paid installment shows zero outstanding and
  no aging bucket.
- No second effective-penalty formula is introduced; the adjustment total still flows through
  `EffectivePenaltyPort`.

**Tests.** IT: after a clearing waiver, assert the `GET /contracts/{id}/installments` row and the aging
report agree with the receivable snapshot (zero remaining penalty, no bucket). Reuse `TestJwts`,
`FixedClock`.

**Implementation note (2026-10-07, branch `t30-effective-penalty-reads`).** Both `contract` read paths now
net the per-installment `Σ adjustment` sourced through `EffectivePenaltyPort` — the same source as the
receivable snapshot and the write-time cap. No second effective-penalty formula and no native
`penalty_adjustment` read were introduced; the `contract → penalty` edge already documented in
02_TECH_SPEC.md §1 is unchanged (T30 only widens its in-module consumers), so no spec/ADR dependency-rule
edit was needed.

- `contract.api.InstallmentResponse`: adjustment-and-paid-aware overload `from(Installment, BigDecimal Σ
adjustment, BigDecimal effectivePenalty)` reports the port's effective (remaining) penalty verbatim on
  `penalty_amount` and nets only the Σ adjustment into `outstanding` via
  `InstallmentBalance.of(installment, Σ adjustment)`; the zero-arg `from(Installment)` delegates with the
  gross value. The wire field `penalty_amount` is unchanged in shape; its meaning is the remaining penalty
  after both adjustments and paid penalty.
- `contract.application.ContractQueryService.installments(...)`: injects `EffectivePenaltyPort`, loads the
  `Contract` once, and threads the port's per-installment `effective` into each row, branching on status —
  DRAFT → empty (no schedule), ACTIVE → `loadEffectivePenalty` (409-guarded), closed →
  `loadEffectivePenaltyAnyStatus`. Every status returns 200.
- `contract.application.AgingReportSourceService`: injects `EffectivePenaltyPort` and nets the Σ adjustment
  into `outstanding` with a single batch call `loadEffectivePenalty(Collection<UUID>)` across all ACTIVE
  contracts, so a fully-waived-but-paid installment nets to zero and leaves no bucket.
- IT `contract.EffectivePenaltyReadConsistencyIT`: a clearing waiver drops period 1 from the schedule row,
  its 1-30 aging bucket, and matches the receivable snapshot; a PARTIAL penalty payment with no waiver makes
  the schedule `penalty_amount` equal the port's `gross − paid penalty` (not gross); a closed (SETTLEMENT)
  contract with a prior waiver still reports the net penalty; and the schedule endpoint stays 200 for DRAFT
  and CLOSED contracts.

**PR #8 review fixes (branch `t30-effective-penalty-reads`).** The PR #8 review raised four findings; all
four were validated against the code and fixed:

- **F1** — `InstallmentResponse` recomputed a second formula `gross − Σ adjustment` and dropped the
  `− Σ paid penalty` term, so a partially-paid penalty with no waiver reported gross. Fixed by reporting the
  port's `effective` verbatim (see above); the IT gained the partial-paid case the clearing-waiver test hid.
- **F2** — a closed contract defaulted adjustments to zero, so its schedule reported gross penalty for a
  previously-waived installment (gross `penalty_amount` is not zeroed on close). Fixed with status-agnostic
  port reads (`EffectivePenaltyPort.loadEffectivePenaltyAnyStatus`, `InstallmentPenaltyPort
.loadPenaltySnapshotAnyStatus`) and a closed branch in `ContractQueryService`.
- **F3** — the aging report called the port once per ACTIVE contract. Fixed with a batch port method
  (`loadEffectivePenalty(Collection<UUID>)`, backed by `InstallmentPenaltyPort.loadPenaltySnapshots`) called
  once, reusing one shared `EffectivePenaltyService` helper.
- **F4** — doc/OpenAPI drift: the aging `@Operation` description, the TECH_SPEC §1 effective-penalty formula
  (added the paid-penalty term), this note, and the ADR-019 D4 "every consumer" wording (now names the
  completed read paths and retains the `ContractInstallmentTotals` gross-penalty exception).

**Verification (Docker available so the `*IT` suites ran).** Narrowest
`.\gradlew test --tests "*EffectivePenaltyReadConsistencyIT" --tests "*PenaltyAdjustmentIT" --tests
"*AgingReportIT" --tests "*ContractApiIT"`: pass. Full `.\gradlew test`: **87 suites / 697 tests / 0 failures / 0 errors** (the PR #8 baseline was 87 suites / 695
tests; this adds the two new `EffectivePenaltyReadConsistencyIT` cases to the existing suite). `.\gradlew
check`: pass. Diff is scoped to the two read paths, the response DTO, the two ports and their
implementations, the installment repository (one batch query), the IT, and docs; no migration, no API-shape
change.

### T12 — E1 Settlement quote (5 pts) — DONE

**Status: DONE** (branch `t12-settlement-quote`). Implementation note: new `settlement` module
(`api/application/domain/infrastructure`, ADR-001) with `POST /api/v1/settlements/quote` (ADMIN_OPERASIONAL
only). The command service `SettlementQuoteCommandService` (`@Transactional`) runs **accrue-before-resolve**
first (ADR-013, ADR-018 D8) — `InstallmentBillingPort.billDueInterest` then
`PenaltyAccrualPort.accrueDuePenalty` on one captured business date — then reads the settlement snapshot
through a **new `contract` read seam** `SettlementReceivablePort` (exposes scheduled `interestAmount`,
recognized interest, principal, resolved amount, status flag, and `contract.version`; the ADR-018-authorized
seam that `InstallmentReceivable` deliberately omits), the effective penalty through
`EffectivePenaltyPort` (invariant 9, ADR-018 D8), and the available credit through a **new**
`ContractCreditPort.availableCredit` read. The pure `SettlementQuoteEngine` (domain, no Spring) prices the
components (ADR-018 D2/D6/D7): outstanding principal, unpaid billed interest, active-period running interest
(ACT/30, capped at 30 days), effective penalty, net future interest after the 50% rebate, plus the admin fee;
`gross_amount`/`cash_due` per D6 with `credit_used = 0` (consumption is T13). Config
(`SETTLEMENT_ADMIN_FEE`, `SETTLEMENT_REBATE_RATE`, `SETTLEMENT_QUOTE_TTL_MINUTES`) read via
`SystemParameterService`. The immutable `SettlementQuote` entity (extends `Auditable`, constructor guards
mirror the V1 CHECKs) is persisted QUOTED with `valid_until = quoted_at + TTL` and the snapshotted
`contract_version`; **no journal** and no money move. `quote_no` via `DocumentNumberGenerator`
(`DocumentType.QUOTE`). `SettlementQuoteRetryingService` (3 attempts, 50/150 ms) retries the
accrue-before-resolve optimistic-lock race against the daily job → 409 `CONCURRENT_MODIFICATION`, mirroring
`ContractCreditRetryingService`. **No migration** (the table + V5 immutability already exist since V1). RBAC
row added to `ResourceServerSecurityConfiguration` + `EndpointRoleMatrixIT`. TS §1 gains the
`settlement → contract` / `settlement → penalty` port edges. **No new 409 execution codes** (STALE/EXPIRED/
ALREADY_EXECUTED/CREDIT_EXCEEDS are T13). Verification (**Docker was NOT available in this environment, so
the Testcontainers `*IT` suites did not run**): `./gradlew compileJava compileTestJava` green;
`./gradlew test --tests "*Test"` (all pure unit tests incl. the new `SettlementQuoteEngineTest`, 5 cases:
on-a-due-date, mid-period active split, final period, ACT/30 cap, SETTLED exclusion) **BUILD SUCCESSFUL, 0
failures**. The IT `SettlementQuoteIT` (persisted components + D6 identities, accrue-before-resolve bills
into the quote, snapshot immutability via raw SQL, non-ACTIVE 409, unknown 404, role matrix) is written and
compiles but was **not executed** here; it must run under Docker before merge.

**PR #9 review fixes (branch `t12-settlement-quote`).** The PR #9 review (qodo + CodeRabbit) raised five
findings; each was validated against the code and three were fixed, with the remaining two assessed and
recorded:

- **F1 (HIGH, valid, fixed)** — `SettlementQuoteCommandService.toEngineInstallments` computed
  `resolvedBeyondPenalty = resolvedAmount − paidPenalty − adjustment`. The penalty adjustment (a waiver)
  reduces recognized penalty, not money, so it is never part of `resolvedAmount` (`paid + settled +
written-off`); subtracting it a second time understated interest/principal and overstated
  `gross_amount`/`cash_due` (a fully-paid installment with a fully-waived penalty was quoted as still
  owing the waived sum). Fixed to `resolvedBeyondPenalty = max0(resolvedAmount − paidPenalty)`; the
  adjustment is carried only by `effective`. New pure test `SettlementQuoteCommandServiceMappingTest`
  (3 cases: fully-waived+paid, partial waiver+penalty payment, untouched).
- **F3 (MEDIUM, valid, fixed)** — `SettlementQuoteRetryingService` retried only optimistic-lock failures,
  so the `uk_penalty_accrual` unique-violation race between the quote's accrue step and the daily job
  surfaced as a raw data-integrity error instead of a retry/409. Extended the predicate (renamed
  `isRetryable`) to also treat a `23505` naming `uk_penalty_accrual` as retryable, mirroring
  `PaymentConflictClassifier` (no idempotency constraint to exclude at T12). New pure test
  `SettlementQuoteRetryingServiceTest` (6 cases).
- **F5 (LOW, valid, fixed)** — this PR's markdown reformatting had corrupted untouched T14/T15 prose in
  `tasks.md`/`PROGRESS.md` (`ADMIN*OPERASIONAL`, `PIUTANG*\*`, `\_receivable\*`). Restored the canonical
  `ADMIN_OPERASIONAL` / `PIUTANG_*` / `receivable` identifiers.
- **F2 (MEDIUM, valid, deferred to T13)** — a penalty resolved by a **credit application** raises
  `paid_amount` but writes no PENALTY `payment_allocation`, so it is invisible to `paidPenalty` and is
  carried as interest/principal (and `effective` itself overstates it). This is a pre-existing limitation
  of the `EffectivePenaltyPort` definition (the `ContractInstallmentTotals`/credit-funded-penalty gap
  tracked since T15/ADR-019), not introduced by the quote; closing it needs the credit-funded penalty
  exposed through the penalty/contract seam. Documented in `toEngineInstallments` and left for T13, not
  fixed in this quote-only change.
- **F4 (MEDIUM, by design)** — a payment committing between the quote's reads cannot silently corrupt a
  quote: the accrue-before-resolve step versions the same installment rows, so a true concurrent write
  collides on `@Version` (now retried, F3), and the quote is a TTL snapshot that ADR-018 D9 re-validates
  at T13 execution (`STALE_SETTLEMENT_QUOTE`) before any money moves. No T12 code change.

Re-verification (Docker still unavailable, so `*IT` not run): `./gradlew compileJava compileTestJava`
green; `./gradlew test --tests "*Test"` all pure unit suites pass (0 failures / 0 errors), including the
two new suites and the unchanged `SettlementQuoteEngineTest` (5). `SettlementQuoteIT` only fails to load
its Spring context (no Testcontainers PostgreSQL); CI must run it before merge.

**Dependencies:** T15 (effective penalty via port). New `settlement` module starts here. Decisions in
**ADR-018**.

**Scope.** Price an immutable settlement quote with a TTL and a `contract.version` snapshot, over HTTP.
No execution yet. Owner module: new `settlement`.

**Business rules (ADR-018 D2/D6/D7/D8; Addendum §8, §13, §16.3; config §1).**

- `POST /api/v1/settlements/quote` for an ACTIVE contract. **Accrue-before-resolve first** (ADR-013): in
  the same transaction, bill and accrue through the quote business date so the components price against the
  base actually in force (the settlement analogue of T4).
- Pure `SettlementQuoteEngine` (domain, no Spring) computes, per ADR-018:
  - `outstanding_principal` = Σ unpaid principal incl. future periods;
  - `unpaid_billed_interest` = recognized-but-unpaid interest (real `PIUTANG_BUNGA`);
  - `accrued_interest` (D7) = running interest of the single earliest unbilled active period, ACT/30,
    `min(daysElapsed, 30)/30`, HALF_EVEN; 0 if that period is already billed;
  - `penalty_outstanding` = effective penalty via `EffectivePenaltyPort` (T15/ADR-019);
  - `futureInterestGross` (D2) = `Σ(interestAmount − recognizedInterestAmount) − accrued_interest`
    (the active period's _earned_ slice is excluded — it is in `accrued_interest`; its _unearned_
    remainder and all fully-future periods are included);
  - `rebate_amount` = `round(0.50 × futureInterestGross, HALF_EVEN, 2)`;
  - `admin_fee` = `SETTLEMENT_ADMIN_FEE` (150,000);
  - `available_credit` = Σ AVAILABLE `contract_credit`; `credit_used` set at quote per the credit policy;
  - `gross_amount` = principal + unpaid_billed_interest + accrued_interest + penalty_outstanding +
    (futureInterestGross − rebate_amount) + admin_fee;
  - `cash_due` = gross_amount − credit_used.
- Persist `settlement_quote` (`status = QUOTED`, `valid_until = quoted_at + SETTLEMENT_QUOTE_TTL_MINUTES`
  (15), `contract_version` = current `contract.version`). Component columns are immutable (V5); only
  status/version/audit may change.
- New read seam: add scheduled `interestAmount` to the settlement-facing contract snapshot (the existing
  `InstallmentReceivable` exposes only `recognizedInterestAmount`) so `futureInterestGross` is computable —
  via a new `contract` port method or an added field (ADR-010-style seam; record in TS §1).
- RBAC: `POST /settlements/quote` ADMIN_OPERASIONAL only (Addendum §3.4). Matrix + `EndpointRoleMatrixIT`.

**Migration.** None required for the quote (table + immutability exist). (The `uq_journal_entry_event`
extension belongs to T13, which posts the journal.)

**Acceptance criteria.**

- A quote for the Demo-B contract at month 7 produces components matching the engine golden values; the
  active-period split is correct (earned slice in `accrued_interest`, remainder rebate-eligible).
- `gross_amount` and `cash_due` satisfy the D6 identities; `futureInterestCharged` reconstructs to
  `futureInterestGross − rebate_amount`.
- A mid-period quote (settlement between due dates) charges the elapsed days' running interest in full and
  rebates only the remainder — no double count of the active period.
- Quote snapshot columns are immutable (raw-SQL UPDATE of a component rejected).
- Role matrix enforced.

**Tests.** Unit `SettlementQuoteEngineTest` (golden, Demo-B pinned numbers incl. the mid-period split, zero
future interest at the final period, ACT/30 cap at exactly 30 days, HALF_EVEN rounding of the rebate). IT
`SettlementQuoteIT`: accrue-before-resolve actually bills/accrues; quote persisted with the right TTL and
`contract_version`; snapshot immutability; role matrix. `FixedClock` for determinism.

### T13 — E2 Settlement execution (5 pts) — DONE

**Status: DONE** (branch `t13-settlement-execution`). Implementation note: `POST /api/v1/settlements`
(ADMIN_OPERASIONAL, `Idempotency-Key` header, `quote_id` body) executes a QUOTED quote. The write path
(`SettlementExecutionCommandService`, wrapped by `SettlementExecutionRetryingService` — 3 attempts, 50/150 ms,
outside the transaction, mirroring the quote/payment retry) re-runs accrue-before-resolve, re-prices with the
same pure `SettlementQuoteEngine`, and revalidates every recomputed component **and** `contract.version`
against the stored snapshot (ADR-018 D9) → `STALE_SETTLEMENT_QUOTE`; `clock.now() > valid_until` →
`SETTLEMENT_QUOTE_EXPIRED`; an EXECUTED quote with a different key → `SETTLEMENT_QUOTE_ALREADY_EXECUTED`
(DB backstop `uk_settlement_quote_id`). It consumes **all** AVAILABLE credit (D10) via the new
`ContractCreditPort.consumeAllAvailableCredit` (flips `contract_credit` AVAILABLE→APPLIED, returns the
per-source breakdown); `available_credit > gross` → `CREDIT_EXCEEDS_SETTLEMENT`. It posts **one** balanced
`SETTLEMENT` journal (D1/D4): Dr `KAS` + `TITIPAN_NASABAH`; Cr `PIUTANG_POKOK`/`PIUTANG_BUNGA`/`PIUTANG_DENDA`

- `PENDAPATAN_BUNGA` (accrued + future-charged) + `PENDAPATAN_ADMIN`; no `DISKON_PELUNASAN` (D3); zero
  components emit no line. `settlement_allocation` records receivable resolution only (pure
  `SettlementResolutionEngine`, PENALTY→INTEREST→PRINCIPAL oldest-first, D5); `settlement_credit_application`
  records each consumed source. Open installments move to `SETTLED` and the contract closes `SETTLEMENT`
  through the new contract-owned `SettlementClosePort` (never the payment MATURITY path, D10). Idempotency via
  `IdempotencyService` exactly as `POST /payments` (identical key replays; expired key →
  `IDEMPOTENCY_KEY_EXPIRED`; `uq_settlement_idempotency` the backstop). Four new `ErrorCode`s +
  `SerfiraException` subclasses (all 409 via `GlobalExceptionHandler`). RBAC row added to
  `ResourceServerSecurityConfiguration` + `EndpointRoleMatrixIT`.

**Migration V17** (task text said "V16"; V16 was taken by the E5 review fix, so forward-only numbering made
it V17 — documented in the migration header): extends `uq_journal_entry_event` to include `SETTLEMENT` so the
one-non-reversal-entry-per-settlement rule (D1) is a hard DB backstop, and teaches the V14 credit cap +
status guards to count `settlement_credit_application`, so a credit consumed by a settlement flips to APPLIED
coherently across both the regular-apply (E3) and settlement paths. No `CREATE TABLE`. The superseded golden
`AccountingInvariantsIT.settlementEventsMayStillPostSeveralEntries` was rewritten (not weakened) to assert the
second non-reversal SETTLEMENT entry is now rejected (`23505`) while a reversal is still allowed — the
expected value changed because the spec (ADR-018 D1) intentionally changed.

**Verification — 2026-10-10, Docker NOT available in this environment** (tech-stack steering fallback):
`.\gradlew compileJava compileTestJava` passes; `.\gradlew test --tests "*Test"` = **410 tests, 0 failures /
0 errors** (JUnit XML aggregated), including the new `SettlementResolutionEngineTest` (4) and
`SettlementExecutionRetryingServiceTest` (7). The Testcontainers `*IT` suites — `SettlementExecutionIT`,
`EndpointRoleMatrixIT`, `AccountingInvariantsIT` — were **NOT run** (confirmed to fail only at
`DockerClientProviderStrategy`, no daemon); they **compile** and CI must run them under Docker before merge.

**Dependencies:** T12 (quote), T14 (credit consumption). Decisions in **ADR-018 D1/D4/D5/D9/D10**.

**Scope.** Execute a QUOTED quote into an immutable `settlement`, post the one balanced journal, resolve
installments, consume credit, and close the contract SETTLEMENT. Over HTTP, idempotent.

**Business rules (ADR-018; Addendum §13; ADR-017).**

- `POST /api/v1/settlements` with an `Idempotency-Key` and the quote id. **Re-price at execution**
  (accrue-before-resolve again) and **revalidate** against the snapshot (D9):
  - `409 STALE_SETTLEMENT_QUOTE` if `contract.version` or any recomputed component differs;
  - `409 SETTLEMENT_QUOTE_EXPIRED` if `clock.now() > valid_until`;
  - `409 SETTLEMENT_QUOTE_ALREADY_EXECUTED` if the quote is already EXECUTED (`uk_settlement_quote_id` is
    the DB backstop — one settlement per quote).
- Idempotency (ADR-017 single-use-forever): identical key replays the stored response; expired key →
  `409 IDEMPOTENCY_KEY_EXPIRED`; `uq_settlement_idempotency` is the permanent backstop (already fully
  unique in V1, consistent with option A).
- Credit policy (D10, §13): consume **all** AVAILABLE credit; if `available_credit > gross_amount` reject
  `409 CREDIT_EXCEEDS_SETTLEMENT` (refund out of MVP). Each consumed source → `settlement_credit_application`.
- Post **one** balanced `SETTLEMENT` journal entry (D1/D4): Dr `KAS` (cash_received) + `TITIPAN_NASABAH`
  (credit_used); Cr `PIUTANG_POKOK`/`PIUTANG_BUNGA`/`PIUTANG_DENDA` (receivable cleared) + `PENDAPATAN_BUNGA`
  (accrued + future interest charged, D2) + `PENDAPATAN_ADMIN` (admin fee). No `DISKON_PELUNASAN` line in
  the normal path (D3). Zero components contribute no line.
- `settlement_allocation` records only receivable resolution per installment (PENALTY/INTEREST/PRINCIPAL,
  oldest first); income/fee/credit are journal-only (D5).
- Close the contract SETTLEMENT and move open installments to SETTLED (`settled_amount`/`settled_at`)
  through the `contract` module's own path, never the payment-resolution MATURITY path (D10).
- RBAC: `POST /settlements` ADMIN_OPERASIONAL only. Matrix + `EndpointRoleMatrixIT`.

**Migration V16.** Extend `uq_journal_entry_event` (V12) to include `'SETTLEMENT'` (drop + recreate the
partial unique index with `SETTLEMENT` added to the `ref_type IN (…)` list) so the one-entry-per-settlement
rule has a DB backstop (ADR-008 T25 note; ADR-018 D1). Add the `contract` SETTLED-close support if the V4
coherence checks need it (verify `settled_amount`/`settled_at` writers against V4). (No `CREATE TABLE`.)

**Acceptance criteria.**

- Executing the Demo-B month-7 quote posts a single balanced `SETTLEMENT` entry with exactly the D4 lines,
  closes the contract SETTLEMENT, moves installments to SETTLED, and returns the settlement record.
- A stale (version/component-changed) or expired quote is rejected with the right 409 and writes nothing.
- Re-executing an EXECUTED quote (different key) is rejected; identical-key replay returns the stored
  settlement; expired key → `IDEMPOTENCY_KEY_EXPIRED`.
- Credit greater than gross is rejected; credit ≤ gross is fully consumed and recorded.
- A second non-reversal `SETTLEMENT` entry for the same settlement is rejected by the extended index (raw
  SQL).
- Role matrix enforced.

**Tests.** IT `SettlementExecutionIT` (Testcontainers, real commits so deferred triggers fire): happy path
balances and closes; stale quote; expired quote; already-executed; idempotent replay; expired key; credit
fully consumed; credit-exceeds-gross rejected; duplicate-entry rejected by the extended index; role matrix.
`AccountingInvariantsIT` extended for the `SETTLEMENT`-single-entry backstop. `FixedClock`, `TestJwts`,
pinned Demo-B golden numbers shared with `SettlementQuoteEngineTest`.

## Sprint 6 — Void, Consistency & Write-off (Epic E4/F1/F4/F5): PLANNED

Planned 2026-10-11 via `/grill-with-docs`. Void semantics are recorded in **ADR-020**; the ADR-017 T18
permanent-key probe question is resolved (probe declined) in an ADR-017 addendum. Implementation order is
**T16 → T17 → T19 → T18 → T27a**: void is the heaviest and the one with real design surface, so it goes
first; the two jobs (T17, T18) reuse the T3/T26 daily-job infrastructure; write-off (T19) is independent;
T27a is a one-line CI change that can land any time.

**Scope and budget (confirmed).** Sprint 6 = T16 (5) + T17 (2) + T18 (2) + T19 (3) + T27a (1) = **13 pts**
(at the ceiling). **T16 is DONE** (merged via PR #11) and **T17 is DONE** (branch
`t17-consistency-check-job`); T19, T18, T27a remain. **T20**
(observability) and the heavier **T27** (dependency/secret scans +
Compose/OpenAPI smoke) move to **Sprint 6b** alongside **T21** (auth). The minimal `./gradlew check` CI
gate is **not** deferred — it is carved out as **T27a** and treated as a Sprint 6 prerequisite, because
today's `ci.yml` runs only `./gradlew test` (verified 2026-10-11). **If T16 re-estimates above 5 pts or the
finalized scope exceeds 13 pts, defer T18 first** (its only open question is already resolved, and the
cleanup job is pure hygiene), leaving T16 + T17 + T19 + T27a ≈ 11 pts. No new module: void lives in
`payment`, consistency and cleanup jobs in `penalty`'s daily-job host (or `shared/job`), write-off in
`contract`.

### T16 — E4 Void payment + reversal, installment recalc & conditional credit reversal (5 pts) — DONE

**Status: DONE** (branch `t16-payment-void`). Decisions in **ADR-020** (D1–D6). First task of Sprint 6.

**Implementation note (2026-10-13, branch `t16-payment-void`).** `POST /api/v1/payments/{id}/void`
(ADMIN_OPERASIONAL, `Idempotency-Key` header, optional `reason` body) reverses a POSTED payment. The write
path (`payment.application.PaymentVoidApplicationService`, wrapped by `PaymentVoidRetryingService` — 3
attempts, 50/150 ms, outside the transaction, mirroring `PaymentRetryingService`) runs one
`@Transactional` use case: it flips the payment `POSTED → VOIDED` (`Payment.markVoided`, a reversal — the
`amount` and all `payment_allocation` rows are kept as history, D1), recomputes each affected installment
from the **remaining POSTED payments** (`PaymentAllocationRepository.sumResolvedByInstallmentIds` after the
VOIDED flip is flushed) through the new `contract.application.PaymentVoidPort.applyVoidRecomputation`
(absolute paid totals via the new `Installment.resetPaidAmountForVoid`, demoting `PAID → PARTIALLY_PAID →
PENDING`, and `Contract.reopenFromMaturity` undoing a MATURITY auto-close, D1.3), reverses the payment's
EXCESS credit when it is still entirely AVAILABLE (`ContractCreditPort.reverseAvailableCreditFromAllocations`,
a `SELECT … FOR UPDATE` lock then delete of the sub-ledger row, D4), and posts **one** mirrored reversal
journal (`LedgerPostingService.postReversal` — original PAYMENT lines with Dr/Cr swapped, `reversal_of_id`
set, same `(ref_type, ref_id)`, so V12 `uq_journal_entry_event` still permits it; no new `LedgerRefType`,
D1.2). The eligibility guard (D5) is evaluated over **every** installment the allocations reference:
SETTLED/WRITTEN_OFF installment or a non-ACTIVE contract → 409 `PAYMENT_NOT_VOIDABLE`; a
partially/fully-consumed credit → 409 `PAYMENT_CREDIT_CONSUMED`; both write nothing. Idempotent via
`IdempotencyService` exactly as `POST /payments` (identical key replays; expired key →
`IDEMPOTENCY_KEY_EXPIRED`); an already-VOIDED payment is a no-op that returns the current representation.
Two new `ErrorCode`s + `SerfiraException` subclasses (both 409). Migration **V18** drops and recreates
`uq_payment_idempotency` **without** the `WHERE status = 'POSTED'` predicate (all statuses now), with a
fail-safe preflight that raises rather than deleting any payment row if a duplicate exists (ADR-020 D3,
ADR-017), so a voided payment's key stays spent after T18 cleanup. RBAC row added to
`ResourceServerSecurityConfiguration` + `EndpointRoleMatrixIT`. No new module. TS §1 gains the two
`payment → contract` void edges and the `payment → ledger` reversal use.

**Review-fix addendum (2026-10-13, PR #11 — CodeRabbit + Qodo).** Four confirmed defects fixed on the
branch; a fifth deferred:

1. **MATURITY void was always rejected (both tools; blocking).** `assertVoidable` rejected every non-ACTIVE
   contract, so the D1.3 reopen path (and the `PaymentVoidIT` maturity scenario) was dead. `VoidEligibility`
   now carries `closedReason`; the guard allows ACTIVE **or** CLOSED/MATURITY and still rejects
   SETTLEMENT/write-off. **ADR-020 D5 corrected** (it had contradicted D1.3).
2. **Bare void → 500 (Qodo High; blocking).** `Payment.markVoided` stored `null` for a blank/missing reason,
   violating V4 `ck_payment_voided_coherence` (VOIDED ⇒ `void_reason NOT NULL`). It now stores a
   `"(no reason given)"` default, so a `{}`-body void (used across `PaymentVoidIT`) succeeds.
3. **Void dropped credit-funded resolution (Qodo High).** The recompute replaced `paid_amount` with only
   remaining POSTED **payment** allocations, ignoring surviving `contract_credit_application` amounts.
   `PaymentVoidService` now adds surviving applied credit per installment (new
   `ContractCreditApplicationRepository.sumAppliedByInstallmentIds`).
4. **Stale `paid_at` (Qodo Medium).** Voiding the earliest payment left the voided payment's instant.
   `Installment.resetPaidAmountForVoid` now takes the earliest **surviving** resolving instant (POSTED
   payment or applied-credit instant), advancing or clearing `paid_at`.
5. **Deferred — reusable void key (Qodo Medium).** A _void_ idempotency key is not single-use-forever (V18
   covers creation keys only); the hole is latent until the T18 cleanup job ships, and closing it needs a
   new migration. Tracked as **T31** below and in ADR-020 Consequences.

**Verification — 2026-10-13, Docker NOT available in this environment** (tech-stack steering fallback):
`.\gradlew compileJava compileTestJava` passes (whole tree compiles with the void path, the two new
`contract` seams, V18, the two new error codes, and the review-fix changes). `.\gradlew test --tests "*Test"`
(all pure unit suites, no Docker) = **BUILD SUCCESSFUL, 57 suites / 429 tests / 0 failures / 0 errors**
(up from 427; +2 from the new `PaymentVoidTest` null-reason case and the `InstallmentVoidRecomputeTest`
paid_at-advance case), including `InstallmentVoidRecomputeTest` (7), `PaymentVoidTest` (5),
`ContractReopenFromMaturityTest` (3) and `PaymentVoidRetryingServiceTest` (4). **Not run:** the Testcontainers
`*IT` suites — specifically the new `PaymentVoidIT` (now including the credit-funded-resolution-survives-void
scenario) and the extended `EndpointRoleMatrixIT` — confirmed to fail only at `DockerClientProviderStrategy`
(no Docker daemon in this environment), not on code. They **compile** and CI must run them under Docker before
the PR merges.

**Dependencies:** C3 (`POST /payments` + allocations), ADR-002/ADR-008 (reversal posting), T14
(`contract_credit` + `ContractCreditPort`), ADR-013 §3 (A-3). None open. **T18 does not block T16**: T16's
own migration (D3) widens `uq_payment_idempotency` to all statuses so a voided key stays spent even before
T18 exists.

**Scope.** Add `POST /api/v1/payments/{id}/void` (ADMIN_OPERASIONAL, `Idempotency-Key` header): reverse a
POSTED payment by posting a reversal journal, flipping the payment to VOIDED, recomputing affected
installments from the remaining POSTED payments, and reversing the payment's own EXCESS credit when it is
still entirely AVAILABLE — all atomically. Owner module: `payment`.

**Business rules (ADR-020 D1–D6; DM §1.7, invariant 16; ADR-002/ADR-008; ADR-013 §3 / A-3; ADR-017 D-widen).**

- **Reversal, never deletion (D1).** The original `payment` row flips POSTED → VOIDED; `amount` and all
  `payment_allocation` rows are left intact (V1 immutability + the V3 `trg_payment_allocation_total_deferred`
  check, which keys on `payment_id` with no status filter, both stay satisfied). One reversal
  `journal_entry` is posted with `reversal_of_id` = the original PAYMENT entry and lines mirroring it with
  Dr/Cr swapped (no new `LedgerRefType`; it reuses the original `(ref_type=PAYMENT, ref_id)`, so the V12
  `uq_journal_entry_event` non-reversal-only index still permits it).
- **Installment recalculation (D1.3).** Each installment referenced by the voided payment's allocations
  recomputes `paid_amount` and status **from the remaining POSTED payments only** (excluding the voided
  payment's allocations) through `InstallmentReceivablePort.applyPaymentResolution` (the same mutator a
  payment uses). PAID→PARTIALLY_PAID/PENDING demotions and an undone MATURITY auto-close follow from the
  recomputation, not from a special path.
- **No catch-up accrual; A-3 preserved (D2).** The void does **not** accrue penalty. A void raises the
  penalty base retroactively, which is the mirror of accrue-before-resolve (that invariant guards flows
  that _lower_ the base). Existing `penalty_accrual` rows are never re-priced (ADR-013 §3); the next daily
  job accrues only un-accrued dates at the then-current (higher) base. The resulting undercharge on
  already-accrued dates is an **explicitly accepted** trade-off, documented as an intentional exception to
  a superficial reading of accrue-before-resolve, not a violation of ADR-013.
- **Consumed-credit guard (D4).** If the payment's EXCESS booked a `contract_credit` row and any of it has
  been applied (T14) or consumed by a settlement (T13) — available balance < original amount — reject
  `409 PAYMENT_CREDIT_CONSUMED`, writing nothing. If the credit is still entirely AVAILABLE, reverse it in
  the same transaction. The check is **concurrency-safe**: take a row lock on the `contract_credit` row
  (`SELECT … FOR UPDATE`, via a `ContractCreditPort` method) before reading its available balance, so a
  concurrent credit-apply/settlement cannot consume it between the check and the reversal.
- **Eligibility guard over all affected installments (D5, corrected per PR #11).** Evaluated over **every**
  installment the payment's allocations reference, not one assumed target: if any is SETTLED or WRITTEN_OFF,
  reject `409 PAYMENT_NOT_VOIDABLE`, writing nothing. The contract must be ACTIVE **or** CLOSED/MATURITY
  (the latter so voiding the final payment can reopen it, D1.3); a SETTLEMENT close or a write-off/termination
  is rejected. T16 never reverses a settlement or write-off.
- **Atomicity + concurrency (D6).** The status flip, reversal journal, installment recalculation and credit
  reversal succeed or roll back together in one `@Transactional` application service (controllers hold no
  transaction; ports post with `Propagation.MANDATORY`). Guards (D4/D5) run inside that transaction under
  their row locks. Optimistic-lock conflicts (installment/contract/credit `@Version`) are retried **outside**
  the transaction by a `PaymentVoidRetryingService` (3 attempts, 50/150 ms, mirroring `PaymentRetryingService`),
  surfacing `409 CONCURRENT_MODIFICATION` if they persist.
- **Idempotency (ADR-017).** Idempotent via `IdempotencyService` exactly as `POST /payments`: identical key
  replays the stored response; expired key → `409 IDEMPOTENCY_KEY_EXPIRED`.
- **RBAC (Addendum §3.4).** `POST /payments/{id}/void` ADMIN_OPERASIONAL only. Add the matcher row to
  `ResourceServerSecurityConfiguration` and a cell to `EndpointRoleMatrixIT`.

**Migration (next free number, no `CREATE TABLE`).** Drop and recreate `uq_payment_idempotency` **without**
the `WHERE status = 'POSTED'` partial predicate, so it is unique across all payment statuses and a voided
payment's key stays spent after T18 cleanup (ADR-020 D3, ADR-017). The migration is **data-preserving and
fail-safe**: a preflight detects any rows that would violate the all-status index and the migration **fails**
rather than deleting/rewriting payment history (an empty preflight is expected — today's partial index
already blocks two POSTED payments per key, and void does not exist yet). Confirm no setter exposes
`payment.amount` (it is already effectively immutable per V1 + the V12 note).

**Acceptance criteria.**

- Voiding a POSTED payment flips it to VOIDED, posts exactly one balanced reversal entry
  (`reversal_of_id` set, Dr/Cr swapped), leaves its allocations intact, and the V3/V12 allocation-total
  invariants still hold.
- Affected installments demote correctly (PAID→PARTIALLY_PAID/PENDING; an undone MATURITY close reopens the
  contract to ACTIVE) computed from the remaining POSTED payments; a second void of the same payment is a
  no-op replay (idempotent) or rejected if already VOIDED.
- A void whose payment credit was partially/fully consumed is rejected `PAYMENT_CREDIT_CONSUMED` and writes
  nothing; a void with fully-AVAILABLE credit reverses the credit atomically with the rest.
- A void where any affected installment is SETTLED/WRITTEN_OFF or the contract is non-ACTIVE is rejected
  `PAYMENT_NOT_VOIDABLE` and writes nothing.
- No `penalty_accrual` row is re-priced; after the void the next accrual run charges un-accrued dates at the
  higher base (asserted at the service/engine level, not necessarily end-to-end).
- Role matrix enforced; idempotent replay returns the stored response; expired key → `IDEMPOTENCY_KEY_EXPIRED`.
- A concurrent credit-apply racing a void never double-spends the credit (one side wins deterministically).

**Tests.** Unit: installment recomputation-from-remaining-POSTED (pure, mirrors allocation ordering);
reversal-line mirroring (Dr/Cr swap). IT `PaymentVoidIT` (Testcontainers, real commits so deferred triggers
fire): happy-path reversal + demotion + balanced reversal entry; MATURITY-close undo; `PAYMENT_CREDIT_CONSUMED`
(applied credit) and clean credit reversal (AVAILABLE); `PAYMENT_NOT_VOIDABLE` for SETTLED installment,
WRITTEN_OFF installment, and non-ACTIVE contract (one case each); idempotent replay; expired key; a reused
spent key after a (simulated) claim-row delete still cannot re-void or create a second payment (ties to
T18); the all-status `uq_payment_idempotency` rejects a duplicate key via raw SQL; role matrix. Concurrency
IT: a credit-apply committing between a void's check and reversal is serialized by the row lock. Reuse
`TestJwts`, `FixedClock`.

### T17 — F1 Consistency check job (2 pts) — DONE

**Status: DONE** (branch `t17-consistency-check-job`). **Dependencies:** T3/T26 daily-job infrastructure
(ShedLock, `job_run`, keyset batching, backoff), T25 (the invariants it re-asserts in aggregate) — none open.

**Implementation note (2026-10-15, branch `t17-consistency-check-job`).** A fourth step
(`consistency-check`) was added to `DailyServicingOrchestrator`, after aging, reusing the T3/T26 ShedLock,
`job_run` lifecycle, keyset paging and retry/backoff — **no new scheduler, lock, or batching**. The step is
**read-only reconciliation**: it records drift as `reconciliation_exception` rows and never corrects money
(Addendum §7.3). The three independent checks (Addendum §7.1) live in the pure
`penalty.domain.consistency.ConsistencyCheckEngine` (golden-tested): **A `PAYMENT_CASH`** (`Σ` POSTED
`Payment.amount` = net `KAS` from `PAYMENT` entries), **B `ALLOCATION_PAYMENT`** (`Σ` POSTED
`PaymentAllocation.amount` = `Σ` `Payment.amount`, invariant 6), **C `RECEIVABLE_INSTALLMENT`** (`Σ`
installment receivable residual = net `PIUTANG_POKOK + PIUTANG_BUNGA + PIUTANG_DENDA`). The `check_type`
values are exactly the three already in the V1 `ck_recon_exception_type` CHECK, so **no migration** was
needed. `ConsistencyCheckService` (one transaction per contract, isolation `REPEATABLE_READ` so all five
reconciliation reads use one consistent snapshot — otherwise a `POST /payments` committing mid-check could
make the ledger reads disagree with the payment/allocation reads and record false drift) gathers each
check's two sides only through the owning modules' read ports (`02_TECH_SPEC.md` §1): the new
`payment.application.PaymentReconciliationPort` (POSTED payment/allocation totals), the new
`ledger.application.ContractLedgerBalancePort` (net `KAS`-from-PAYMENT and `PIUTANG_*` balances), and the
new `contract.application.ReceivableReconciliationPort` (receivable residual, netting active adjustments
through `EffectivePenaltyPort` — the same single "outstanding" formula as T15/T30, no second formula). The
`ReconciliationException` writer entity + repository are owned by `penalty` (the sole writer). Recording is
**idempotent** per `(contract_id, check_date, check_type)`: a same-date re-run of a still-drifting contract
does not add a second OPEN row — the service skips the insert when an OPEN row for the key already exists,
backed by the partial unique index `uq_recon_exception_open` (migration **V19**, partial on
`status = 'OPEN'` so RESOLVED/INVESTIGATING history stays unconstrained). A-9 counting for this step:
`records_processed` = contracts checked, `records_failed` = contracts that **drifted** (or threw a read
error), so the `job_run` row is itself a portfolio drift alarm while a drift still commits its exception
rows (not a transaction failure). Written as `SYSTEM`. No HTTP endpoint (the Finance read endpoint
`GET /reports/reconciliation-exceptions` stays deferred to the `reporting` module), hence **no RBAC row**.
The existing daily-job ITs were updated to expect four `job_run` rows per run. TS §1 gains the three
`penalty →` consistency-read edges (one new consumer on the existing `penalty → payment` edge, plus the new
`penalty → ledger` read seam and the new `penalty → contract` `ReceivableReconciliationPort`).

**PR #12 review fixes (2026-10-15, CodeRabbit).** (1) `check` isolation raised to `REPEATABLE_READ` for a
consistent snapshot across the five reads, removing false drift when a payment commits mid-check; (2)
exception recording made idempotent per `(contract_id, check_date, check_type)` via an OPEN-row existence
check plus the V19 partial unique index; (3) corrupted Markdown in this entry and `docs/PROGRESS.md`
restored. The review's suggestion to record an A-n ambiguity between "specified" and "implemented" checks
was **not** actioned: the three implemented checks (A `PAYMENT_CASH` / B `ALLOCATION_PAYMENT` /
C `RECEIVABLE_INSTALLMENT`) match Addendum §7.1 exactly, so there is no discrepancy to flag.

**Verification — 2026-10-15, Docker NOT available in this environment** (tech-stack steering fallback):
`.\gradlew compileJava compileTestJava` passes (whole tree compiles with the fourth step, the three new read
ports + implementations, the engine, the `ReconciliationException` writer, and the updated ITs).
`.\gradlew test --tests "*Test"` (all pure unit suites, no Docker) = **BUILD SUCCESSFUL, 58 suites / 443
tests / 0 failures / 0 errors** (up from 429), including the new `ConsistencyCheckEngineTest` (8) and the
five new consistency cases in `DailyServicingOrchestratorTest` (now 24). **Not run:** the Testcontainers
`*IT` suites — the new `ConsistencyCheckIT` (including the re-run-without-duplicate-OPEN case) and the
updated `DailyServicingJobIT`/`DailyJobRobustnessIT`/`DailyServicingLockIT` — which compile but fail only at
`DockerClientProviderStrategy` (no Docker daemon); CI must run them under Docker before the PR merges.

**Original task spec (for reference).**

**Scope.** Add a fourth step to the daily servicing job (after aging, per Addendum §7.3 billing → penalty →
aging → consistency check): a read-only reconciliation that re-asserts the money invariants in aggregate and
records a `job_run` row, flagging (not fixing) any drift. Owner: the `penalty` daily-job host (or
`shared/job`), reusing the existing orchestration — **no new scheduler, lock, or batching mechanism**.

**Business rules (Addendum §7.3, §10; ADR-013 A-9; DM §3 invariants).** The three reconciliation checks
(the F1 set): (1) ledger balance — every `journal_entry` has `Σ debit = Σ credit` (already a DB trigger, but
re-asserted in aggregate as a drift alarm); (2) receivable vs. ledger — each contract's installment
outstanding reconciles with the `PIUTANG_*` ledger balances; (3) credit vs. ledger — `Σ AVAILABLE
contract_credit` reconciles with the `TITIPAN_NASABAH` balance. The step is **read-only** (no money moves,
no corrections — a correction would be an operator-initiated reversal, out of scope). It writes one
`job_run` row (`job_name = "consistency-check"`, `records_processed` = contracts checked, `records_failed` =
contracts failing a check, status `COMPLETED`/`FAILED` derived from `records_failed`, exactly as ADR-013
A-9). Discrepancies are logged (safe metadata only, no PII) at WARN/ERROR; the exact alerting channel is out
of MVP scope (observability is T20). Runs in its own transaction (read-only), isolated from the money steps
(a failed check never rolls back recognized money, mirroring the T6 aging-step isolation).

**Acceptance criteria.** A clean portfolio produces a `consistency-check` `job_run` row with
`records_failed = 0`, status COMPLETED. A deliberately corrupted fixture (e.g. a raw-SQL imbalance that
bypasses the triggers in a test) is detected and flagged with the right contract id, `records_failed > 0`,
status FAILED, and **no** mutation. The step reuses the existing ShedLock/`job_run`/keyset/backoff; a crash
leaves a `RUNNING` row that the next run marks `ABANDONED` (T26 behavior, unchanged).

**Tests.** Unit: each reconciliation predicate (pure, over snapshot inputs). IT `ConsistencyCheckIT`
(Testcontainers): clean portfolio → COMPLETED; injected drift → FAILED + flagged, no mutation; `job_run`
granularity matches A-9; isolation from the money steps. `FixedClock`.

### T19 — F5 Write-off (3 pts)

**Status: TODO.** **Dependencies:** ADR-013 §1 (accrue-before-resolve), V4 (record-state coherence),
ADR-008 (posting). Independent of T16/T17/T18.

**Scope.** Add `POST /api/v1/contracts/{id}/write-off` (ADMIN_OPERASIONAL, `Idempotency-Key`): record an
external decision to stop collecting, closing the contract `ACTIVE → TERMINATED`, capping the receivable,
moving open installments to WRITTEN_OFF, and posting the write-off journal. Owner module: `contract`.

**Business rules (DM §1.3/§1.12, invariant; Addendum §13; ADR-013 §1; GLOSSARY Write-off).**

- **Accrue-before-resolve first (ADR-013 §1).** In the same transaction, bill and accrue through the
  write-off business date before capping, so the written-off receivable reflects penalty actually in force
  (the write-off analogue of T4/T12/T13).
- Close the contract `ACTIVE → TERMINATED` with the write-off reason; move open installments to
  WRITTEN_OFF (`written_off_amount`/`written_off_at`), capping the receivable (`InstallmentBalance`
  outstanding → 0 once written off). Require **`write_off_recorded_by`** (the JWT `sub`, never the body) at
  the **DB** level — V4 currently checks only the reason; T19's migration adds a NOT-NULL/CHECK so a
  TERMINATED contract cannot exist without the recording actor.
- Post one balanced write-off journal `Dr BIAYA_PENGHAPUSAN_PIUTANG / Cr PIUTANG_POKOK|PIUTANG_BUNGA|
PIUTANG_DENDA` for the capped recognized receivable (`ref_type = WRITE_OFF`, `ref_id = contract or a
write-off record id` — pin one entry per write-off). No future unrecognized interest is written off (it
  was never a receivable). Posted history is never hidden (append-only).
- Idempotent via `IdempotencyService`; one `@Transactional` application-service boundary (accrue → cap →
  close → post, all-or-nothing); optimistic-lock retry outside the transaction.
- **RBAC:** `POST /contracts/{id}/write-off` ADMIN_OPERASIONAL only. Matrix + `EndpointRoleMatrixIT`.

**Migration (next free number, no `CREATE TABLE`).** Add the DB guard requiring `write_off_recorded_by`
(and `write_off_at`) when `status = TERMINATED`/reason is a write-off (strengthening V4). Optionally extend
`uq_journal_entry_event` to `WRITE_OFF` (decide during implementation; default is the service guard, like
PAYMENT/CREDIT_APPLICATION per ADR-008 d5).

**Acceptance criteria.** A write-off on an ACTIVE contract accrues through the write-off date, caps the
receivable, closes TERMINATED, moves open installments to WRITTEN_OFF, and posts one balanced `WRITE_OFF`
entry; outstanding goes to 0 and no penalty accrues afterward (contract is non-ACTIVE). A TERMINATED row
without `write_off_recorded_by` is rejected at the DB (raw SQL). Write-off of a non-ACTIVE contract is
rejected. Idempotent replay returns the stored response. Role matrix enforced.

**Tests.** Unit: write-off cap/close transition (pure where possible). IT `WriteOffIT` (Testcontainers):
accrue-before-resolve bills/accrues into the write-off; cap + close + WRITTEN_OFF; balanced `WRITE_OFF`
entry; DB rejects TERMINATED without `write_off_recorded_by` (raw SQL); non-ACTIVE rejected; idempotent
replay; role matrix. `FixedClock`, `TestJwts`.

### T18 — F4 Idempotency key cleanup job (2 pts)

**Status: TODO.** **Dependencies:** T3/T26 daily-job infrastructure; ADR-017 (lifecycle, including the
2026-10-11 resolution of the probe item); ADR-020 D3 (the all-status `uq_payment_idempotency` that keeps a
voided key spent after cleanup). First candidate to defer if Sprint 6 exceeds 13 pts.

**Scope.** Add a job that deletes `idempotency_keys` rows past their retention window (`expires_at` passed),
reusing the T3/T26 job infrastructure (ShedLock, `job_run`, keyset batching, backoff). Owner: the daily-job
host (or `shared/job`). Purely hygiene — it bounds table growth; the permanent DB backstops, not the claim
rows, are the correctness authority once a row is gone.

**Business rules (ADR-017 D1–D3 + the 2026-10-11 resolution; TS §2.5).**

- Delete only rows whose `expires_at` has passed. The delete must **not** re-open a key: a later reuse of a
  deleted-and-expired key is still rejected, because the permanent business-row backstops
  (`uq_contract_idempotency`, and the ADR-020-D3-widened all-status `uq_payment_idempotency`) hold
  independently of the claim row.
- **No pre-business-logic permanent-key probe (resolved 2026-10-11, ADR-017 addendum).** The 409 code a
  reused spent key returns after cleanup is whichever truthful guard the re-run reaches first
  (`DUPLICATE_CONTRACT` for a same-request contract retry, `CONTRACT_STATE_INVALID` for a payment against a
  now-inactive contract, `IDEMPOTENCY_KEY_EXPIRED` from a backstop translator when reached). These varied
  codes are accepted; the probe is explicitly **declined** (it would tax every hot write path with an extra
  read for a case that cannot occur until after both retention and cleanup, to normalize a non-workflow
  reuse). The invariant that matters — a cleaned-up claim can **never** permit a duplicate business row —
  is held by the backstops and is what T18 tests assert.
- Writes one `job_run` row (`job_name = "idempotency-cleanup"`, `records_processed` = rows deleted, A-9
  granularity and status rules). Keyset batching + backoff from T26.

**Acceptance criteria.** The job deletes only expired rows and leaves live ones; it records a correct
`job_run` row. After the job deletes an expired claim, re-issuing that key **cannot** create a second
contract or payment — it is rejected with one of the truthful 409s (asserted), never silently accepted. A
voided payment's key stays spent after cleanup (ties to ADR-020 D3). Crash leaves a `RUNNING` row the next
run marks `ABANDONED` (T26, unchanged).

**Tests.** IT `IdempotencyCleanupIT` (Testcontainers): expired rows deleted, live rows kept; `job_run`
granularity; **after cleanup, a reused spent key cannot mint a second contract/payment** (the key
assertion); a voided payment's key still spent (ADR-020 D3). `FixedClock`.

### T27a — Minimal CI gate: `./gradlew check` (1 pt)

**Status: TODO.** **Dependencies:** none. Sprint 6 prerequisite (not deferred), because `ci.yml` today runs
only `./gradlew test` (verified 2026-10-11).

**Scope.** Change the CI `test` job in `.github/workflows/ci.yml` to run `./gradlew check` instead of
`./gradlew test`, so the quality gate (tests plus whatever `check` aggregates — compilation of all source
sets and any configured verification tasks) runs on every PR and push to `main`. **Not** in scope here (→
T27, Sprint 6b): adding a dependency-vulnerability scan, a secret scan, and the `docker compose up` →
`/actuator/health` → `/v3/api-docs` smoke → `down` runtime gate. Each of those tools must be justified per
`35-coding-standards` when T27 is planned.

**Acceptance criteria.** CI runs `./gradlew check` and the build is green on the current `main` (verify the
`check` task does not pull in an unconfigured plugin that fails the build; if it does, either configure it
minimally or scope that out explicitly to T27). No change to application code.

**Tests.** CI config change; verified by a green CI run on the PR. (If `check` currently equals `test` plus
nothing, note that and keep the change for forward-safety as new verification tasks are added.)

### T31 — Durable single-use-forever void idempotency key (2 pts)

**Status: TODO.** **Dependencies:** T16 (DONE, void path + V18), ADR-017 (single-use-forever key lifecycle),
ADR-020 D3 (all-status `uq_payment_idempotency`), T18 (the cleanup job that makes the hole reachable). Raised
by the PR #11 review (Qodo finding #5).

**Problem.** A payment-_creation_ idempotency key is kept spent forever by V18's all-status
`uq_payment_idempotency`. A _void_ key has no such durable backstop: it lives only in the
`IdempotencyService` claim row. Once T18's cleanup deletes an expired claim, the same key could be reused to
void a **different** payment. No ledger invariant is breached — each such void still passes every eligibility
guard and posts its own legitimate reversal — but it weakens the ADR-017 single-use-forever contract for
void keys.

**Scope.** Persist a durable, uniquely-constrained, endpoint-scoped void-key association with the voided
payment (new Flyway migration: a column or side table + unique index keyed on the void endpoint scope + key),
written inside the void transaction. Reject reuse of a spent void key for a different payment as
`IDEMPOTENCY_KEY_EXPIRED` / `CONFLICT` even after the claim row is gone. Owner module: `payment`.

**Acceptance criteria.** After a void and a simulated claim-row deletion (as T18 would do), reusing that key
for a different payment is rejected and writes nothing; an identical retry of the original void still replays.

**Tests.** IT (Testcontainers): void a payment, delete the claim row directly, then a reused-key void of a
different payment is rejected; identical-key replay of the original void still returns the stored response.

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

> **Sprint 5 (T12–T15) is complete** and **Sprint 6 (T16–T19 + T27a) is now fully planned** in the
> "Sprint 6 — Void, Consistency & Write-off" section above (ADR-020 for void; the ADR-017 T18 probe item
> is resolved there). The rows below are the still-stubbed later tasks (Sprint 6b onward).

| ID  | Story                                                              | Sprint | Status | Carry-forward requirements from this plan                                                                                                                                                                                                                                                                                                                                                 |
| --- | ------------------------------------------------------------------ | ------ | ------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T20 | Observability baseline (Addendum §10)                              | 6b     | TODO   | `X-Request-Id` filter + MDC, JSON logs (`timestamp`, `level`, `request_id`, `contract_id`, `module`), write `idempotency_keys.request_id`. Not owned by any story in `05_SPRINT_PLAN.md` (X-3). Moved out of Sprint 6 at planning (2026-10-11) to hold the 13-pt ceiling; the void/consistency/write-off theme comes first.                                                               |
| T21 | F2 Auth: login/refresh/logout, Argon2id, lockout, refresh rotation | 6b     | TODO   | Issue `roles` as a string array per ADR-015 (T7). Validate `iss`/`aud` once a real issuer exists and consider RS256 (ADR-005, review CR-10). Revisit T7 D2 (active `app_user` check at issue or per request). Add a production guard against the dev-key fallbacks in `docker-compose.yml` (CR-16).                                                                                       |
| T27 | CI & runtime gates — security scans + Compose smoke (review CR-15) | 6b     | TODO   | The minimal `./gradlew check` CI gate is split out to **T27a** and lands in Sprint 6 (prerequisite, not deferred). T27 keeps the heavier parts: a dependency-vulnerability and a secret scan (justify each tool per `35-coding-standards`), and `docker compose up` → wait for `/actuator/health` → `/v3/api-docs` smoke → `down`. T23's `OpenApiSmokeIT` already covers the in-JVM part. |
| T22 | G1–G6 Reporting, frontend, demo, outbox                            | 7–8    | TODO   | Unchanged from `05_SPRINT_PLAN.md`. NPL/AR depend on the DPD definition from T1.b.                                                                                                                                                                                                                                                                                                        |
| T28 | PII / HMAC key rotation (review CR-11)                             | 7      | TODO   | Key id in the PII envelope (`v2:<kid>:…`), a key ring for decrypt and for HMAC lookups, and a re-keying procedure for `nik_hash`/`phone_lookup` and `idempotency_keys.request_hash` (ADR-004, ADR-007 consequences). New ADR.                                                                                                                                                             |

### Deferred

| Item                                                                                        | Status   | Reason                                                                                                   |
| ------------------------------------------------------------------------------------------- | -------- | -------------------------------------------------------------------------------------------------------- |
| PRD C-5 update DRAFT contract                                                               | DEFERRED | Already deferred in `05_SPRINT_PLAN.md`.                                                                 |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring                     | DEFERRED | P2 / Fase 4 per PRD.                                                                                     |
| Require auth for OpenAPI/Swagger outside dev                                                | DEFERRED | ADR-005 made it public by design. Revisit with T21.                                                      |
| Rate limiting, security headers, CORS policy                                                | DEFERRED | No browser client or public deployment yet. Revisit with G2.                                             |
| Remove the unused Lombok dependency. Move the Sonar host out of `gradle-wrapper.properties` | DEFERRED | Hygiene only. The Sonar change is uncommitted local work owned by the developer.                         |
| Envelope-rendering `ErrorController` for container-level errors (firewall, direct `/error`) | DEFERRED | T7 note: Boot's default JSON is returned, leaking no internals. Low value until a browser client exists. |
| Fix TS §7 repo structure (X-9)                                                              | DEFERRED | Documentation only.                                                                                      |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role)             | DEFERRED | Deployment concern. No deployment exists yet.                                                            |

---

## Planning Notes

### Accrue-before-resolve invariant (accepted in ADR-013; implemented for payment by T4)

Every flow that lowers an installment's penalty base (payment, settlement, write-off) must first bill and
accrue through its business date in the same transaction. If that holds, the run-time base that
`PenaltyCalculator` uses equals the base that was actually in force on each missing date. With no
resolution in between, the base did not change. The historical-base concern therefore reduces to:

- **Void (E4):** the base goes _up_ retroactively (A-3).
- **A resolution path that still skips accrual:** payment no longer does so after T4/ADR-014. Settlement and
  write-off must implement the same invariant in T12, T13, and T19.

This is why T10 is conditional rather than planned.

### Review history (archived)

The 2026-09-29 code review and the 2026-09-30 external review (16 findings, CR-01…CR-16) are both resolved
or mapped to tasks. The full CR verdict table and the review corrections are in `tasks-archive.md`. The CR
findings that remain **open** map to still-TODO tasks: CR-10/CR-16 → T21, CR-11 → T28,
CR-15 → T27. **CR-14 is closed by T15** (one effective-penalty formula behind `EffectivePenaltyPort`).
All others are DONE (T7, T23, T24, T25, T26).

<!-- The CR-01…CR-16 verdict table and the 2026-09-29 correction notes moved to tasks-archive.md on 2026-10-05. -->

### Ambiguities

| ID   | Question                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | Documents in tension                                                                                                                          | Affects                          |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- |
| A-1  | Is date D charged at the base at the start of D (before resolutions on D)?                                                                                                                                                                                                                                                                                                                                                                                                            | TS §4.3 formula; ADR-012 d3 (unspecified)                                                                                                     | T1.a, T3, T4, T10, T12, T13, T16 |
| A-2  | Does penalty already paid reduce the pokok+bunga base?                                                                                                                                                                                                                                                                                                                                                                                                                                | TS §4.3 vs ADR-012 d2                                                                                                                         | T1.a, T10                        |
| A-3  | After a void, are dates already accrued at a reduced base re-priced, and how, given one row per `(installment, date)`?                                                                                                                                                                                                                                                                                                                                                                | TS §4.3 / Addendum §6 vs ADR-012 d7                                                                                                           | T1.a, T16 (possibly schema)      |
| A-4  | When does DPD start (due date or due date + grace), and what makes an installment OVERDUE (outstanding > 0? PAID with billed-but-unpaid interest)?                                                                                                                                                                                                                                                                                                                                    | DM §1.4, PRD glossary, Addendum §14 (all silent)                                                                                              | T1.b, T6, T8, T22                |
| A-5  | OVERDUE + partial payment → stay OVERDUE or become PARTIALLY_PAID?                                                                                                                                                                                                                                                                                                                                                                                                                    | DM §1.4 state machine vs `Installment.applyPayment`                                                                                           | T1.b, T6, T8                     |
| A-6  | Aging `as_of`: today only, or historical reconstruction?                                                                                                                                                                                                                                                                                                                                                                                                                              | FE §2.9 filter vs current-state data model                                                                                                    | T1.b, T8                         |
| A-7  | Bucket basis (installment outstanding per bucket vs contract max DPD), whether not-yet-due principal counts as Current, ACTIVE-only scope?                                                                                                                                                                                                                                                                                                                                            | PRD glossary, FE §2.9, Addendum §14                                                                                                           | T1.b, T8, T22                    |
| A-8  | Statement columns: what debit/credit mean for a balanced entry, which accounts appear, running balance?                                                                                                                                                                                                                                                                                                                                                                               | FE §2.7                                                                                                                                       | T1.c, T9, T22                    |
| A-9  | `job_run`: one row per step or per run? Units of `records_processed`/`records_failed`? Status after a partial failure?                                                                                                                                                                                                                                                                                                                                                                | Addendum §10, V1 comment, `PenaltyAccrualPort` Javadoc                                                                                        | T1.d, T3, T6, T17, T18           |
| A-10 | Confirm that no penalty accrues after maturity close (accrual requires ACTIVE)                                                                                                                                                                                                                                                                                                                                                                                                        | DM invariant 17 (silent on penalty)                                                                                                           | T1.a, T4                         |
| A-11 | Confirm the payment retry of Addendum §5 is still wanted (planned as written in T5)                                                                                                                                                                                                                                                                                                                                                                                                   | Resolved: implemented as written in T5 (`PaymentRetryingService`)                                                                             | DONE                             |
| A-13 | Resolved 2026-09-30 by the owner: **option A**. A key is single-use per endpoint forever, and retention only bounds replay. Reuse after expiry → 409 `IDEMPOTENCY_KEY_EXPIRED`, recorded in ADR-016 (written in T24). Rejected: B (reusable, relax the business-row backstops, weakens ADR-007 d8) and C (reusable, internal claim id on business rows, needs a migration). Question was: after retention, is an `Idempotency-Key` reusable (reclaim + re-run) or single-use forever? | `IdempotencyService`/`reclaimExpired` vs V7 `uq_contract_idempotency`, V1 `uq_payment_idempotency`/`uq_settlement_idempotency`; ADR-007 d8/d9 | T24, T13, T16, T18               |
| A-12 | Resolved: `roles` claim, JSON string array, implemented in T7 (ADR-015 D1). Previously: JWT roles claim name                                                                                                                                                                                                                                                                                                                                                                          | ADR-005, Addendum §3 (silent)                                                                                                                 | T7, T21                          |

### Assumptions preserved by this plan

- The business date is always `clock.today()` in Asia/Jakarta (TS §2.0). No backdated payments (Addendum
  §10A).
- Jobs run in-process as `SYSTEM` with no HTTP trigger (Addendum §3.3).
- Settlement, write-off and void are not built before Sprints 4b–4d complete. The penalty semantics decided
  in T1.a must be in place first, because those flows consume `penalty_outstanding` and re-run accrual.
- Review findings are placed per the owner's roadmap (2026-09-30): identity, RBAC and dependency
  compatibility before T7 closes (T23, T7); integrity, idempotency and job robustness before T11 (T24–T26);
  settlement/credit/adjustment findings in Sprint 5 (T13, T15); void findings in T16; CI, observability and
  production hardening in Sprint 6–7 (T20, T21, T27, T28).
- T7 (RBAC) moved from Sprint 6b to Sprint 4c. This follows the priority order (security before new
  functionality), and PRD §2 already requires a read-only role in Phase 1. It is not a change in scope.

### Dependency order (verified)

```text
T1 (decisions) ─┬─► T6 aging step ─► T8 aging report
                ├─► T9 statement
                ├─► T4 lazy accrual ─► T5 retry
                └─► T10 (conditional)
T2 (V9) ─► T3 job ─┬─► T5
                   └─► T6
T23 (DONE) ─► T7 RBAC + JWT identity (DONE) ─► T8, T9
A-13 (option A, decided) ─► T24 idempotency lifecycle
T25 DB backstops ─► T12–T15 (new posting types)
T26 job robustness ─► T17, T18
T14 credit + ADR-020 (void) ─► T16
ADR-020 D3 (all-status uq_payment_idempotency) ─► T18 (voided key stays spent after cleanup)
T3…T10, T23…T26 ─► T11 exit verification ─► Sprint 5 (T12–T15) ─► Sprint 6 (T16 → T17 → T19 → T18 → T27a)
    ─► Sprint 6b (T20, T27, T21) ─► T22, T28
```

### Sprint 6 planning note (2026-10-11)

The void semantics (A-3 confirmed unchanged, reversal + status flip, consumed-credit and settled-installment
guards, all-status idempotency widening, atomic transaction) are recorded in **ADR-020**. The ADR-017 open
T18 item (pre-business-logic permanent-key probe) is **resolved: declined** (ADR-017 addendum). Scope held at
the 13-pt ceiling by moving T20 and the heavy T27 work to Sprint 6b while keeping the minimal `./gradlew check`
CI gate in Sprint 6 as **T27a**. T18 is the designated cut if T16 grows past 5 pts.
