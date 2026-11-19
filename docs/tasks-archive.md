# Serfira tasks — archive

Historical detail for completed work, moved out of `tasks.md` to keep the live backlog lean. Nothing here
is actionable. The authoritative record of each decision is the cited ADR; the authoritative narrative of
each implementation is `PROGRESS.md` plus the commit history. This file is the long-form implementation
notes and the resolved review tables, kept for historical digging only.

The live backlog, the open tasks, the Current Project State snapshot, and the open-ambiguities table remain
in `tasks.md`. The compact Completed Work index (one line per task, with ADR/commit references) also stays
in `tasks.md`; this file holds the long notes that index points at.

---

## Completed task detail — Sprint 4b (Phase-1 Close A: Penalty Correctness & Daily Job)

**Goal:** Penalty charged every day by an auditable job and by the payment path, no loss when a payment
comes before the job, aging maintained. **Exit:** PRD scenario 2 end-to-end with no manual accrual; a second
run for the same business date writes nothing; `job_run` rows for every step. All DONE.

### T1 — Resolve Phase-1 specification decisions (DONE)

Implementation note (2026-09-29): Recorded in `ADR-013-phase1-close-semantics.md` (Accepted, amends
ADR-012). A-1…A-10 resolved: A-1 accrue-before-resolve invariant (binds T3/T4); A-2/A-3 keep ADR-012
decisions 2/7 unchanged, no schema change (T10 → DEFERRED); A-4/A-5 DPD-start = first chargeable penalty
day, and OVERDUE + partial → PARTIALLY_PAID (code wins; DM diagram corrected); A-6 as_of today-only,
historical → 400 `INVALID_AS_OF_DATE`; A-7 per-installment buckets summed at contract/portfolio level,
ACTIVE-only; A-8 statement ledger-literal (no running balance); A-9 `job_run` one row per job step per
business date, counters count contracts, status COMPLETED/FAILED only; A-10 confirmed, no code change.
Derived docs updated: TS §4.3, DM §1.4, Addendum §6/§10/§14, FE §2.7/§2.9, `05_SPRINT_PLAN.md` §5. No code
or migration changed.

### T2 — Migration V9: job lock table and penalty-accrual integrity (DONE)

Implementation note (2026-09-29): `V9__job_lock_and_penalty_accrual_integrity.sql` creates `shedlock` with
the exact PostgreSQL schema documented by `shedlock-provider-jdbc-template` (library-owned, no audit
columns); attaches `trg_penalty_accrual_immutable` (`block_modification()`) for UPDATE/DELETE on
`penalty_accrual`; tightens `ck_penalty_accrual_days` to `days_late >= 1`. `PenaltyAccrual.version` kept
mapped with a Javadoc note (harmless: insert-only, no setters). Tests added to `BaselineSchemaIT` and
`AccountingInvariantsIT` (V9 section).

### T3 — Daily servicing job: billing → penalty accrual (DONE)

Implementation note (2026-09-29): A contract-owned ACTIVE-id/status port; a transactional per-contract
processor joining billing then penalty in mandatory order; a non-transactional batch orchestrator with
fixed-date validation, SYSTEM audit binding, five-total-attempt retry for optimistic-lock / SQLSTATE
`23505`, non-ACTIVE skip, per-contract failure isolation. Cron and explicit backfill share one public
`LockedDailyServicingJob` ShedLock boundary; JDBC lock uses DB time + ShedLock `KeepAliveLockProvider`; cron
configurable, disabled in tests. `JobRunService` writes `billing` and `penalty-accrual` rows in
`REQUIRES_NEW`. V10 adds/backfills non-null `job_run.business_date`. Docs: TS §1/§2.3, DM job metadata,
Addendum §5/§7.3/§10, ADR-013 A-9. Verification: focused unit + T3 ITs passed; full `test` 56 suites / 411
tests / 0 failures; `check` passed.

### T4 — Lazy penalty accrual in the payment path (DONE)

Implementation note (2026-09-30): Accepted in `ADR-014-lazy-penalty-accrual-in-payment-path.md`, implemented
through the one-way `payment → penalty.application.PenaltyAccrualPort` edge. Inside the idempotency supplier,
one captured business date drives billing → accrual → snapshot → allocation → resolution → payment journal
in one transaction. A completed replay skips every financial step; a failure rolls back everything together.
HTTP ITs cover grace (no accrual), first chargeable day (`1,573.33`), 28-day catch-up (`44,053.24`, total
`1,617,386.57`), penalty-first allocation, replay, multi-installment resolution, rollback, authenticated
audit actor, maturity close. Verification: `PaymentApiIT` 17 pass; full `test --rerun` 413 pass; `check`
pass. No schema or API change.

### T5 — Payment write-path conflict retry (DONE)

Implementation note (2026-09-30): `PaymentRetryingService` (`payment.application`), non-transactional, wraps
`PaymentApplicationService.create` from outside its `@Transactional` boundary; `PaymentController` depends on
it. Each attempt opens its own transaction and idempotency claim (a failed attempt never consumes the key).
Retryable failures classified by a payment-owned `PaymentConflictClassifier`. Policy: 3 attempts, pauses of
50 and 150 ms via an injectable `Sleeper` (`shared.concurrency`); no `spring-retry`. Exhausted retries throw
`PaymentConflictRetriesExhaustedException` (409 `CONCURRENT_MODIFICATION`). `PaymentRetryingServiceTest`
(5 unit) + `PaymentConflictRetryIT` (forces real SQLSTATE `23505` races against Testcontainers). Full
`test --rerun` 420; `check` pass. No schema or API change.

### T6 — Aging status step (DONE)

Implementation note (2026-09-30): ADR-013 "Implementation Note — T6"; TS §1/§2.3, DM §1.4, Addendum
§5/§7.3/§10 synchronized. `Installment.markOverdue(D, grace)` moves only `PENDING`/`PARTIALLY_PAID` with
`outstanding > 0` to `OVERDUE` from `due_date + grace + 1`; resolved states untouched; already-OVERDUE never
rewritten. `InstallmentAgingPort`/`Service` apply it per contract (`Propagation.MANDATORY`). The job calls a
new `@Transactional DailyServicingContractProcessor.age`, a separate transaction after billing → penalty.
Orchestrator runs both steps through one step-generic retry (five attempts) and writes a third `aging`
`job_run` row. Formula pinned to `PenaltyTerms` by `AgingPenaltyParityTest`; no `contract → penalty` edge.
Unit `InstallmentAgingTest` (11), `InstallmentMarkOverdueTest` (11), `AgingPenaltyParityTest` (2),
orchestrator/processor (+6); ITs `InstallmentAgingServiceIT` (4), `DailyAgingJobIT` (6). Full `test --rerun`
460; `check` pass. No migration/API/dependency change.

---

## Completed task detail — Sprint 4c (Phase-1 Close B: Stack Alignment, Access Control & Phase-1 Reads)

**Order:** T23 → T7 → T8/T9. All DONE.

### T23 — Spring Boot 4 dependency alignment (DONE)

Implementation note (2026-10-01): PR #1 (`d5de2ea`, merged `0b282c0`). `shedlock-spring` and
`shedlock-provider-jdbc-template` pinned to `7.10.1`, `springdoc-openapi-starter-webmvc-ui` to `3.1.1`.
`JobSchedulingConfiguration` compiles unchanged against the 7.x API. The 7.10.1 PostgreSQL DDL matches V9,
so no migration (V9 header still cites `6.9.0`, left as is — applied migrations are not edited). springdoc
3.x default paths unchanged, so ADR-005 `PUBLIC_PATHS` still cover them. New `OpenApiSmokeIT` (3 tests):
`/v3/api-docs` 200 without a token and lists `/api/v1/payments` + `/api/v1/contracts`; Swagger UI resolves.
Full `test --rerun` 463; `check` pass. No behavior change.

### T7 — RBAC enforcement and JWT identity hardening (DONE)

Implementation note (2026-10-01): Accepted as ADR-015 (amends ADR-005 d3/d5). Authorities from
`RolesClaimAuthoritiesConverter` reading the `roles` array (`AppRole` mirrors `ck_app_user_role`); a `SYSTEM`
element withholds every authority (D4). Decoder validator is `JwtValidators.createDefault()` + a
`JwtTimestampValidator` with `allowEmptyExpiryClaim=false` + a `sub`-is-UUID `JwtClaimValidator`, so a token
that names no actor is 401 before authenticating (D5, CR-01). The Addendum §3.4 matrix is one matcher table
in `ResourceServerSecurityConfiguration` ending in `denyAll()`, with `dispatcherTypeMatchers(ERROR)
.permitAll()`. `AuditActorBindingFilter` discards a non-UUID-`sub` authentication. Retired the
`AuditedAssetTestController` probe; attribution moved onto `POST /api/v1/contracts`. Test tokens consolidated
into `support/TestJwts`. OpenAPI: every operation documents its 403 + allowed roles. Tests:
`RolesClaimAuthoritiesConverterTest` (12), `EndpointRoleMatrixIT` (38 cells + deny-by-default +
denied-writes-store-nothing), `JwtAuthenticationIT` (16), `ErrorDispatchSecurityIT` (4). Full `test --rerun`
529; `check` pass. No schema/dependency change.

### T8 — Aging report (DONE)

Implementation note (2026-10-02): New read-only `reporting` module. `GET /api/v1/reports/aging` returns
Current/1–30/31–60/61–90/>90 buckets as portfolio total and per contract, in `{data, error}`.
Bucketing/aggregation in `reporting.domain`/`reporting.application`; the DPD calendar (`InstallmentAging`)
and the outstanding formula (`InstallmentBalance`) stay in `contract`, read through the new one-way
`contract.application.AgingReportSourcePort` (TS §1, so no second formula; A-7). Penalty included; penalty
adjustments not subtracted (no E5). ACTIVE-only; `as_of` today-only else 400 `INVALID_AS_OF_DATE`. One matcher
row (ADMIN_OPERASIONAL/FINANCE/MANAJEMEN) with matrix + 401/403 tests. Unit `AgingBucketTest`/
`AgingBreakdownTest`/`AgingReportServiceTest` + `AgingReportIT` + updated `EndpointRoleMatrixIT`. Full `test`
72 suites / 570 tests; `check` pass. No migration/dependency/decision change (A-6/A-7 in ADR-013).

### T9 — Contract statement (DONE)

Implementation note (2026-10-02): `GET /api/v1/contracts/{id}/statement` returns the contract's posted
journal lines oldest first, in `{data, error}`. Endpoint in `contract` (existence check → 404
`CONTRACT_NOT_FOUND`); read through the new read-only `ledger.application.ContractStatementPort`
(`@Transactional(readOnly = true)`) over the existing `contract → ledger` edge. Ledger-literal (ADR-013 A-8):
one row per `journal_line`, `debit`/`credit` the posted amounts, no running balance; reversals surface as
their own rows flagged `is_reversal`. Optional `ref_type` + inclusive business-zone `from`/`to` filters,
`entry_date asc, line.id asc` order, `page`/`size` paging; DRAFT → empty. Roles ADMIN_OPERASIONAL/FINANCE
(MANAJEMEN → 403). `V11__journal_line_contract_statement_index.sql` adds `(contract_id, entry_date)`. Account
name from `LedgerAccount.displayName()`, pinned by `LedgerAccountNameIT`. Tests `ContractStatementIT`, the
flipped `EndpointRoleMatrixIT` cell, `LedgerAccountNameIT`. (Its `*IT` suites were first run with Docker in
T29; see X-13.) No new `ErrorCode`/dependency/decision.

---

## Completed task detail — Sprint 4d (Phase-1 Close C: Integrity Hardening & Exit)

**All DONE.** T10 stays DEFERRED (T1.a kept ADR-012 decision 2; no code change required unless a new
business decision demands component-exact precision).

### T24 — Idempotency key lifecycle (DONE)

Implementation note (2026-10-02): Accepted as `ADR-017-idempotency-key-lifecycle.md` (amends ADR-007 d9,
option A). **ADR-017, not ADR-016** — ADR-016 was already decimal-magnitude bounds. A key is single-use
forever per endpoint; retention only bounds replay. `IdempotencyService` drops `reclaimExpired`; an existing
claim past `expires_at` throws `IdempotencyKeyExpiredException` (409 `IDEMPOTENCY_KEY_EXPIRED`, new
`ErrorCode`) before the supplier runs — no business work, whatever the body. After T18 deletes the row the
permanent backstops give the same code (`ContractCommandService.translateCreateViolation` +
`PaymentApplicationService.translatePaymentViolation`). `PaymentConflictClassifier` is constraint-aware: only
`uk_penalty_accrual` + optimistic-lock retried, never `uq_payment_idempotency` (CR-04).
`PaymentRetryingService.BACKOFF_MILLIS` trimmed to `{50, 150}` (CR-13). No migration. PR #5 bot review raised
two findings (post-row-deletion `DUPLICATE_CONTRACT` / `CONTRACT_STATE_INVALID`) assessed as intended, not
bugs; recorded in ADR-017 consequences and the T18 carry-forward. Docs: ADR-017, TS §2.5, DM, controller
OpenAPI 409 lists. Verification: unit `test --tests "*Test"` 43 suites / 335 tests (incl.
`PaymentConflictClassifierTest`); `*IT` suites later confirmed with Docker.

### T25 — Complete the DB accounting backstops (DONE)

Implementation note (2026-10-03): `V12__accounting_backstops.sql` (**V12, not V11** — V11 was T9's statement
index). Three backstops: (1) parent-side `DEFERRABLE INITIALLY DEFERRED` constraint triggers on INSERT —
`trg_journal_entry_has_lines_deferred` (≥ 2 balanced lines) and `trg_payment_has_allocations_deferred`
(Σ allocations = amount), the only event needing cover (both parents immutable). (2) Partial unique index
`uq_journal_entry_event` on `(ref_type, ref_id) WHERE reversal_of_id IS NULL AND ref_type IN
('CONTRACT_ACTIVATION','BILLING','PENALTY_ACCRUAL','PAYMENT')`, scoped so E2 keeps SETTLEMENT free (amends
ADR-008 d5). (3) `trg_system_parameter_immutable` (`block_modification()` on UPDATE/DELETE). No Java change.
Test cleanups that delete config rows use `support/AppendOnlyTestCleanup` (single-connection
`session_replication_role = replica` bypass; the Testcontainers role is a superuser, the app role is not).
Timing pinned in tests via SQLSTATE: parent-side DEFERRED fail at COMMIT with `P0001`; `uq_journal_entry_event`
fails at INSERT with `23505`; `system_parameter` BEFORE trigger fails at the statement with `P0001`. Three
pre-T25 fixtures made atomic. Docs: ADR-008 T25 note; X-12 closed. New `AccountingInvariantsIT` cases pass;
full `test` 600 tests / 5 failed — all 5 the pre-existing T9 statement bug (X-13 / T29), confirmed on clean
`main`.

### T26 — Daily job robustness (DONE)

Implementation note (2026-10-05): CR-07/08/09 on `DailyServicingOrchestrator`, the `shared.job` lifecycle,
and the `contract` listing port; no money/ledger/HTTP/security change, A-9 counters and three-`job_run`-rows
contract unchanged. **CR-07:** new terminal `JobRunStatus.ABANDONED` + `V13__job_run_abandoned_status.sql`
widening `ck_job_run_status`; `JobRun.abandon`/`failHard`, `JobRunService.abandonStaleRuns`/`failHard`
(`REQUIRES_NEW`). At each locked run start, pre-existing `RUNNING` rows are abandoned (ShedLock guarantees no
live run — no timeout heuristic); if the loop throws, the three rows are finalized `FAILED` and the exception
rethrown. `Error`/`Throwable` deliberately not caught; CR-07(b) finalizes any row they leave on the next run.
**CR-08:** `findActiveContractIdsAfter(afterId, limit)` keyset paging with the all-zero UUID first-page
sentinel (typed non-null, avoids the X-13 `42P18` failure, strictly below `gen_random_uuid()` ids); the
orchestrator pages until a short page. Batch size `serfira.jobs.daily-servicing.batch-size` (`@Value` 500).
**CR-09:** `runWithRetry` injects `Sleeper` and pauses `BACKOFF_MILLIS = {50, 150, 400, 1000}` on the
retryable branch only (exactly `MAX_ATTEMPTS - 1 = 4` entries, the T24/CR-13 lesson). Docs: ADR-013 A-9 note
T26, TS §2.3/§1, Addendum §5/§10. New unit + `DailyJobRobustnessIT`; required daily-job ITs stay green. Full
`check` 621 tests / 5 failed — all 5 the pre-existing `ContractStatementIT` bug (X-13 / T29), confirmed on
unmodified `main` (`a10b552`).

### T29 — Fix the contract statement query on PostgreSQL (bug, X-13; DONE)

Implementation note (2026-10-05): Fixed `JournalLineRepository.findContractStatement` (main `@Query` + its
`countQuery`) by wrapping only the three `is null` guards in HQL `cast(...)`:
`cast(:fromInclusive as timestamp)`, `cast(:toExclusive as timestamp)`, `cast(:refType as string)`. Root
cause: a bare `:param is null` left each nullable bind untyped, so PostgreSQL raised `42P18 could not
determine data type of parameter` and every `/statement` call returned 500. The casts give the bind a
declared type when `null`; comparisons, inclusive `from` / exclusive-upper `to` window, optional `ref_type`
match, ordering, and paging unchanged. Method signature, `StatementLineProjection`, `ContractStatementService`
untouched. Verified: `ContractStatementIT` (9) green; `*LedgerAccountNameIT` (2) green; full `test` 621 / 0
failed; `check` green.

### T11 — Phase-1 test hardening and exit verification (DONE)

Implementation note (2026-10-05): Test-only hardening on branch `t11-phase1-exit-verification` (commits
`a8fa1a6` impl, `a61e971` close-out, `002b9c6` review follow-up); no production code, migration, or unrelated
file changed. (1) `AccountingInvariantsIT` gains four append-only immutability tests for `journal_line`,
`payment_allocation`, `settlement_allocation`, `penalty_adjustment` (UPDATE + DELETE rejected with SQLSTATE
`P0001`, row survives; `penalty_accrual` not duplicated — covered by V9); two bare-`RuntimeException` asserts
upgraded to `P0001`. (2)/(3) The two `isNotNull()` losing-retry asserts in `ContractIdempotencyIT`/
`PaymentIdempotencyIT` now assert `ConflictException` (ADR-017 D1 / TS §2.5). (4) New `Phase1ExitScenariosIT`
drives PRD §7 scenarios 1–4 and 8–9 over HTTP with `TestJwts` + `@Primary FixedClock`, no SQL seeding;
scenario 2b runs the real `LockedDailyServicingJob` for a late date (bills, accrues 28 days as SYSTEM, ages to
OVERDUE) then settles over HTTP; asserts the PENALTY→INTEREST→PRINCIPAL waterfall, overpayment →
`TITIPAN_NASABAH` never auto-applied, partial-payment state, month-end/leap clamping, Σ allocations = amount,
balanced journals. (5) New `PiiLoggingIT` proves raw NIK, raw phone, and normalized `628…` never appear in
logs across create/payment/job (non-vacuous `>= 1 event`). The optional shared TRUNCATE cleanup was a NO-GO
(deferred). Verification (Docker, forced rerun by the orchestrator): `check` pass; `test --rerun-tasks` 80
suites / 633 tests / 0 failures. Two-axis `/code-review` APPROVED after scenario 2b closed the one spec
finding.

---

## Resolved spec/implementation discrepancies (X-1 … X-13)

Each row cites where it was resolved; detail lives in the cited ADR/task.

| #    | Discrepancy                                                                 | Resolution                                                               |
| ---- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| X-1  | Payment retries optimistic-lock up to 3× then 409 `CONCURRENT_MODIFICATION` | T5 (`PaymentRetryingService`); constant tidied T24 (CR-13)               |
| X-2  | Daily jobs use ShedLock, retry per record up to 5×                          | T2/T3 (V9 lock, DB-time renewable lock)                                  |
| X-3  | `X-Request-Id`, JSON logs, `request_id` on `idempotency_keys`               | T20 (`shared/observability` filter + Boot 4 ECS + claim write)           |
| X-4  | DM §1.4 lacked `OVERDUE → PARTIALLY_PAID`                                   | A-5 → T1 (code wins; DM corrected)                                       |
| X-5  | Void catch-up delta when expected > recognized                              | A-3 → T1 (ADR-012 d7: catch-up only for date-less rows); E4/T16          |
| X-6  | Penalty base = unpaid pokok+bunga only                                      | A-2 → T1 (ADR-012 d2 kept: paid penalty also reduces base); T10 DEFERRED |
| X-7  | `penalty_accrual` append-only / `days_late >= 1`                            | T2 (V9 trigger + CHECK)                                                  |
| X-8  | Statement debit/credit per entry                                            | A-8 → T1 (ledger-literal); built in T9                                   |
| X-9  | TS §7 repo/package names                                                    | Doc-only, DEFERRED                                                       |
| X-10 | Non-UUID `sub` fails closed                                                 | T7 (ADR-015 D5: 401 at decoder + defensive filter)                       |
| X-11 | Idempotency key "may be claimed again" after retention                      | T24 (ADR-017: single-use forever; 409 `IDEMPOTENCY_KEY_EXPIRED`)         |
| X-12 | `system_parameter` append-only                                              | T25 (V12 `block_modification()`)                                         |
| X-13 | Statement query 500 on PostgreSQL (`42P18`)                                 | T29 (`cast(...)`-typed nullable binds)                                   |

---

## External review 2026-09-30 — CR-01 … CR-16 (resolved/mapped)

An external AI review of `848e8a3` reported 16 findings, renumbered CR-01…CR-16 to avoid colliding with the
2026-09-29 F-numbers. Each was checked against source, migrations, CI and the upstream compatibility
matrices on 2026-09-30 (suite not re-run for that check; last green run then was the T6 run, 460 tests).

| ID    | Finding                                                 | Verdict / task                                                                           |
| ----- | ------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| CR-01 | Non-UUID `sub` stays authenticated, writes as `SYSTEM`  | Fixed T7 (ADR-015 D5): decoder `sub`-is-UUID validator 401s; filter discards defensively |
| CR-02 | ShedLock 6.9.0 on Boot 4.1.1                            | Unsupported pairing; fixed T23 (→ 7.10.1)                                                |
| CR-03 | springdoc 2.8.9 on Boot 4                               | Boot 4 needs springdoc v3; fixed T23 (→ 3.1.1), `OpenApiSmokeIT`                         |
| CR-04 | Retention takeover vs permanent business-row keys       | Severity lowered (no double execution); A-13 option A → T24                              |
| CR-05 | Ledger one-entry-per-event only in Java                 | Documented (ADR-008 d5); defense-in-depth DB guard T25, scoped to current ref types      |
| CR-06 | Zero-line entry / zero-allocation payment bypass V3     | V3 triggers child-only; parent-side checks T25                                           |
| CR-07 | `job_run` stuck `RUNNING`                               | Fixed T26 (`ABANDONED` + V13, loop-escape finalization)                                  |
| CR-08 | All ACTIVE ids loaded at once                           | Fixed T26 (keyset paging, zero-UUID sentinel, batch size)                                |
| CR-09 | Daily retry without backoff                             | Fixed T26 (`Sleeper`, `{50,150,400,1000}` ms)                                            |
| CR-10 | No `iss`/`aud`/active-user/role checks                  | Roles + `exp` fixed T7; `iss`/`aud`/active-user need a real issuer → T21                 |
| CR-11 | No key rotation                                         | Documented (ADR-007 consequences); → T28                                                 |
| CR-12 | `system_parameter` append-only by convention only       | Fixed T25 (V12 `block_modification()`; `AppendOnlyTestCleanup`)                          |
| CR-13 | 50/150/400 ms documented, 50/150 ms real                | `BACKOFF_MILLIS[2]` unreachable; corrected in the re-plan, constant tidied T24           |
| CR-14 | Outstanding formula differs across read and write paths | Dormant (no `penalty_adjustment` write path); → T15                                      |
| CR-15 | CI runs only `test` + compose build                     | → T23 (smoke IT) + T27                                                                   |
| CR-16 | Committed dev keys in Compose, no production guard      | Documented (ADR-005, README); → T21                                                      |

Corrections to the review itself: CR-04 is not HIGH (permanent backstops prevent any second business row;
the defect was a misleading error plus wasted work). CR-05 ignored ADR-008 decision 5 (per-event DB guards
are why no race exists today). CR-02/CR-03 are compatibility risks, not observed failures.

---

## Historical verification snapshots

Point-in-time run records kept for audit; the live `tasks.md` carries only the latest (T11).

- **2026-10-02 (through T8):** `compileJava compileTestJava` pass; narrowest T8 tests pass; full `test` 72
  suites / 570 tests pass; `check` pass.
- **2026-10-02 (T9) — Docker unavailable:** compile pass; all unit `*Test` pass; `*IT` deferred to a Docker
  run (done in T29).
- **2026-10-02 (T24) — Docker unavailable:** compile pass; unit `test --tests "*Test"` 43 suites / 335 tests
  pass (incl. `PaymentConflictClassifierTest`); `*IT` deferred to a Docker run.
- **2026-10-03 (T25) — Docker available:** compile pass; `AccountingInvariantsIT` (32) pass; full `test` 600
  tests / 5 failed, all the pre-existing T9 statement bug (X-13), confirmed on clean `main`.
- **2026-10-05 (T29) — Docker available:** `ContractStatementIT` (9) pass; `*LedgerAccountNameIT` (2) pass;
  full `test` 621 / 0 failed; `check` pass.
- **2026-10-05 (T11) — Docker available:** `check` pass; forced full `test --rerun-tasks` 80 suites / 633
  tests / 0 failures.

---

## Completed task detail — Sprint 5 (Settlement, Credit & Waiver, Epic E)

Planned 2026-10-05 via `/grill-with-docs`; decisions in **ADR-018** (settlement) and **ADR-019**
(effective-penalty port). Order was T14 → T15 → T12 → T13, then the T30 read-consistency follow-up. The
full narrative is in `PROGRESS.md`; the one-line index is in `tasks.md`. New `settlement` module
(`api/application/domain/infrastructure`, ADR-001) depending on `ledger`, `contract` (ports), `penalty`
(`EffectivePenaltyPort`). No new tables (the settlement/credit tables and their V5 immutability exist since
V1); migrations V14–V17 are thin behaviour backstops.

### T14 — E3 Excess → `contract_credit` + credit-apply/read endpoints (DONE)

Implementation note (branch `t14-contract-credit`). A payment's EXCESS allocation books one AVAILABLE
`contract_credit` row (sub-ledger of `TITIPAN_NASABAH`, not a second journal) via the new
`ContractCreditPort` (new `payment → contract` edge; `uk_contract_credit_source` = one credit per EXCESS
allocation). `POST /api/v1/contracts/{id}/credit/apply` (ADMIN*OPERASIONAL) applies available credit to the
oldest recognized-receivable installment through the pure `CreditApplicationEngine` (PENALTY → INTEREST →
PRINCIPAL, oldest due first); partial application allowed, one credit may fund many applications, never
auto-applied; reduces only recognized receivable via `InstallmentReceivablePort.applyPaymentResolution`;
one `Dr TITIPAN_NASABAH / Cr PIUTANG*\*` journal per call (`ref_type=CREDIT_APPLICATION`, service guard per
ADR-008 d5). `GET /api/v1/contracts/{id}/credit`(ADMIN_OPERASIONAL + FINANCE) returns balance + history.
Status flips AVAILABLE → APPLIED at balance 0. V14 adds the deferred`Σ applications ≤ amount`cap
(invariant 11) + status guard (no`CREATE TABLE`). `ContractCreditRetryingService`(3 attempts, 50/150 ms).
Verification (Docker available):`CreditApplicationEngineTest`(16) +`ContractCreditIT`(10) +`EndpointRoleMatrixIT`+`OpenApiSmokeIT`pass; full`test`= 665 tests, 0 failures;`check` green.

### T15 — E5 Penalty waive/reduce + `EffectivePenaltyPort` (DONE)

Implementation note (branch `t15-penalty-waive-effective-port`). First writer of `penalty_adjustment`: new
append-only `PenaltyAdjustment` entity (`penalty.domain`). `POST /api/v1/penalty-adjustments`
(ADMIN_OPERASIONAL) records WAIVE/REDUCE and posts one `Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA`
(`ref_type=PENALTY_WAIVER`); `approved_by` from the JWT `sub`, never the body. `BEBAN_WAIVER_DENDA` is a new
EXPENSE account seeded in V15 (not a reuse of `DISKON_PELUNASAN`, reserved for the settlement rebate,
ADR-018 D3). New `EffectivePenaltyPort`/`EffectivePenaltyService` is the single effective-penalty source
(invariant 9, clamped ≥ 0); the native `InstallmentRepository.sumPenaltyAdjustmentsByInstallmentIds` read
in `contract` is removed and both call sites route through the port (new `contract → penalty` edge, closes
CR-14). V15 seeds the account + the deferred `assert_penalty_adjustment_cap`; `uq_journal_entry_event` was
NOT extended (service guard suffices, ADR-008 d5). A `PaymentApiIT` corrupt-snapshot test became
`anOverWaiverExceedingAccruedPenaltyIsRejectedByTheDbCap` (invariant 9 intentionally changed; spec-driven
expected-value change). Verification (Docker available): `EffectivePenaltyFormulaTest` (5) +
`PenaltyAdjustmentIT` (7) + role/ledger ITs pass; full `test` = 680 tests / 84 suites / 0 failures; `check`
green.

**PR #7 fix-pass (F2/F3/F4/F5).** Effective penalty became `max(0, gross − Σ adjustment − Σ paid penalty)`,
with the paid-penalty term read via a new `payment.application.PenaltyAllocationPort` and migration **V16**
teaching the DB cap to subtract Σ PENALTY `payment_allocation` (V15 left as-is, forward-only). Waiver now
recomputes installment resolution + MATURITY close via `InstallmentStatusRecomputePort`, and
`PenaltyAdjustmentRetryingService` (3 attempts, 50/150 ms) serializes concurrent waivers → 409
`CONCURRENT_MODIFICATION`. `F1` (schedule/aging read paths) was deferred to T30.

### T30 — Schedule response + aging report through effective penalty (DONE)

Implementation note (branch `t30-effective-penalty-reads`; closes ADR-019 D4 / PR #7 F1 and PR #8 F1–F4).
Both `contract` read paths now net the per-installment `Σ adjustment` sourced through `EffectivePenaltyPort`
(the same source as the receivable snapshot and the write-time cap, no second formula, no native
`penalty_adjustment` read). `InstallmentResponse` reports the port's `effective` (remaining) penalty verbatim
on `penalty_amount` (wire shape unchanged; meaning is remaining after adjustments and paid penalty) and nets
only Σ adjustment into `outstanding`. `ContractQueryService.installments(...)` branches on status (DRAFT →
empty, ACTIVE → `loadEffectivePenalty`, closed → `loadEffectivePenaltyAnyStatus`), every status 200.
`AgingReportSourceService` uses one batch call `loadEffectivePenalty(Collection<UUID>)`. PR #8 fixes: F1
report the port value verbatim (don't recompute and drop the paid term); F2 status-agnostic reads for closed
contracts; F3 batch the aging call; F4 doc/OpenAPI drift. IT `EffectivePenaltyReadConsistencyIT` added.
Verification (Docker available): narrowest ITs pass; full `test` = 87 suites / 697 tests / 0 failures;
`check` green. No migration, no API-shape change.

### T12 — E1 Settlement quote (DONE)

Implementation note (branch `t12-settlement-quote`; ADR-018). New `settlement` module; `POST
/api/v1/settlements/quote` (ADMIN_OPERASIONAL). `SettlementQuoteCommandService` runs accrue-before-resolve
first (ADR-013/ADR-018 D8), then reads the settlement snapshot through the new `contract`
`SettlementReceivablePort` (exposes scheduled `interestAmount` + `contract.version`, which
`InstallmentReceivablePort` deliberately omits), effective penalty via `EffectivePenaltyPort`, and available
credit via the new `ContractCreditPort.availableCredit`. The pure `SettlementQuoteEngine` prices components
(ADR-018 D2/D6/D7): outstanding principal, unpaid billed interest, active-period running interest (ACT/30,
cap 30 days), effective penalty, net future interest after the 50% rebate, plus admin fee; `credit_used = 0`
(consumption is T13). Immutable `SettlementQuote` persisted QUOTED with `valid_until` and
`contract_version`; no journal, no money move. `SettlementQuoteRetryingService` (3 attempts, 50/150 ms)
retries the accrue-vs-job race → 409 `CONCURRENT_MODIFICATION`. No migration. RBAC row added. TS §1 gains
the `settlement → contract` / `settlement → penalty` edges. PR #9 fixes: F1 (HIGH)
`resolvedBeyondPenalty = max0(resolvedAmount − paidPenalty)` (the waiver was double-subtracted); F3 (MEDIUM)
retry the `uk_penalty_accrual` race; F5 (LOW) restore markdown-corrupted identifiers; F2/F4 assessed
(F2 credit-funded-penalty gap deferred to the port definition; F4 by design — TTL + D9 re-validation).
Verification (Docker unavailable): compile pass; all `*Test` pass incl. `SettlementQuoteEngineTest` (5),
`SettlementQuoteCommandServiceMappingTest` (3), `SettlementQuoteRetryingServiceTest` (6); `SettlementQuoteIT`
compiles, run under Docker in CI.

### T13 — E2 Settlement execution (DONE)

Implementation note (branch `t13-settlement-execution`; ADR-018 D1/D4/D5/D9/D10). `POST /api/v1/settlements`
(ADMIN*OPERASIONAL, `Idempotency-Key`, `quote_id` body) executes a QUOTED quote. The write path
(`SettlementExecutionCommandService`, wrapped by `SettlementExecutionRetryingService` — 3 attempts,
50/150 ms) re-runs accrue-before-resolve, re-prices with the same `SettlementQuoteEngine`, and revalidates
every recomputed component and `contract.version` against the snapshot (D9): mismatch →
`STALE_SETTLEMENT_QUOTE`; past `valid_until` → `SETTLEMENT_QUOTE_EXPIRED`; already-EXECUTED with a different
key → `SETTLEMENT_QUOTE_ALREADY_EXECUTED` (`uk_settlement_quote_id`). Consumes all AVAILABLE credit (D10) via
`ContractCreditPort.consumeAllAvailableCredit`; `available_credit > gross` → `CREDIT_EXCEEDS_SETTLEMENT`.
Posts one balanced SETTLEMENT journal (D1/D4): Dr `KAS` + `TITIPAN_NASABAH`; Cr `PIUTANG*\*`+`PENDAPATAN_BUNGA`(accrued + future-charged) +`PENDAPATAN_ADMIN`; no `DISKON_PELUNASAN`(D3); zero
components emit no line.`settlement_allocation`records receivable resolution only (pure`SettlementResolutionEngine`, PENALTY→INTEREST→PRINCIPAL oldest-first, D5); `settlement_credit_application`records each consumed source. Open installments → SETTLED and the contract closes SETTLEMENT via the
contract-owned`SettlementClosePort`(never the MATURITY path, D10). Idempotent via`IdempotencyService`.
Four new 409 `ErrorCode`s. Migration **V17** extends `uq_journal_entry_event`to SETTLEMENT and teaches the
V14 credit cap/status guards to count`settlement_credit_application`. The superseded golden
`AccountingInvariantsIT.settlementEventsMayStillPostSeveralEntries`was rewritten to assert the second
non-reversal SETTLEMENT entry is rejected (spec intentionally changed, ADR-018 D1). PR #10 fixes (Docker
available): (1) HIGH`assert_contract_credit_application_cap()`branches on`TG_TABLE_NAME`; (2) MAJOR
`SettlementExecutionIT.seedAvailableCredit`wraps the seed in one transaction and sets EXCESS`installment_id = NULL`; (3) MEDIUM `settlement_credit_application`made append-only; (4) MINOR Σ consumed ==`credit_used`assertion. Full`test`= 744 tests, 0 failures;`check` green.

---

## Completed task detail — Sprint 6 (Void, Consistency & Write-off)

Planned 2026-10-11 via `/grill-with-docs`; void semantics in **ADR-020**, the ADR-017 T18 probe item
resolved (declined) in an ADR-017 addendum. Order was T16 → T17 → T19 (T18/T27a/T31 remain open in
`tasks.md`). All three completed tasks are merged to `main`: T16 via PR #11, T17 via PR #12, T19 via PR #13.

### T16 — E4 Payment void (DONE, merged PR #11)

Implementation note (branch `t16-payment-void`; ADR-020 D1–D6). `POST /api/v1/payments/{id}/void`
(ADMIN_OPERASIONAL, `Idempotency-Key`, optional `reason`) reverses a POSTED payment. The write path
(`PaymentVoidApplicationService`, wrapped by `PaymentVoidRetryingService` — 3 attempts, 50/150 ms) flips the
payment POSTED → VOIDED (`Payment.markVoided` — amount and allocations kept as history, D1), recomputes each
affected installment from the remaining POSTED payments via `PaymentVoidPort.applyVoidRecomputation`
(absolute paid totals via `Installment.resetPaidAmountForVoid`, demoting PAID → PARTIALLY_PAID → PENDING,
`Contract.reopenFromMaturity` undoing a MATURITY close, D1.3), reverses the payment's EXCESS credit when it
is still entirely AVAILABLE (`ContractCreditPort.reverseAvailableCreditFromAllocations`, row lock then
delete, D4), and posts one mirrored reversal journal (`LedgerPostingService.postReversal`, Dr/Cr swapped,
`reversal_of_id` set, no new `LedgerRefType`, D1.2). Eligibility guard (D5) over every affected installment:
SETTLED/WRITTEN_OFF → 409 `PAYMENT_NOT_VOIDABLE`; consumed credit → 409 `PAYMENT_CREDIT_CONSUMED`. No
catch-up accrual (D2). Idempotent. V18 drops the `WHERE status = 'POSTED'` predicate from
`uq_payment_idempotency` (all statuses, fail-safe preflight, D3). PR #11 fixes: (1) MATURITY void allowed
(ADR-020 D5 corrected to contradict-free with D1.3); (2) blank-reason default `"(no reason given)"` to
satisfy V4; (3) recompute adds surviving applied credit; (4) `paid_at` re-stamped to the earliest surviving
resolving instant. Deferred: void-key single-use-forever → T31. Verification (Docker unavailable): compile
pass; `*Test` = 57 suites / 429 tests / 0 failures; `*IT` (`PaymentVoidIT`, extended `EndpointRoleMatrixIT`)
compile, run under Docker in CI.

### T17 — F1 Consistency check job (DONE, merged PR #12)

Implementation note (branch `t17-consistency-check-job`; Addendum §7.1/§7.3). A fourth daily step
(`consistency-check`) after aging, reusing the T3/T26 ShedLock, `job_run` lifecycle, keyset paging and
backoff — no new scheduler/lock/batching. Read-only reconciliation: records drift as
`reconciliation_exception` rows, never corrects money. Three checks in the pure `ConsistencyCheckEngine`:
A `PAYMENT_CASH` (Σ POSTED `Payment.amount` = net `KAS` from PAYMENT entries), B `ALLOCATION_PAYMENT` (Σ
POSTED `PaymentAllocation.amount` = Σ `Payment.amount`), C `RECEIVABLE_INSTALLMENT` (Σ residual = net
`PIUTANG_*`). `check_type` values match the V1 `ck_recon_exception_type` CHECK, so no new CHECK. The three
`check_type`s match Addendum §7.1 exactly. `ConsistencyCheckService` runs one `REPEATABLE_READ` transaction
per contract (consistent snapshot across all five reads) and gathers each side only through the owning
modules' read ports: the new `payment.application.PaymentReconciliationPort`, the new
`ledger.application.ContractLedgerBalancePort`, and the new `contract.application.ReceivableReconciliationPort`
(nets adjustments through `EffectivePenaltyPort`). `ReconciliationException` writer owned by `penalty`.
Recording idempotent per `(contract_id, check_date, check_type)` via an OPEN-row existence check backed by
migration **V19** (`uq_recon_exception_open`, partial on `status = 'OPEN'`). A-9: `records_processed` =
contracts checked, `records_failed` = contracts that drifted or errored. No endpoint, no RBAC row (the
Finance read endpoint stays deferred to `reporting`). The daily-job ITs were updated to expect four `job_run`
rows. TS §1 gains the three `penalty →` consistency-read edges. PR #12 fixes: REPEATABLE_READ isolation,
idempotent recording + V19, markdown restored. Verification (Docker unavailable): compile pass; `*Test` =
58 suites / 443 tests / 0 failures incl. `ConsistencyCheckEngineTest` (8); `*IT` compile, run under Docker
in CI.

### T19 — F5 Write-off (DONE, merged PR #13)

Implementation note (branch `t19-write-off`; DM §1.3/§1.12, ADR-013 §1). `POST /api/v1/contracts/{id}/write-off`
(ADMIN*OPERASIONAL, `Idempotency-Key`, required `reason`). The write path (`ContractWriteOffCommandService`,
wrapped by `ContractWriteOffRetryingService` — 3 attempts, 50/150 ms, retries optimistic-lock and the
`uk_penalty_accrual` write-off-vs-job race) runs validate → compute → write → post → commit:
accrue-before-resolve first, then the pure `WriteOffEngine` caps each open installment's recognized
receivable (effective penalty via `EffectivePenaltyPort`, then unpaid recognized interest, then unpaid
principal; future unrecognized interest is never written off), moves open installments to `WRITTEN_OFF`
(`Installment.writeOff`, invariant 15), closes the contract ACTIVE → TERMINATED (`Contract.terminateByWriteOff`,
actor from the JWT `sub`, never the body), and posts one balanced `WRITE_OFF` journal (Dr
`BIAYA_PENGHAPUSAN_PIUTANG`; Cr `PIUTANG**`per component; zero component emits no line; service guard per
ADR-008 d5,`WRITE_OFF`left out of`uq_journal_entry_event`). Idempotent; non-ACTIVE → 409
`CONTRACT_STATE_INVALID`. No new `ErrorCode`. Migration authored as V19 on the branch was renumbered
forward-only to **V20** at merge because T17's PR #12 claimed V19 first;
`V20\_\_write_off_recorded_by_required.sql`strengthens`ck_contract_terminated_coherence`to require both`write_off_reason`and`write_off_recorded_by`on a TERMINATED contract. RBAC row added. Verification (Docker
unavailable): compile pass;`*Test`= 60 suites / 448 tests / 0 failures incl.`WriteOffEngineTest`(5),`InstallmentWriteOffTest`(8),`ContractTerminateByWriteOffTest`(6);`\*IT` (`ContractWriteOffIT`, extended
`EndpointRoleMatrixIT`) compile, run under Docker in CI.

---

## Completed task detail — Sprint 6 (continued) & Sprint 6b (DONE)

Moved out of `tasks.md` on 2026-11-15. These are the implementation records for T18, T20, T21a, T27a and
T31; the live backlog no longer reproduces their task scopes, and the one-line index stays in
`tasks.md` -> Completed Work.

---

### T21a — Auth foundation (3 pts)

**Status: DONE** (merged to `main` via PR #19, `278c57e`; review fixes in `070e7ad`). **Dependencies:** T20
(DONE), T7/ADR-015 (DONE). Owner:
new `auth` module + `shared/security` (decoder only). Source: Addendum §3.1–§3.4, §18.3–§18.4; ADR-005;
ADR-015; ADR-022.

**Implementation note (2026-11-15).** New `com.serfira.auth` module (`api`/`application`/`domain`/
`infrastructure`). It reuses the V1 `app_user`/`refresh_token` tables — no schema migration. `AppUser` and
`AppUserRepository` read users; `RefreshToken` and `RefreshTokenRepository` issue the stateful half T21b
will rotate. `AuthConfiguration` provides an `Argon2PasswordEncoder` (`defaultsForSpringSecurity_v5_8()`:
`m=16384,t=2,p=1`, 16-byte salt, 32-byte hash) backed by new dependency
`org.bouncycastle:bcprov-jdk18on:1.80` (Argon2 KDF only; no `bcpkix`). `AuthService.login` rejects every
failure with the same 401 `UNAUTHORIZED` (`AuthenticationFailedException`), pays one Argon2 verification
against a startup dummy hash for unknown usernames, resets `failed_login_attempts`, sets `last_login_at`,
and issues: a 15-minute HS256 access token (`sub` = `app_user.id`, `roles` = one-element string array,
`iat`/`exp`) and an opaque UUID refresh token stored only as its SHA-256 hex in `refresh_token.token_hash`
(7-day expiry, `created_by`/`updated_by` = user). `SYSTEM` cannot log in (inactive + non-Argon2 hash).
`POST /api/v1/auth/login` is the new `AuthController` endpoint; it is `permitAll` in
`ResourceServerSecurityConfiguration` with its own explicit matrix row (all three business roles).
`ResourceServerSecurityConfiguration.tokenValidator` now also requires `sub` to resolve to an **active**
`app_user` (`SELECT EXISTS …`), rejecting unknown/inactive subjects with 401 — closing T7-D2 and amending
ADR-015 D2. `R__demo_seed.sql` (repeatable; originally `V99`) lives in `db/demo`;
`application-demo.properties` adds that location, and `docker-compose.yml` defaults
`SPRING_PROFILES_ACTIVE=demo`, so only the demo path seeds `admin`/`finance`/`manager` with Argon2id
hashes. Tests: `AuthLoginIT` (7: claims/lifetime/hashed refresh
token + reset/last-login; wrong password; unknown user; inactive user; unknown `sub`; inactive `sub`;
post-login write stamps `created_by`/`updated_by`); `EndpointRoleMatrixIT` gains the `/auth/login` row (3
cells); `ErrorDispatchSecurityIT` now seeds an active actor for its bearer tokens. **Verification:**
`compileJava`/`compileTestJava` → BUILD SUCCESSFUL; full `check` with Docker/Testcontainers active →
BUILD SUCCESSFUL, 112 suites / 866 tests / 0 failed (includes `AuthLoginIT`,
`EndpointRoleMatrixIT` and `ErrorDispatchSecurityIT`). `AuthClockTestConfiguration` anchors its fixed
instant to suite start: a hard-coded past instant made freshly minted tokens already expired against
the decoder's real clock, which the local IT run caught. CI remains the merge gate.

**PR #19 review fixes (2026-11-15, commit `070e7ad`).** Login now passes its Argon2 verification through
a `Semaphore` of `serfira.auth.argon2.max-concurrent-verifications` permits (default 2, validated >= 1):
a caller that cannot acquire a permit fails fast with **503 `AUTHENTICATION_CAPACITY_EXCEEDED`** instead
of queueing unbounded 16 MiB KDFs (ADR-022 D9). The accepted-hash prefix was narrowed from `$argon2` to
`$argon2id$`, so a legacy Argon2i/Argon2d encoding can never authenticate (ADR-022 D2). The demo seed was
renamed `V99__demo_seed.sql` → **`R__demo_seed.sql`** (repeatable migration; `ON CONFLICT (username) DO
NOTHING` keeps re-application harmless, at the cost of not updating an existing demo row's hash) and
ADR-022 D8 documents it. `docker-compose.yml` now honours an explicitly empty `SPRING_PROFILES_ACTIVE`
(`${SPRING_PROFILES_ACTIVE-demo}` instead of `:-`) and publishes the API on `127.0.0.1` only.

### T20 — Observability baseline (3 pts)

**Status: DONE** (merged to `main`, PR #18). **Dependencies:** none. Owner: `shared/observability` (new) +
`shared/idempotency`. Source: Addendum §10; resolves X-3.

**Implementation note (2026-11-07).** New `shared/observability` package. `CorrelationIdFilter`
(`OncePerRequestFilter`) reads `X-Request-Id` or generates a UUID when absent/blank/longer than 64 chars,
binds it in the MDC, echoes it on the response header, and clears the MDC in `finally`; registered via
`FilterRegistrationBean` at `SecurityFilterProperties.DEFAULT_FILTER_ORDER - 10`, ahead of Spring Security
in both the embedded container and Boot 4 MockMvc (a bare `Filter` bean would run after the security chain
in MockMvc and lose the id on authentication failures; `SecurityFilterProperties` lives in
`spring-boot-security` in Boot 4). `LogContext` adds scoped `module`/`contract_id` MDC (`AutoCloseable`,
ownership-tracked). Structured logging is Boot 4 native ECS (`logging.structured.format.console=ecs`,
`service.name=serfira-core`), no logback XML and no new dependency; the test profile keeps plain-text
console output. X-3: `IdempotencyService` captures `CorrelationId.current()` at claim time into
`idempotency_keys.request_id` (nullable column already in V1, so no migration). Tests:
`StructuredLoggingFormatTest` (2) and `CorrelationIdFilterIT` (6). Verification (Docker available):
`.\gradlew test` = 111 suites / 856 tests / 0 failed; `.\gradlew check` green.

**PR #18 review fixes (2026-11-10, commit `0c05dfe`).** Removing a nested `LogContext` scope no longer
removes the outer scope's keys; `LogContext` is wired into the production write paths (contract create,
credit, write-off, payment, daily servicing); `CorrelationIdFilter` also handles the `ERROR` dispatch type;
Addendum §3.1 corrected to say auth uses the existing V1 `app_user`/`refresh_token` tables (no new table).
No new test-count record for this commit.

### T18 — F4 Idempotency key cleanup job (2 pts)

**Status: DONE** (merged to `main`, PR #14). **Dependencies:** T3/T26 daily-job infrastructure;
ADR-017 (lifecycle, including the 2026-10-11 probe resolution); ADR-020 D3 (the all-status
`uq_payment_idempotency` that keeps a voided key spent after cleanup).

**Scope.** Add a job that deletes `idempotency_keys` rows past their retention window (`expires_at` passed),
reusing the T3/T26 job infrastructure (ShedLock, `job_run`, keyset batching, backoff). Owner: the daily-job
host (or `shared/job`). Purely hygiene — it bounds table growth; the permanent DB backstops, not the claim
rows, are the correctness authority once a row is gone.

**Business rules (ADR-017 D1–D3 + the 2026-10-11 resolution; TS §2.5).**

- Delete only rows whose `expires_at` has passed. The delete must **not** re-open a key: a later reuse of a
  deleted-and-expired key is still rejected, because the permanent business-row backstops
  (`uq_contract_idempotency`, and the ADR-020-D3-widened all-status `uq_payment_idempotency`) hold
  independently of the claim row.
- **No pre-business-logic permanent-key probe (resolved 2026-10-11, ADR-017 addendum).** The 409 a reused
  spent key returns after cleanup is whichever truthful guard the re-run reaches first (`DUPLICATE_CONTRACT`,
  `CONTRACT_STATE_INVALID`, or `IDEMPOTENCY_KEY_EXPIRED` from a backstop translator). These varied codes are
  accepted; the probe is explicitly declined. The invariant that matters — a cleaned-up claim can never
  permit a duplicate business row — is held by the backstops.
- Writes one `job_run` row (`job_name = "idempotency-cleanup"`, `records_processed` = rows deleted, A-9
  granularity and status rules). Keyset batching + backoff from T26.

**Acceptance criteria.** The job deletes only expired rows and leaves live ones; records a correct `job_run`
row. After the job deletes an expired claim, re-issuing that key cannot create a second contract or payment —
rejected with one of the truthful 409s. A voided payment's key stays spent after cleanup (ties to ADR-020
D3). Crash leaves a `RUNNING` row the next run marks `ABANDONED` (T26, unchanged).

**Tests.** IT `IdempotencyCleanupIT` (Testcontainers): expired rows deleted, live rows kept; `job_run`
granularity; after cleanup, a reused spent key cannot mint a second contract/payment; a voided payment's key
still spent (ADR-020 D3). `FixedClock`.

**Implementation note (2026-10-21).** Additive, owned by `shared/idempotency` (cohesion with the table owner;
no cross-module reach). New `IdempotencyCleanupJob` (non-transactional orchestration: run-as-SYSTEM,
`abandonStaleRuns`, one `job_run` named `idempotency-cleanup`, keyset-paged delete, T26 backoff
`{50,150,400,1000}`/5 attempts, `complete`/`failHard`), `IdempotencyKeyBatchDeleter` (`REQUIRES_NEW`
per-page delete, separate `@Service` to dodge the self-invocation proxy pitfall), `LockedIdempotencyCleanupJob`
(`@SchedulerLock(name="idempotency-cleanup")`) and `IdempotencyCleanupScheduler` (`@Scheduled` cron 01:30
Asia/Jakarta, after the 01:00 daily servicing, distinct lock). `IdempotencyKeyRepository.deleteExpiredBatch`
is a native keyset `DELETE … WHERE id IN (SELECT … WHERE expires_at < :now ORDER BY expires_at, id LIMIT
:limit)`. Migration **V21** adds `idx_idempotency_keys_expires_at` only — no table/constraint/backstop
change, and **no permanent-key probe** (ADR-017 2026-10-11 resolution). Three `serfira.jobs.idempotency-cleanup.*`
keys in both `application.properties` (test cron `-`). Verification: `IdempotencyCleanupIT` (5 cases) +
`IdempotencyRetentionIT` (3) green; `compileJava`/`compileTestJava` clean. The full `./gradlew test` run
showed 7 failures in unrelated suites (`BaselineSchemaIT`, `PaymentVoidIT`, `ConsistencyCheckIT`,
`DailyServicingJobIT`) that reproduce identically on the clean branch tip with the T18 changes stashed —
pre-existing shared-DB IT ordering pollution, not introduced by T18; `./gradlew check` therefore not green
on this base. T18's own suite passes within the full run. PR #14 review round: the cleanup
`DELETE` is now scoped to the three permanently-backed create endpoints (`POST /api/v1/contracts`,
`POST /api/v1/payments`, `POST /api/v1/settlements`) via an `endpoint IN (...)` allow-list, so write-off and
payment-void claims are retained even once expired (they have no durable backstop — deleting them would
re-open the key for reuse against a different target, ADR-017 single-use-forever / ADR-020 D3); and
`jobRuns.complete` moved inside the job's failure boundary so a completion failure finalizes via `failHard`
(with the original cause preserved via `addSuppressed`) instead of leaving a `RUNNING` row. New tests:
`IdempotencyCleanupPagingIT` (multi-page keyset drain at batch-size 2) and an
`IdempotencyCleanupIT` scoping regression asserting write-off/void claims survive while backed-create claims
are deleted.

### T27a — Minimal CI gate: `./gradlew check` (1 pt)

**Status: DONE** (merged to `main`, PR #15). **Dependencies:** none. Sprint 6 prerequisite, because
`ci.yml` previously ran only `./gradlew test`.

**Scope.** Change the CI `test` job in `.github/workflows/ci.yml` to run `./gradlew check` instead of
`./gradlew test`, so the quality gate runs on every PR and push to `main`. **Not** in scope (→ T27): a
dependency-vulnerability scan, a secret scan, and the `docker compose up` → `/actuator/health` →
`/v3/api-docs` smoke → `down` runtime gate.

**Acceptance criteria.** CI runs `./gradlew check` and the build is green on the current `main` (verify
`check` does not pull in an unconfigured plugin that fails the build; if it does, configure it minimally or
scope it to T27). No change to application code.

**Tests.** CI config change; verified by a green CI run on the PR.

**Implementation note (2026-11-04).** Single-line change in `.github/workflows/ci.yml`: the `test` job's run
step is now `./gradlew check --no-daemon` (step renamed to "Run quality gate (check = tests + verification)").
The unconfigured-plugin risk is nil for this build: `build.gradle.kts` applies only `java`,
`org.springframework.boot`, `io.spring.dependency-management` and `org.sonarqube`. For the `java` plugin
`check` depends on `test`; the `sonarqube` plugin contributes a standalone `sonar` task that is **not** wired
into `check`, so `check` never contacts the `localhost:9000` Sonar host; no Checkstyle/PMD/SpotBugs plugin is
applied. So `check` on this build resolves to `test` plus the no-op `java` verification lifecycle — no new
failing task. Verified locally that `./gradlew check --dry-run` configures the build and resolves the task
graph without error (the full `check` with `*IT` tests runs in CI, which has Docker). No application code
changed. Final green `./gradlew check` is confirmed by the PR's CI run.

### T31 — Durable single-use-forever void idempotency key (2 pts)

**Status: DONE** (merged to `main`, PR #16 + review fixes PR #17). **Dependencies:** T16 (DONE, void
path + V18), ADR-017, ADR-020 D3, T18 (the cleanup job that makes the hole reachable). Raised by the PR #11
review (Qodo finding #5).

**Problem.** A payment-_creation_ key is kept spent forever by V18's all-status `uq_payment_idempotency`. A
_void_ key has no such durable backstop — it lives only in the `IdempotencyService` claim row. Once T18
deletes an expired claim, the same key could be reused to void a **different** payment. No ledger invariant
is breached (each such void still passes every guard and posts a legitimate reversal), but it weakens the
ADR-017 single-use-forever contract for void keys.

**Scope.** Persist a durable, uniquely-constrained, endpoint-scoped void-key association with the voided
payment (new Flyway migration: a column or side table + unique index keyed on the void endpoint scope + key),
written inside the void transaction. Reject reuse of a spent void key for a different payment as
`IDEMPOTENCY_KEY_EXPIRED` / `CONFLICT` even after the claim row is gone. Owner module: `payment`.

**Acceptance criteria.** After a void and a simulated claim-row deletion (as T18 would do), reusing that key
for a different payment is rejected and writes nothing; an identical retry of the original void still
replays.

**Tests.** IT (Testcontainers): void a payment, delete the claim row directly, then a reused-key void of a
different payment is rejected; identical-key replay of the original void still returns the stored response.

**Implementation note (2026-11-04).** Additive, owned entirely by `payment` (no cross-module reach). The
void key is a **different**, endpoint-scoped key from the create key (which owns `payment.idempotency_key` /
`uq_payment_idempotency`), so a durable column on the payment row is the wrong home — chose instead a small
side table `payment_void_idempotency` keyed on `(endpoint, idempotency_key)`, mirroring the V18 precedent
(a permanent business-row backstop that holds once the `idempotency_keys` claim row is gone). New
**V22** (`V22__void_idempotency_durable_backstop.sql`): the table with `uq_payment_void_idempotency UNIQUE
(endpoint, idempotency_key)`, an FK to `payment(id)`, immutable audit columns, a `payment_id` index, the
same **data-preserving, fail-safe preflight** shape as V18 (fails the migration on a pre-existing collision,
never deletes history), and a backfill that reconstructs rows for already-VOIDED payments from surviving
claim rows. New `PaymentVoidIdempotency` entity (extends `ImmutableAuditable`; constructor guards mirror the
V22 NOT NULL/length columns) and `PaymentVoidIdempotencyRepository`. `PaymentVoidApplicationService.reverse`
now writes one durable row (step 0, inside the void transaction, ADR-020 D6) and flushes **before** any
ledger work, so a reused key that already voided a different payment trips `uq_payment_void_idempotency` and
is translated (`translateVoidKeyViolation`, matching `PaymentConflictClassifier`'s approach) to
`IdempotencyKeyExpiredException` (409 `IDEMPOTENCY_KEY_EXPIRED`) with the whole void rolled back — writing
nothing. An identical replay of the **original** void is unaffected: the payment is already VOIDED, so
`reverse` returns the current representation before the durable insert and posts no second reversal. Other
suites' `TRUNCATE … CASCADE` now lists `payment_void_idempotency`. New IT `VoidIdempotencyKeyDurabilityIT`
(2 cases: reuse-for-a-different-payment rejected + writes nothing after a simulated T18 claim-row delete;
identical replay still returns the stored response). **Verification (no Docker in this environment):**
`.\gradlew compileJava compileTestJava` → BUILD SUCCESSFUL; `.\gradlew test --tests "*Test"` (pure unit
suites) → BUILD SUCCESSFUL. The `*IT` suite — including `VoidIdempotencyKeyDurabilityIT` and the existing
`PaymentVoidIT` — was **NOT run locally** because Docker/Testcontainers is unavailable here; CI is the gate
for it (per the tech-steering no-Docker fallback). No change to the void's business behaviour beyond the new
durable backstop; closes ADR-020 "Deferred finding #5".

**PR #16 review fixes (2026-11-05).** Five review findings addressed on branch `t31-pr16-review-fixes`.
(1, HIGH) The durable void-key check now runs **before** the already-VOIDED early return in
`reverse(...)`, so a spent key reused against a _different_ payment that is already VOIDED is correctly
409 `IDEMPOTENCY_KEY_EXPIRED` instead of a false 200 no-op. `recordDurableVoidKey` now reads
`PaymentVoidIdempotencyRepository.findByEndpointAndIdempotencyKey` to distinguish "this key already maps
to **this** payment" (replay: allow, no second row/reversal) from "maps to a **different** payment"
(reject); a fresh key on an already-VOIDED payment still records its durable row, and the DB UNIQUE
constraint remains the concurrency-safe authority for the race. (2, MED) Now that every successful void
has durable protection, `POST /api/v1/payments/{id}/void` was added to the `deleteExpiredBatch`
allow-list so expired void claims are cleaned up (write-off still excluded — no backstop yet); Javadoc
updated. (3, MED) The V22 backfill joins on the **top-level** payment id extracted from the stored
response (`response_json::jsonb ->> 'id'`, the bare `PaymentResponse` serialized before the envelope
wrap) instead of a `LIKE '%id%'` substring that could match an embedded id and map one claim to several
payments; the preflight now also fails on a void claim whose response has no extractable top-level id.
(4, LOW) The backfill carries `k.created_by` into `payment_void_idempotency.created_by`, keeping the
original actor. (5, MED) A `trg_payment_void_idempotency_immutable` `BEFORE UPDATE OR DELETE` trigger on
`block_modification()` makes the append-only table reject native UPDATE/DELETE, matching the other
immutable tables (INSERT and statement-level TRUNCATE still work). New IT cases: a reused key against a
different **already-VOIDED** payment → 409 writing nothing; a fresh no-op key on a VOIDED payment stays
protected after cleanup (`VoidIdempotencyKeyDurabilityIT`); and the real cleanup job deletes an expired
void claim while the durable row keeps the key spent (`IdempotencyCleanupIT`, retention test updated to
assert void **is** now deleted and write-off retained). **Verification (no Docker in this environment):**
`.\gradlew compileJava compileTestJava` → BUILD SUCCESSFUL; `.\gradlew test --tests "*Test"` → BUILD
SUCCESSFUL (468 unit tests, 0 failures). The `*IT` suites (`VoidIdempotencyKeyDurabilityIT`,
`IdempotencyCleanupIT`, `PaymentVoidIT`) were **NOT run locally** — Docker/Testcontainers unavailable;
CI is the gate.

**Void-vs-caps DB-invariant correction (2026-11-05, branch `t31-pr16-review-fixes`).** Running the T31
`*IT` suites with Docker for the first time surfaced a latent bug predating T31: the V3
`assert_payment_allocation_component_caps()` and V16 `assert_penalty_adjustment_cap()` cap triggers
summed `payment_allocation` rows with **no** owning-payment status filter. V16's comment even claimed
parity with the POSTED-only application port "because void is T16" — false now that void (ADR-020) is
live. A VOIDED payment's immutable allocation rows still counted against the per-installment caps, so a
legitimate re-pay of an installment whose earlier payment was voided tripped the deferred trigger at
COMMIT → HTTP 500 (`VoidIdempotencyKeyDurabilityIT.aFreshVoidKeyOnAnAlreadyVoidedPaymentStaysProtectedAfterCleanup`).
Fixed by new forward-only migration **V23** (`V23__void_excludes_allocations_from_caps.sql`): both
functions `CREATE OR REPLACE`d to join `payment_allocation → payment` and count only `status = 'POSTED'`
rows, numerically identical to `sumActiveByInstallmentIds(PaymentStatus.POSTED, ...)`; the existing
DEFERRABLE triggers stay wired, V3/V16 untouched. `assert_payment_allocation_totals()` (per-payment Σ =
amount) is correct for voided payments and left alone. New `PaymentVoidIT` regression
`repayingAnInstallmentAfterVoidingItsPaymentSucceeds` (pay → void → re-pay the same installment in full);
ADR-021 records the decision and the ADR-020 cross-reference. **Verification (Docker available):**
`.\gradlew test --tests "*VoidIdempotencyKeyDurabilityIT" --tests "*PaymentVoidIT" --tests "*PenaltyAdjustment*" --tests "*PaymentApiIT"` → BUILD SUCCESSFUL (46 tests, 0 failed;
the void ITs including the former 500 repro now pass). A full-suite run then surfaced 6 **pre-existing**
IT failures unrelated to V23 (confirmed identical red on the clean branch tip with this change stashed):
`BaselineSchemaIT.postedPaymentCannotReuseAPaymentIdempotencyKey` (stale premise vs V18/ADR-020 D3
single-use-forever key) and `ConsistencyCheckIT` (4) + `DailyServicingJobIT` (1) whose bare fixtures trip
the V12 parent-side backstops / `ck_contract_terminated_coherence`. These "merged before tested" ITs were
realigned to current invariants in a **separate commit** (test-only; see below), after which full
`.\gradlew test` → 848 tests, 0 failed and `.\gradlew check` → BUILD SUCCESSFUL.

**Stale-IT realignment (2026-11-05, separate commit, test-only).** The 6 pre-existing failures were stale
TEST expectations, not production bugs; only test code/fixtures changed (V12, the consistency-check engine
and all production code untouched). `BaselineSchemaIT` now asserts the voided idempotency key stays
rejected; `ConsistencyCheckIT`'s stray-receivable fixture inserts entry + 2 lines in one CTE;
`DailyServicingJobIT.terminateForFixture` sets `write_off_recorded_by`. The three `ConsistencyCheckIT`
drift cases that used a zero-allocation payment were narrowed to **check-A-only** drift (a payment WITH a
valid EXCESS allocation but WITHOUT its KAS journal). **Finding:** consistency check B's drift condition
(Σ allocations ≠ `payment.amount`) is **unstageable end-to-end** because V12's DEFERRABLE
`assert_payment_has_allocations()` rejects any such payment at COMMIT; check B is defense-in-depth,
validated at the engine level (`ConsistencyCheckEngineTest`), not end-to-end. Logged as a follow-up below.

---

## Completed task detail — Sprint 6b (T21b, T27)

Moved out of `tasks.md` on 2026-11-19. Sprint 6b is closed; these are the scope bodies and close-out notes
for T21b and T27. A-14 (Lombok/entity conventions in `auth.domain`) was resolved by T21b and is recorded in
its note. The one-line index stays in `tasks.md` → Completed Work.

### T21b — Refresh rotation, logout, brute-force lockout, dev-key guard (3 pts)

**Status:** DONE
**Estimate:** 3 pts
Implementation note (2026-11-16, branch `t21b-refresh-lockout`): shipped as decided. `AuthService` gained
`refresh`/`logout`; `RefreshTokenRepository.revokeIfActive` is the conditional revoke (replacement inserted
first, `revoked == 0` ⇒ replay ⇒ rollback), so the concurrent-refresh IT yields exactly one winner.
`LoginAttemptRecorder` is a separate `@Service` with `REQUIRES_NEW`, and `AuthLockoutIT` asserts the
**persisted** `failed_login_attempts` after every 401 — the regression test for the rollback trap. `AppUser`
and `RefreshToken` moved to project entity style (no Lombok/setters; `isLockedAt`, `recordFailedLogin`,
`recordSuccessfulLogin`, `isUsableAt`), closing A-14. `DevKeyProductionGuardConfiguration` compares the three
keys against the compose defaults under `prod`/`production` and names property + env var, never the value.
Two `permitAll` matcher rows added; `TokenPairResponse` replaced `LoginResponse`; `ErrorCode.ACCOUNT_LOCKED`
added; `serfira.auth.lockout.max-attempts=5` / `.window=PT15M` in both properties files. No migration: every
column was already in V1. Docs updated: ADR-022 addendum D10–D13, `02_TECH_SPEC.md` §2.6, `06_FRONTEND_SPEC.md`
§1.1/§1.4/§2.1, `03_DOMAIN_MODEL.md` §2, `04_GAPS_ADDENDUM.md` §3.2–§3.4, `README.md`, `PROGRESS.md`,
`.kiro/steering/{tech,structure,product}.md`. Verification: `./gradlew test` = **116 suites / 893 tests / 0
failed** (baseline 112/866; +4 suites, +21 tests, +6 matrix cells), `./gradlew check` green. Not run: nothing —
Docker was available for the Testcontainers ITs.
Review fix (2026-11-16, same branch): the Qodo/CodeRabbit findings on PR #20 were verified against the code
and the real ones fixed. **Lockout concurrency:** `AuthService.login` lost its `@Transactional` — the lookup
now ends before Argon2, so the KDF holds no pooled connection and the independent failure/success writes
cannot starve the pool; `LoginAttemptRecorder` (`REQUIRES_NEW`) and the new `SuccessfulLoginRecorder` both
re-read the row via `AppUserRepository.findLockedById` (`PESSIMISTIC_WRITE`), so concurrent failures
increment the persisted counter exactly once and a success that loses the race answers 423 instead of
clearing the freshly committed lock. `AppUser.recordFailedLogin` clears an elapsed lock (restart at 1) and
leaves an active one untouched (no window extension). Access tokens are minted only after the success
transaction commits (`CompletedLogin`). **Dev-key guard:** compares decoded key bytes (`MessageDigest.isEqual`) so unpadded
Base64 cannot smuggle the public key past a string compare, treats malformed Base64 as non-matching, and
uses `Environment.matchesProfiles("prod", "production")` so `spring.profiles.default=prod` is guarded too.
**Refresh race test:** a test-only repository proxy barrier releases both refreshes after
`findByTokenHash`; the previous pre-call barrier could pass without the conditional update ever racing.
**Lockout race tests:** a test-only pausable `PasswordEncoder` parks a successful Argon2 verification while
concurrent failures commit the lock. Also removed the duplicated refresh hashing/lifetime into
`RefreshToken.issue/hash`, and documented the `permitAll`-does-not-skip-bearer caveat in
`06_FRONTEND_SPEC.md` and `.kiro/steering/tech.md`. No migration, no production lock added for a test.
Verification: `./gradlew check` = **117 suites / 911 tests / 0 failed**; all `*IT` suites ran (Docker
available).
Decision (2026-11-15, pre-implementation): the three open choices are settled. (a) X-14 transport =
**response body**; no cookie/CSRF/CORS work this phase because there is no browser client — revisit at
G2. (b) A locked account answers **423 `LOCKED` `ACCOUNT_LOCKED`** with the minutes remaining; the
username-existence tell is accepted, bounded by the D9 Argon2 semaphore. (c) A replayed refresh token is
**rejected only** (401); no token-family cascade revocation in T21b. All three go into the ADR-022
addendum at implementation.

**Goal:** Complete story **F2** with the stateful half of auth — rotating refresh tokens, logout,
brute-force lockout — plus a production guard against the dev-only key fallbacks.

**Scope.**

- **`POST /api/v1/auth/refresh`** — rotate: validate the presented refresh token against its stored hash and
  expiry, issue a new access + new refresh token, revoke the old row (`revoked_at`) and point it at the new
  one via `replaced_by_id`. A reused/revoked refresh token is rejected (detect replay). Never stateless-only
  for refresh (Addendum §3.3). Rotation is **race-safe**: the old row is revoked by a conditional update
  (`… WHERE revoked_at IS NULL`) that exactly one concurrent caller can win, so two parallel refreshes cannot
  both mint. Refresh also **rejects an `app_user` that is inactive or carries a non-HTTP role**, so a
  deactivated account cannot keep minting access tokens. The new refresh token's 7-day expiry is **sliding**
  (re-issued from each rotation); there is no absolute session cap — record that in the ADR.
- **`POST /api/v1/auth/logout`** — revoke the presented refresh token (`revoked_at`); idempotent.
- **Brute-force lockout (Addendum §3.1/§3.3).** On failed login increment `failed_login_attempts`; past a
  threshold set `locked_until`; a locked account is rejected until the window passes; a success resets the
  counter. Threshold and window are **configuration** (`serfira.auth.lockout.max-attempts`, default 5;
  `serfira.auth.lockout.window`, default `PT15M`), not constants, and are mirrored into the test properties so
  the ITs need not burn real Argon2 verifications.
- **Dev-key production guard (CR-16).** Fail fast at startup in a production profile if the JWT/PII keys are
  still the documented `docker-compose` dev defaults, so a deployment cannot silently run on demo keys.
- **Docs fallout.** Update `.kiro/steering/tech.md`: the public-path list (§Security) must say login **and**
  refresh/logout are public, and its §Tech Stack auth line still calls rotation/logout/lockout "T21b". Update
  `.kiro/steering/structure.md` and `tech.md` where they cite ambiguity **A-14**, which this task closes.
- **Refresh-token transport (X-14).** Settle whether the refresh token is returned in the response body
  (current T21a behaviour, ADR-022 D3/D5) or set as an `HttpOnly` cookie (Tech Spec §2.6,
  `06_FRONTEND_SPEC.md` §2.1). Whichever wins, update the document that loses and the login response shape.
- **Role matrix.** Add `/auth/refresh` and `/auth/logout` rows (§3.4). Both are `permitAll` — they are
  called when the access token is dead, so they cannot require one (the same by-design exception as login,
  ADR-022 D7) — so their matrix cells assert "not 401/403" for all three roles rather than a 403 cell.

**Business Rules.** Refresh tokens are stored hashed and single-use via rotation; a revoked or
already-rotated token never mints a new access token (replay detection). Logout must not error on an
already-revoked token. Lockout state lives on `app_user` (`failed_login_attempts`, `locked_until`). The
dev-key guard must not weaken dev ergonomics — it only trips under the production profile.

**Implementation Notes.** Owner: same `auth` module as T21a. Source: Addendum §3.2–3.3, §18, ADR-005,
CR-10/CR-16. The lockout threshold/window is settled at 5 attempts / 15 min, configurable via
`serfira.auth.lockout.*`, and recorded in the ADR-022 addendum at implementation (Decision 2026-11-15). The
Argon2 capacity guard (ADR-022 D9) already exists; lockout operates on top of it.

**Failure bookkeeping must survive the throw.** `AuthService.login` is `@Transactional` and throws on
failure, so incrementing `failed_login_attempts` inside it would be rolled back and lockout would never
fire — with a green test suite unless the IT asserts the persisted counter. Record the failure in a
`@Transactional(propagation = REQUIRES_NEW)` component, or as one atomic statement
(`… SET failed_login_attempts = failed_login_attempts + 1, locked_until = CASE WHEN … THEN … END … WHERE id = ?`)
that also avoids a lost update under concurrent attempts. Same class of defect in rotation: guard it with the
conditional revoke above and treat "0 rows affected" as replay.

**Dependencies:** T21a (app_user, refresh_token, login all exist). **Closes A-14** in passing: `AppUser` is
already edited for lockout behaviour, so it moves to project entity style at the same time (protected no-arg
constructor, validating constructor, intention-revealing methods, no Lombok) instead of leaving steering and
code in contradiction. No migration is needed — `app_user.locked_until`/`failed_login_attempts` and
`refresh_token.revoked_at`/`replaced_by_id` are all in V1.

**Acceptance Criteria.** A refresh rotates both tokens, revokes the old, and links `replaced_by_id`; a
reused old refresh token is rejected. Logout revokes and is idempotent. N failed logins lock the account for
the window; a correct login afterward is still blocked until it expires; a success resets the counter. A
failed login leaves its increment **persisted** despite the thrown 401 (asserted in the DB, not only the
status code). Under the production profile, startup fails if the keys are the dev defaults.
`/auth/refresh` and `/auth/logout` have matrix rows whose cells assert all three roles are not denied. The
ADR-022 addendum records D10 (transport), D11 (rotation/replay), D12 (lockout), D13 (dev-key guard), and
`tasks.md` marks X-14 resolved.

**Tests.** IT (Testcontainers): rotation happy path + old-token-rejected replay + expired refresh +
refresh-for-inactive-user + two concurrent refreshes yield exactly one winner; logout idempotency (repeat and
unknown token both 200) + refresh-after-logout rejected; lockout after N failures, correct password still
blocked, reset on success, and the **persisted counter** asserted (`FixedClock` drives `locked_until`); an
`ApplicationContextRunner` test that the production profile with dev keys fails to start while demo/default
does not. Extend `EndpointRoleMatrixIT` for the two endpoints.

**Risks.** The production-profile startup guard can break CI or local `prod`-profile runs if the test
harness supplies dev keys; scope the guard to a real production profile and keep the test/dev profiles out
of its reach.

### T27 — CI & runtime gates: dependency + secret scan + Compose smoke (2 pts)

**Status:** DONE
**Estimate:** 2 pts
Close-out note (2026-11-19): implementation, PR #21 review remediation and local verification are complete.
The keyed `dependencyCheckAnalyze` run and the pipeline execution of the `secrets`/`compose-smoke` steps
remain unproven in CI because the GitHub account is locked for billing, so the CI acceptance run is deferred
at the user's direction rather than treated as passed. Billing is an operational concern; no code or workflow
work is outstanding.
Implementation note (2026-11-17, branch `t27-ci-runtime-gates`): shipped as the sprint's last gate, no
application code touched and no scanner wired into `check` (T27a parity). Decisions fixed here:
(1) **Dependency-Check** — Gradle plugin `org.owasp.dependencycheck` 13.0.0 with `failBuildOnCVSS = 7.0`
(High + Critical fail, Medium and below report only), HTML report, `suppressionFile =
dependency-check-suppressions.xml`, `failBuildOnUnusedSuppressionRule = true` so a stale suppression reds
the scan, and `analyzers.ossIndex.warnOnlyOnRemoteErrors = true` because the NVD feed — not OSS Index — is
the gate's source of truth. A valid `NVD_API_KEY` is **required**, not optional: the NVD API rejects
unkeyed/empty requests, so a blank key fails the scan instead of bypassing it. The suppression file is empty
today with a written policy header: every future entry needs a reason.
(2) **Gradle 9 workaround** — the plugin resolves configurations during execution, which Gradle 9.7.1
rejects under parallel scheduling (upstream issue #462), so the scan runs as its own invocation with
`--no-parallel --no-configuration-cache`.
(3) **gitleaks** — `.gitleaks.toml` extends the default rules and allowlists only four provably-not-secret
shapes, each scoped by path *and* exact value (build outputs `backend/(build|bin)`; the public test-only
PII/JWT keys in `src/test/resources/application.properties`; one T31 test `Idempotency-Key` literal; the
committed dev-only compose keys plus the `DevKeyProductionGuard` that names them, ADR-022 D13). Both the
base64 and decoded forms are listed because gitleaks reports decoded secrets from history.
(4) **CI** — jobs stay independent (`test`, `secrets`, `dependencies`, `compose-smoke`, plus a
`dependencies-fork-notice` job that only runs on fork PRs); the old build-only `docker` job is replaced by
the real smoke. The `secrets` job downloads a **pinned** gitleaks binary (8.30.1, SHA-256 verified) and runs
`gitleaks git .` over the full history and `gitleaks dir .` over the working tree, both from the repository
root with `--config .gitleaks.toml`. The earlier `gitleaks-action@v3` step was removed: it narrows
`push`/`pull_request` to an event-specific commit range, so it could not serve as the full-history gate. The
`dependencies` job fails loudly with an `::error::` guard step when the `NVD_API_KEY` secret is absent,
caches `~/.gradle/dependency-check-data` under a per-run key
(`dependency-check-data-${os}-v1-${run_id}`, restoring with the versioned `…-v1-` prefix), and uploads the
report on failure. Fork-originated PRs receive no repository secrets, so `dependencies` is skipped there and
`dependencies-fork-notice` reports the skip explicitly; `pull_request_target` is not used, so untrusted
fork Gradle code never sees a secret. `compose-smoke` parses the served document with `jq` and asserts
`paths` contains `/api/v1/payments` and `/api/v1/contracts` (the same surface `OpenApiSmokeIT` pins
in-process), then tears the stack down unconditionally.

Verification (original implementation, 2026-11-17): `gitleaks dir .` and `gitleaks git .` (99 commits) both
clean at exit 0, and a scratch `api_token = "…"` probe returned exit 1 and disappeared after removal
(positive control; an AWS *example* key does not fire, it is allowlisted by gitleaks itself). A second probe
planted the same shape inside `docker-compose.yml` — a file whose dev keys *are* allowlisted — and still
failed the scan at exit 1, so the allowlist is value-scoped rather than file-wide. ODC task wiring verified
by `--dry-run` plus two env probes: a dummy `NVD_API_KEY` reaches the scanner core (`Invalid API Key:
dummy-*****`) while no key fails with `NvdApiException: Invalid API Key, length of 0` — i.e. the key
genuinely gates the scan. Compose smoke rehearsed locally end-to-end: `up --build` →
`/actuator/health` **UP after 4 attempts** → `/v3/api-docs` served (**51,851 bytes, 17 paths**) → `down -v`
removed containers, volume and network. `./gradlew check` = **117 suites / 911 tests / 0 failed** (test
sources unchanged, so the T21b result set was reused). A full ODC scan with a real NVD key — and therefore
the CI-green acceptance criterion — cannot be produced locally and is left to the pipeline run.

Review remediation (2026-11-19, PR #21 findings). The gitleaks half and the smoke assertion were reworked and
re-verified; the other findings were review/doc corrections.
- **Full-history gate is now explicit.** The gitleaks action was replaced by a pinned CLI (8.30.1, linux_x64
  archive SHA-256 `551f6fc8…470eb`, verified against the published asset). `gitleaks git .` scans the
  history and `gitleaks dir .` the working tree, both from the repository root with `--config .gitleaks.toml`
  and `--redact`; `.gitleaks.toml` is unchanged. Local re-run: `git` = 100 non-merge commits scanned, clean,
  exit 0; `dir` = clean, exit 0. Positive controls: a scratch file containing
  `api_token = "…"` made `dir` exit 1 and the scan returned to exit 0 after removal. History control: in a
  scratch clone a probe secret was committed and then deleted in a later commit (HEAD tree and working tree
  clean, `dir` exit 0), and `gitleaks git .` still failed at exit 1 with the finding attributed to the
  historical commit — the property the event-range action could not provide.
- **Smoke test asserts the contract.** `jq` now requires a `paths` object and the presence of
  `/api/v1/payments` and `/api/v1/contracts`. Rehearsed against a real Compose stack: health UP,
  `/v3/api-docs` parsed with **17 paths**, both assertions exit 0; a deliberate probe for a nonexistent path
  exited 1 with the `::error::` annotation, and fixtures with a missing path or a missing `paths` object also
  exit 1. `docker compose down -v` then removed both containers, the volume and the network. Teardown stays
  `if: always()`.
- **NVD key documented as required** (comment only, no scanner change or version bump). Re-verified keyless
  behaviour: `dependencyCheckAnalyze` without the variable fails in 19s with `UpdateException: Error updating
  the NVD Data`, caused by `NvdApiException: Invalid API Key, length of 0`, build exit 1 — the gate cannot
  pass silently. CI reads the `NVD_API_KEY` secret; local scans must export it. A full keyed scan remains
  CI-only.
- **Caches and forks.** The NVD cache key is now per-run
  (`dependency-check-data-${os}-v1-${run_id}`) with the versioned `…-v1-` restore prefix; fork PRs skip the
  keyed job and get an explicit `dependencies-fork-notice` warning/summary instead, and
  `pull_request_target` is not used.
- **Validation:** `actionlint 1.7.12` exit 0, YAML parsed with all five jobs present; `./gradlew check
  --rerun-tasks` = **117 suites / 911 tests / 0 failed** (4m15s).
- **Merge diffs covered.** The history step now passes
  `--log-opts="--all --full-history --diff-filter=tuxdb -m"`, restating gitleaks' built-in full-history
  options and adding per-parent merge diffs. Proven with an "evil merge" probe whose secret is in neither
  parent and was later deleted: default options scanned 3 commits with no leak, while the new command scanned
  4 commits and exited 1. On this repository it scans 136 commits (vs 100) and stays clean at exit 0.
- **Key documented precisely.** README now separates CI policy (the `dependencies` job rejects a missing
  secret before Gradle runs; presence is not validity) from scanner behaviour (Dependency-Check only fails on
  a missing/invalid key when it requests NVD data, and skips the update while `nvd.api.check.validforhours`,
  default 4 h, has not elapsed), so a clean local run is not presented as proof of a valid key.
Outstanding CI-only acceptance: the keyed `dependencyCheckAnalyze` run and the pipeline execution of the new
`secrets`/`compose-smoke` steps. They are deferred while the GitHub account billing lock is resolved; T27 is
closed at the user's direction with that limitation recorded.

**Goal:** Harden CI beyond `check` with two self-contained scanners and a runtime smoke test, so a
high-severity CVE, a committed secret, or an app that fails to boot cannot land on `main`.

**Scope.**

- **Dependency-vulnerability scan — OWASP Dependency-Check.** Add the Gradle `dependencyCheck` task (or the
  GitHub Action) as a CI step. Fail the job on a high-severity finding; allow a reviewed suppression file for
  accepted/false-positive CVEs.
- **Secret scan — gitleaks.** Add gitleaks as a CI step over the repo/diff; guards the "never commit secrets"
  rule (steering `60-security`) given the repo deliberately carries dev-only keys in compose that must not
  spread to real secrets.
- **Compose runtime smoke (CR-15).** A CI job: `docker compose up --build -d`, wait for `/actuator/health`
  to go UP, assert `/v3/api-docs` (OpenAPI) responds, then `docker compose down`. Keep it a separate
  job/step so a smoke failure is distinguishable from a unit/IT failure.
- **Sonar is explicitly out of scope** (2026-11-07 decision): it needs a running SonarQube/SonarCloud
  server, an external dependency not worth standing up for this portfolio repo; the `org.sonarqube` plugin
  stays local/manual and is never wired into `check` or CI (consistent with T27a).

**Business Rules.** Scanners must be self-contained (no external server) so CI stays hermetic and non-flaky.
Each tool choice is justified in the task per `35-coding-standards`. No application code changes; this is
CI/build config only. A suppression file for Dependency-Check is allowed but every suppression needs a
one-line reason.

**Implementation Notes.** Owner: CI config (`.github/workflows/ci.yml`) + Gradle. Source: CR-15, steering
`35-coding-standards`. Justification per tool: OWASP Dependency-Check is the de-facto OSS CVE scanner for
JVM dependency trees and runs self-contained; gitleaks is a single self-contained binary with a strong
default rule set. The open decision is the Dependency-Check severity threshold + suppression policy, fixed
at implementation (tracked as the open ambiguity in Planning Notes, non-blocking).

**Dependencies:** T27a (the `./gradlew check` gate, DONE).

**Acceptance Criteria.** CI runs OWASP Dependency-Check and gitleaks and fails on a real high-severity CVE
or a committed secret (prove each with a scratch positive, then remove it). The Compose smoke job brings the
app up, sees `/actuator/health` UP and `/v3/api-docs` served, and tears down cleanly. A green run on a clean
`main`.

**Tests.** CI config change, verified by a green CI run on the PR plus a deliberate-failure spot check for
each scanner (shown in the PR description, not committed). No unit/IT changes.

**Risks.** OWASP Dependency-Check downloads the NVD feed and can be slow or flaky on a cold cache, and a new
high-severity CVE in a transitive dep can red the build without a code change; the reviewed suppression file
plus NVD caching are the mitigations.
