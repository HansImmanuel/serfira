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

**Snapshot:** `main` @ `0b282c0` plus the T7 implementation (ADR-015: RBAC matrix, roles-claim converter,
fail-closed JWT identity, `support/TestJwts`, retired audit probe), the T8 implementation (new read-only
`reporting` module with the aging report), the T9 implementation (contract statement read through a new
`ledger` statement port, V11 index), the T24 implementation (ADR-017: idempotency keys are single-use
forever, 409 `IDEMPOTENCY_KEY_EXPIRED`, the payment classifier no longer retries `uq_payment_idempotency`,
`BACKOFF_MILLIS` tidied), T25 (V12 accounting backstops), T26 (V13 `ABANDONED` + daily job robustness),
T29 (statement query `cast` fix), and the T11 implementation (Phase-1 test hardening: child-table
immutability tests, specific-exception assertions, `Phase1ExitScenariosIT`, `PiiLoggingIT`) — all on branch
`t11-phase1-exit-verification` @ `a8fa1a6`, pending merge.

**Latest verification — 2026-10-05 (T11), Docker available so the Testcontainers `*IT` suites ran:**

- `.\gradlew check`: pass.
- Forced full `.\gradlew test --rerun-tasks` (all tasks executed, not cached): **80 suites / 633 tests /
  0 failures / 0 errors / 0 skipped** (621 before T11 + 12 new tests: 4 child-table immutability, the 7
  `Phase1ExitScenariosIT` scenarios, and `PiiLoggingIT`).
- Diff is test-only: `AccountingInvariantsIT`, `Phase1ExitScenariosIT` (new), `PiiLoggingIT` (new),
  `ContractIdempotencyIT`, `PaymentIdempotencyIT`. No production code, migration, or unrelated file changed.

Older per-task verification snapshots (T8, T9, T24, T25, T29) live in `tasks-archive.md`.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `shared`. `settlement` does not exist;
  `frontend/` is empty.
- Migrations V1–V15. Baseline + accounting triggers (V3), state coherence (V4), settlement immutability (V5),
  contract-create safety (V6/V7), SYSTEM hardening (V8), ShedLock + penalty-accrual integrity (V9),
  `job_run.business_date` (V10), the `journal_line (contract_id, entry_date)` statement index (V11, T9), the
  parent-side accounting backstops + `uq_journal_entry_event` + `system_parameter` append-only (V12, T25),
  the `ck_job_run_status` widen for `ABANDONED` (V13, T26), the `contract_credit_application` cap +
  status-guard deferred triggers (V14, T14), and the `BEBAN_WAIVER_DENDA` COA seed + `penalty_adjustment`
  deferred cap trigger (V15, T15).
- Daily servicing (T3, hardened by T26): one renewable ShedLock for cron + backfill; every ACTIVE contract
  processed atomically billing → penalty → aging with five-attempt backoff retry, failure isolation, SYSTEM
  audit, three `job_run` rows; crashed `RUNNING` rows closed `ABANDONED`; keyset paging by
  `serfira.jobs.daily-servicing.batch-size` (default 500).
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
- Endpoints (11): `POST /api/v1/contracts`, `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, `GET /api/v1/reports/aging`,
  `POST /api/v1/contracts/{id}/credit/apply`, `GET /api/v1/contracts/{id}/credit`,
  `POST /api/v1/penalty-adjustments`.
- Security: JWT HS256 resource server, default-deny, Addendum §3.4 role matrix enforced on all eleven
  endpoints (T7, ADR-015; the two credit rows added by T14, the penalty-adjustment row by T15). `sub` must
  be a UUID with `exp`. No login/refresh/logout, no `iss`/`aud` validation yet (ADR-005; T21).

Full per-task implementation notes for all DONE work are archived in `tasks-archive.md`.

**Current sprint:** Sprint 4 ("Penalty, Aging & Phase-1 Close") is complete. C4/D1 and re-planned T1–T6 are
DONE (Sprint 4b), Sprint 4c (T23, T7, T8, T9) is complete, and **Sprint 4d is complete**: T24 (idempotency
key lifecycle), T25 (DB accounting backstops), T26 (daily job robustness), T29 (fix the pre-existing
statement query, X-13) and T11 (Phase-1 test hardening + exit verification) are all DONE. T10 stays
DEFERRED (T1.a kept ADR-012 decision 2; no code change required). **Sprint 5 (Settlement, Credit & Waiver,
T12–T15) is planned** (2026-10-05, ADR-018 + ADR-019; see the Sprint 5 section) and ready to implement in
order T14 → T15 → T12 → T13. **T14 (E3 credit) and T15 (E5 waive/reduce + `EffectivePenaltyPort`) are DONE**
(branches `t14-contract-credit`, `t15-penalty-waive-effective-port`); T12 → T13 remain. **T30 (route the
schedule response + aging report through `EffectivePenaltyPort`, closing the ADR-019 D4 / PR #7 F1
follow-up) is DONE** (branch `t30-effective-penalty-reads`). T16 (void) remains in Sprint 6.

**Blockers and critical gaps:** none open. The two that remained after T6 (the unenforced Addendum §3.4
matrix, and a non-UUID `sub` writing as `SYSTEM`) were closed by T7 (ADR-015).

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-3** (observability:
`X-Request-Id`, JSON logs, `idempotency_keys.request_id` — open, owned by T20) and **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). The resolved X-table with per-item detail is in `tasks-archive.md`.

---

## Completed Work

These items are preserved and must not be re-implemented. Detail lives in `05_SPRINT_PLAN.md §5` and the
ADRs listed.

| Story | Title                                                                                                                                                | Status | Reference                      |
| ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | ------ | ------------------------------ |
| A1    | Repo, CI, docker-compose, README, `docs/adr/`                                                                                                        | DONE   | Sprint 0                       |
| A2    | Injectable Clock (Asia/Jakarta), audit base, error envelope                                                                                          | DONE   | ADR-003                        |
| A3    | Flyway baseline, seeds, COA                                                                                                                          | DONE   | V1                             |
| A4    | Business document number generator                                                                                                                   | DONE   | `DocumentNumberGeneratorIT`    |
| —     | Pre-Sprint-2 hardening: PII at rest, resource server                                                                                                 | DONE   | ADR-004, ADR-005, V2–V5        |
| B1–B4 | Entities, FLAT/EFFECTIVE engine, due dates, golden tests                                                                                             | DONE   | Sprint 1                       |
| B5    | Contract create/activate/list/detail/schedule API                                                                                                    | DONE   | ADR-006, ADR-007, V6, V7       |
| C1    | Ledger posting + disbursement journal at activation                                                                                                  | DONE   | ADR-008                        |
| C2    | Allocation engine                                                                                                                                    | DONE   | ADR-009                        |
| C3    | `POST /payments` + idempotency                                                                                                                       | DONE   | ADR-010                        |
| C4    | Due-date billing + maturity auto-close. **The scheduler half is moved to T3**                                                                        | DONE   | ADR-011                        |
| D1    | Penalty calculator + per-date idempotent accrual. **The payment-before-job risk is closed by T4; component-exact precision remains deferred in T10** | DONE   | ADR-012, ADR-014               |
| T1–T3 | Phase-1 decisions, V9 integrity/ShedLock schema, daily billing→penalty job + V10 audit date                                                          | DONE   | ADR-013, V9/V10                |
| T4    | Lazy penalty accrual in the idempotent payment transaction                                                                                           | DONE   | ADR-014                        |
| T5    | Payment write-path conflict retry                                                                                                                    | DONE   | ADR-014 decision 7             |
| T6    | Aging status step in the daily job                                                                                                                   | DONE   | ADR-013 implementation note T6 |
| T23   | Spring Boot 4 dependency alignment (ShedLock 7.10.1, springdoc 3.1.1)                                                                                | DONE   | PR #1, `OpenApiSmokeIT`        |
| T7    | RBAC enforcement + JWT identity hardening (roles claim, matcher table, fail-closed `sub`/`exp`)                                                      | DONE   | ADR-015 (CR-01, CR-10, X-10)   |
| T8    | Aging report (`GET /api/v1/reports/aging`): new read-only `reporting` module, per-installment buckets                                                | DONE   | ADR-013 impl note T8 (A-6/A-7) |
| T9    | Contract statement (`GET /api/v1/contracts/{id}/statement`): ledger-literal rows via a `ledger` read port, filters + paging                          | DONE   | ADR-013 impl note T9 (A-8)     |
| T24   | Idempotency key lifecycle: single-use forever (option A), 409 `IDEMPOTENCY_KEY_EXPIRED`, classifier no longer retries `uq_payment_idempotency`       | DONE   | ADR-017 (A-13, CR-04, CR-13)   |
| T25   | DB accounting backstops: V12 parent-side deferred checks, scoped `uq_journal_entry_event`, `system_parameter` append-only                            | DONE   | ADR-008 d5 note (CR-05/06/12)  |
| T26   | Daily job robustness: `ABANDONED` status + V13, stale-row/loop-escape finalization, keyset batching, retry backoff                                   | DONE   | ADR-013 A-9 note T26 (CR-07/08/09) |
| T29   | Fix contract statement query on PostgreSQL: `cast(...)`-typed nullable binds in `findContractStatement`/`countQuery`, clearing `42P18`                | DONE   | X-13 (bug task)                |
| T11   | Phase-1 test hardening + exit verification: child-table immutability tests, specific-exception assertions, PRD §7 scenarios over HTTP, PII log guard | DONE   | commit `a8fa1a6`               |
| T14   | E3 Excess → `contract_credit` + credit-apply/read endpoints: `ContractCreditPort`, `CreditApplicationEngine`, V14 cap/status triggers, RBAC rows     | DONE   | ADR-009, Addendum §2 (branch `t14-contract-credit`) |
| T15   | E5 Penalty waive/reduce + `EffectivePenaltyPort`: `PenaltyAdjustment` entity, `POST /penalty-adjustments`, `BEBAN_WAIVER_DENDA`/V15 cap, retired contract native read (CR-14) | DONE   | ADR-019, Addendum §16.4 (branch `t15-penalty-waive-effective-port`) |
| T30   | Route schedule response + aging report through the adjustment-aware effective penalty via `EffectivePenaltyPort` (closes ADR-019 D4 / PR #7 F1); schedule `penalty_amount` + aging outstanding now net active waivers | DONE   | ADR-019 D4 (branch `t30-effective-penalty-reads`) |
| —     | Phase A hygiene: V8 SYSTEM hardening, idempotency retention takeover (semantics revisited in T24), open-in-view off                                  | DONE   | `cdce254`                      |

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
second journal); `POST /api/v1/contracts/{id}/credit/apply` (ADMIN_OPERASIONAL) applies available credit
via the new pure `CreditApplicationEngine` (PENALTY → INTEREST → PRINCIPAL, oldest due first), reducing
only recognized receivable through `InstallmentReceivablePort.applyPaymentResolution` and posting one
`Dr TITIPAN_NASABAH / Cr PIUTANG_*` entry (`ref_type=CREDIT_APPLICATION`, `ref_id` = first application id;
service guard, ADR-008 d5); `GET /api/v1/contracts/{id}/credit` (ADMIN_OPERASIONAL + FINANCE) returns
balance + history. Status flips AVAILABLE → APPLIED only at balance 0. Migration V14 adds the deferred
`Σ applications ≤ amount` cap (invariant 11) + the status guard (no `CREATE TABLE`, `uq_journal_entry_event`
untouched). Optimistic-lock retry `ContractCreditRetryingService` (3 attempts, 50/150 ms). Verification
(Docker available): `CreditApplicationEngineTest` (16) + `ContractCreditIT` (10) + `EndpointRoleMatrixIT` +
`OpenApiSmokeIT` pass; full `./gradlew test` = **665 tests, 0 failures**; `./gradlew check` green.

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
  new account is chosen it is seeded in V15). The waiver reverses recognized penalty *receivable*, never
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

Status: DONE (branch `t30-effective-penalty-reads`; closes the PR #7 review finding F1; ADR-019 D4).

**Dependencies:** T15 (`EffectivePenaltyPort` + the paid-aware remaining definition exist).

**Scope.** Close the last read/write inconsistency from ADR-019 D4: the payment-receivable snapshot already
reads effective penalty through `EffectivePenaltyPort`, but two `contract` read paths still show **gross**
penalty, so a waived-but-otherwise-paid installment still appears to owe penalty and can land in an aging
bucket. Route them through the adjustment-aware (and, with F2, paid-aware) effective penalty. Owner module:
`contract` (reads), feeding the per-installment `Σ adjustment` already available via `EffectivePenaltyPort`.

**Business rules (ADR-019 D4; PR #7 review finding F1).**
- `contract.api.InstallmentResponse.from(...)` currently builds the balance with the zero-adjustment
  `InstallmentBalance.of(installment)` and returns `installment.getPenaltyAmount()` (gross) directly; it
  must use the adjustment-aware overload `InstallmentBalance.of(installment, Σ adjustment)` and report the
  effective (remaining) penalty, matching the receivable path.
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

- `contract.api.InstallmentResponse`: new adjustment-aware overload `from(Installment, BigDecimal Σ
  adjustment)` builds the balance with `InstallmentBalance.of(installment, Σ adjustment)` and reports the
  effective (remaining) penalty (`max(0, gross − Σ adjustment)`); the zero-arg `from(Installment)` now
  delegates with `BigDecimal.ZERO`. The wire field `penalty_amount` is unchanged in shape; its meaning
  sharpens from gross accrued to remaining-after-adjustments.
- `contract.application.ContractQueryService.installments(...)`: injects `EffectivePenaltyPort`, loads the
  `Contract` once, and for an ACTIVE contract threads the per-installment `Σ adjustment` into each row. The
  port is only consulted for ACTIVE contracts (it throws 409 otherwise), so DRAFT (empty schedule) and
  closed contracts still return 200.
- `contract.application.AgingReportSourceService`: injects `EffectivePenaltyPort`, queries it once per
  distinct ACTIVE contract, and computes `InstallmentBalance.of(installment, Σ adjustment).outstanding()`,
  so a fully-waived-but-paid installment nets to zero and leaves no bucket.
- New IT `contract.EffectivePenaltyReadConsistencyIT` (2 tests): a clearing waiver drops period 1 from the
  schedule row (zero remaining penalty + zero outstanding), its 1-30 aging bucket, and matches the
  receivable snapshot's effective remaining penalty; and the schedule endpoint stays 200 for DRAFT and
  CLOSED contracts (no 409 from the port routing).

**Verification (2026-10-07, Docker available so the `*IT` suites ran).** Narrowest
`.\gradlew test --tests "*EffectivePenaltyReadConsistencyIT" --tests "*PenaltyAdjustmentIT" --tests
"*AgingReportIT" --tests "*ContractApiIT"`: pass. Full `.\gradlew test`: **87 suites / 695 tests / 0
failures / 0 errors**: the branch base measured 86 suites / 693 tests, and T30 adds the two
`EffectivePenaltyReadConsistencyIT` cases (one suite). (For reference the T15 baseline noted elsewhere was
680 tests / 84 suites.) `.\gradlew check`: pass. Diff is scoped to the two read paths, the response DTO, the
new IT, and docs; no migration, no API-shape change.

### T12 — E1 Settlement quote (5 pts)

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
    (the active period's *earned* slice is excluded — it is in `accrued_interest`; its *unearned*
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

### T13 — E2 Settlement execution (5 pts)

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

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

> **Sprint 5 (T12–T15) is now fully planned** in the "Sprint 5 — Settlement, Credit & Waiver" section
> above (ADR-018, ADR-019). The rows below are the still-stubbed later tasks.

| ID  | Story                                                              | Sprint | Status | Carry-forward requirements from this plan                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| --- | ------------------------------------------------------------------ | ------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T16 | E4 Void payment + synchronous penalty recalc                       | 6      | TODO   | The void re-pricing rule is resolved by ADR-013 (A-3): no re-pricing of already-accrued dates, catch-up only for dates with no row yet. Make `payment.amount` non-updatable (the parent-side deferred checks moved to T25). Revisit the V3 allocation-total trigger, which counts VOIDED rows (C3 note), and T25's payment-insert check for voided payments. Under A-13 (option A) a voided payment's key stays consumed; because `uq_payment_idempotency` only covers `POSTED`, keep that true after T18 deletes the claim row (for example by widening the index to all statuses in the void migration). Void is blocked when credit was consumed (invariant 16). |
| T17 | F1 Consistency check job                                           | 6      | TODO   | Runs after aging in the T3 job, reusing its ShedLock, `job_run`, keyset batching and backoff (T26).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| T18 | F4 Idempotency key cleanup job                                     | 6      | TODO   | Reuses the T3/T26 job infrastructure. Deleting a row must not re-open the key: the T24/A-13 rule and the business-row backstops decide what a later reuse returns. Note (ADR-017 consequences, PR #5 bot review): after the row is deleted the exact 409 depends on which truthful guard fires first (`DUPLICATE_CONTRACT` for a same-request contract retry, `CONTRACT_STATE_INVALID` for a payment against a now-inactive contract), not necessarily `IDEMPOTENCY_KEY_EXPIRED`. Decide here whether a pre-business-logic permanent-key probe is wanted to make the code uniform.                                                                                  |
| T19 | F5 Write-off                                                       | 6      | TODO   | Accrue through the write-off date first. Require `write_off_recorded_by` for TERMINATED at DB level (V4 checks only the reason).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| T20 | Observability baseline (Addendum §10)                              | 6      | TODO   | `X-Request-Id` filter + MDC, JSON logs (`timestamp`, `level`, `request_id`, `contract_id`, `module`), write `idempotency_keys.request_id`. Not owned by any story in `05_SPRINT_PLAN.md` (X-3).                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| T21 | F2 Auth: login/refresh/logout, Argon2id, lockout, refresh rotation | 6b     | TODO   | Issue `roles` as a string array per ADR-015 (T7). Validate `iss`/`aud` once a real issuer exists and consider RS256 (ADR-005, review CR-10). Revisit T7 D2 (active `app_user` check at issue or per request). Add a production guard against the dev-key fallbacks in `docker-compose.yml` (CR-16).                                                                                                                                                                                                                                                                                                                                                                 |
| T22 | G1–G6 Reporting, frontend, demo, outbox                            | 7–8    | TODO   | Unchanged from `05_SPRINT_PLAN.md`. NPL/AR depend on the DPD definition from T1.b.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| T27 | CI and runtime gates (review CR-15)                                | 6      | TODO   | CI runs `./gradlew check` instead of `test`. Add a dependency-vulnerability and a secret scan (justify each tool per `35-coding-standards`). Add `docker compose up` → wait for `/actuator/health` → `/v3/api-docs` smoke → `down`. T23's `OpenApiSmokeIT` already covers the in-JVM part.                                                                                                                                                                                                                                                                                                                                                                          |
| T28 | PII / HMAC key rotation (review CR-11)                             | 7      | TODO   | Key id in the PII envelope (`v2:<kid>:…`), a key ring for decrypt and for HMAC lookups, and a re-keying procedure for `nik_hash`/`phone_lookup` and `idempotency_keys.request_hash` (ADR-004, ADR-007 consequences). New ADR.                                                                                                                                                                                                                                                                                                                                                                                                                                       |

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
T3…T10, T23…T26 ─► T11 exit verification ─► Sprint 5 (T12–T15) ─► Sprint 6 (T16–T20, T27) ─► T21 ─► T22, T28
```
