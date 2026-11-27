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

## Completed task detail — Sprint 7 (Frontend Core & Demo: T35, T32, T33, T34)

Moved out of `tasks.md` on 2026-11-26 at Sprint 8 planning. Sprint 7 is closed (T35 PR #22, T32 PR #23,
T33 PR #24, T34 PR #25); these are its goal/exit header, scope bodies and close-out notes. The one-line index
stays in `tasks.md` → Completed Work.

**Goal (05_SPRINT_PLAN.md Sprint 7):** the main operator flow runs in a browser — login → create contract
→ activate → record payment → read statement — and the demo is repeatable.

**Scope (13 pts):**

- T35 — Browser-client CORS boundary + ADR-023 (1 pt) — **DONE**
- T32 — G2: app shell, login, contract list/detail/create/activate (5 pts) — **DONE**
- T33 — G3: payment form, installment table, statement, credit tab (5 pts) — **DONE**
- T34 — G5: demo script, seed data, README close-out (2 pts) — **DONE**

**Exit:** login works against the real backend; a DRAFT contract can be created, activated, paid and read
back from the browser; the statement renders ledger-literal rows; the demo script runs end-to-end from a
clean database; a frontend CI job (install, lint, typecheck, unit, build) is green; authorization stays
server-side and the UI only hides what the role cannot do.

**Dependency order:** T35 → T32 → T33 → T34.

> **Re-planned 2026-11-19.** Sprint 7 keeps the sprint plan's G2/G3/G5 and adds T35, the browser-client
> prerequisite that the sprint plan left uncosted (CORS was deferred to G2). **T28 (key rotation) moves to
> Sprint 8 as T28a/T28b:** it has no frontend dependency, and carrying it here would put the sprint at
> 17 pts. The payments-history tab and the settlement/write-off dialogs move to T39 (Sprint 8b) because
> the payments list has no backend read model yet.

Business rules live in `06_FRONTEND_SPEC.md` §1–§3, `04_GAPS_ADDENDUM.md` §3.4 and the cited ADRs; these
tasks point to them and do not restate them.

### T35 — Browser-client CORS boundary and ADR-023 (1 pt)

Status: DONE
Estimate: 1 pt
Implementation note (2026-11-19, branch `t35-cors-adr-023`): CORS lives in
`ResourceServerSecurityConfiguration` as a `CorsConfigurationSource` bean wired with `.cors(...)` as the
first DSL call, so an untokened preflight is answered by the `CorsFilter` instead of falling through to
`denyAll()`. `AllowedOrigins` validates the comma-separated property once at startup and rejects
wildcards, blank entries, paths, queries, fragments, user info, out-of-range and default ports, and
non-canonical spelling (including non-canonical IPv6); the message names the offending entry.
`allowCredentials=false`, methods `GET,POST`, allowed headers
`Authorization`/`Content-Type`/`Idempotency-Key`/`X-Request-Id`, exposed `X-Request-Id`,
max age 1800 s. Main properties ship an explicit empty default; Compose and the test profile set
`http://localhost:3000`. ADR-023 records the boundary (direct calls, in-memory tokens, roles as UX only,
no `/me`, unverified decode) and the Next.js 16.4.6 pin with its OSV check. One behaviour the review
pinned: a non-preflight request from a disallowed origin is rejected by Spring's `CorsFilter` with 403 and
the fixed body `Invalid CORS request` *before* authentication, not with the endpoint's 401. Tests: new
`AllowedOriginsTest` (8), `CorsIT` (6), `CorsDeniedByDefaultIT` (1). Verified with
`./gradlew test --tests "*Cors*" --tests "*AllowedOriginsTest"` and then the full `./gradlew test` =
**120 suites / 926 tests / 0 failed**; `EndpointRoleMatrixIT` unchanged and green.

Review remediation (2026-11-21, PR #22): Compose now uses the unset-only default
`${SERFIRA_SECURITY_CORS_ALLOWED_ORIGINS-http://localhost:3000}`, so an explicitly empty value is
preserved and denies every browser origin instead of silently falling back to localhost (ADR-023 D4).
Verified with `docker compose config`: unset renders `http://localhost:3000`; an empty env-file value
renders `""` (the old `:-` form rendered localhost for the same input). `AllowedOrigins` now re-serializes
IPv6 literals the way a browser does and rejects non-canonical spellings (`[0:0:0:0:0:0:0:1]`, uppercase
or leading-zero hex, a `::` run that is not the first longest, embedded dotted IPv4, zone ids) without DNS;
`[::1]` and `[::ffff:7f00:1]` stay accepted. `AllowedOriginsTest` grew 8 -> 11. The review suggestion to
put a localhost default in the main properties was not applied: ADR-023 D4 and this task's risk note
require the empty default, Compose and the test profile carry the dev origin, and the README documents the
`bootRun` variable. Verified: focused CORS suite 18 tests / 0 failed, full `./gradlew test` = **120 suites
/ 929 tests / 0 failed**, `./gradlew check` green.

**Goal:** Let the browser application on its own origin call the API without opening the API to arbitrary
origins, and record the browser-client security boundary before frontend code exists.

**Scope.**

- CORS in the security chain: allowed origins from `serfira.security.cors.allowed-origins`
  (comma-separated, default empty = deny), methods `GET,POST`, headers
  `Authorization, Content-Type, Idempotency-Key, X-Request-Id`, exposed header `X-Request-Id`,
  `allowCredentials=false`, and a bounded preflight max age.
- Dev, test and Compose values allow only `http://localhost:3000`; a production profile without the
  property allows nothing.
- **ADR-023** records the browser-client boundary: bearer access token + refresh token in memory
  (06_FRONTEND_SPEC §1.1/§1.4), no cookies so no CSRF and no credentialed CORS; roles read from the JWT
  `roles` claim with no `/me` endpoint; security headers owned by Next.js plus Spring Security defaults,
  not a new backend dependency; rate limiting stays deferred. It also records the pinned frontend stack
  version and the CVE check behind it (Next.js 16.4.6; OSV returns no advisory for that version on
  2026-11-19).
- Docs: replace the CORS-versus-proxy open line in `06_FRONTEND_SPEC.md` §4 with the decided answer;
  update `02_TECH_SPEC.md` §2.6 if it names the boundary, `README.md` (environment) and
  `.kiro/steering/tech.md`.

**Business Rules.** ADR-015 (default deny), ADR-022 D7 (the auth endpoints are `permitAll`, but a bearer
token is still validated), 06_FRONTEND_SPEC §1.4 (the three public auth paths never carry an access
token). CORS is a browser control, never an authorization mechanism: the role matrix stays the
enforcement.

**Implementation Notes.** CORS belongs in `ResourceServerSecurityConfiguration` through a
`CorsConfigurationSource` bean; do not weaken the security chain ad hoc. The auth paths are not exempt
from CORS. `X-Request-Id` already exists (T20) — expose it so the browser can log it. Verify the Boot 4 /
Spring Security 7 CORS API with Context7 before coding. `allowCredentials=false` is what makes an empty
default safe; a wildcard origin plus credentials must be impossible by configuration.

**Dependencies:** none (T7/ADR-015 and T21b/ADR-022 are DONE).

**Acceptance Criteria.**

- A preflight from an allowed origin returns 200 with the expected `Access-Control-Allow-*` headers.
- A disallowed origin receives no `Access-Control-Allow-Origin`; the browser blocks the call.
- Credentials are not allowed; no configuration combination produces `*` with credentials.
- The property absent means no origin is allowed; dev/test/Compose allow only `http://localhost:3000`.
- ADR-023 exists and the documents listed above match the implementation.

**Tests.** Narrowest first: MockMvc tests for the allowed preflight, the disallowed origin, an actual
POST from an allowed origin, the `/auth/login` preflight, and the empty default. Then
`EndpointRoleMatrixIT` and `./gradlew check` stay green (CORS must not change authorization).

**Risks.** A permissive default would silently widen the browser boundary; keep it empty and put the dev
origin only in the dev/test profiles. Spring Security 7's CORS support is version-sensitive — confirm the
API rather than copying an older recipe.

### T32 — G2: app shell, login and contract lifecycle (5 pts)

Status: DONE
Estimate: 5 pts
Implementation note (2026-11-20, branch `t32-nextjs-shell`, stacked on `t35-cors-adr-023`): scaffolded
`frontend/` with `create-next-app@16.4.6` (TypeScript, Tailwind v4, ESLint, App Router, no `src/`, `@/*`)
and shadcn/ui (`--base radix -p nova`, the Tailwind-v4/`cn` model), adding TanStack Query v5, React Hook
Form + Zod and Vitest + React Testing Library (jsdom). Node is pinned to **24.16.0** in `frontend/.nvmrc`
(CI reads it) with `engines.node >= 22.0.0` because the shadcn CLI's `cn` package needs Node 22+; the
Next.js floor (20.9.0) is still met. The OSV re-check was repeated: `next@latest` = 16.4.6, zero advisories
(any severity) for 16.4.6 and `latest` — recorded in ADR-023 D9. `tsconfig` target is ES2020 (the decimal
helpers use BigInt literals); `typecheck` = `next typegen && tsc --noEmit`.

`lib/api.ts` is the only envelope-aware layer: it unwraps `{data, error}` once, never attaches a bearer to
`PUBLIC_AUTH_PATHS`, rotates the pair **once** on a 401 and replays the request through a private
`alreadyRetried` flag (so a second 401 cannot loop), and shares one in-flight refresh between concurrent
401s because rotation is single-use; a failed refresh clears the session and hard-redirects to `/login`.
`lib/session.ts` keeps both tokens in module memory only (no storage, cookie, URL or log) and retains the
typed username for the header because `sub` is an `app_user` UUID. `lib/auth.ts` owns login/logout
(logout revokes best-effort and always forgets locally); `lib/navigation.ts` isolates the one hard
navigation so the session logic stays testable.

Screens: `/login` (401/423 envelope messages), the `(dashboard)` shell (client guard, sidebar, header,
logout), `/contracts` (status filter, `q` search, pagination, `sort=created_at,desc`, DRAFT `outstanding`
as "–"), `/contracts/new` (Zod incl. NIK 16 digits, phone 9–15, money exactly scale 2, rate exactly 2
decimal places; `planned_start_date`; one `Idempotency-Key` per mount), `/contracts/[id]` (header, badge,
activation dialog with no date input, Jadwal/Kredit/Ledger tabs), and a minimal
`/contracts/[id]/statement` so the Ledger link is not dead (date-range/ref-type filters and payment links
remain T33). Write actions (`Buat Kontrak`, `Aktifkan`) are hidden for non-`ADMIN_OPERASIONAL`; that is UX
only and the backend matrix still answers 403.

Money never touches a float: `lib/decimal.ts` does validation, comparison, percent→fraction and the two
derived schedule sums with BigInt string arithmetic; `InstallmentTable` derives `recognized_total` and
`resolved_amount` exactly as `InstallmentBalance` defines them and documents that. Display formatting is
isolated in `lib/format.ts` (`id-ID`, `Asia/Jakarta`, missing value = "–").

CI: new independent `frontend` job (`npm ci` → lint → typecheck → test → build, Node from `.nvmrc`). The
gitleaks generic-key rule gained one path-only allowlist for `frontend/(node_modules|.next|coverage|out)`,
mirroring T27's build-output precedent; tracked frontend files are still scanned. `npm audit` reports 0
high/critical and 4 moderate, all through the shadcn CLI's `ts-morph`/`minimatch` chain (dev-only); JS
dependency scanning stays a deferred follow-up as the task records.

Verification: `npm ci`, `npm run lint` (0 problems), `npm run typecheck`, `npm test` (**79 tests / 12
files**, 2026-11-20) and `npm run build` (8 routes) pass; backend `./gradlew test --rerun-tasks` remains
**120 suites / 926 tests / 0 failed**. The branch was based on `t35-cors-adr-023` because T35 was its
dependency; T35 has since merged as PR #22 (`cff44de`), and `main` is merged back into this branch to clear
PR #23's conflicts.

Review remediation note (2026-11-25, same branch, PR #23): the nine Qodo findings were verified against the
frontend, the backend matcher table and the specs; all nine were valid and are fixed here.
(1) **Stale refresh overwrote a newer session**: a session **generation** in `lib/session.ts` is captured by
every request and refresh, so a rotation or late response from a replaced session is rejected with
`SESSION_CHANGED` instead of overwriting the newer operator; concurrent 401s in one session still share one
refresh and a stale flight cannot block a newer login.
(2) **MANAJEMEN saw the Credit tab** and (3) **the statement navigation**: both are now rendered and queried
only for `ADMIN_OPERASIONAL`/`FINANCE` (tab, link and query `enabled`), with an access-denied page for a
direct URL; contract detail stays available to `MANAJEMEN`.
(4) **Money formatting lost precision** and (5) **money input exceeded `NUMERIC(19,2)`**: money and rates are
**exact decimal strings** on the wire (ADR-024: `BigDecimalStringModule` + `OpenApiConfiguration` in the
backend, `Money = string` and BigInt formatting in the frontend), and operator input is bounded to
`NUMERIC(19,2)` (17 integer digits, scale 2) before submit.
(6) **A production build silently used localhost**: it now fails without an explicit
`NEXT_PUBLIC_API_BASE_URL`; CI builds against `https://ci-build.invalid`.
(7) **Logout left the Query cache**: `app/providers.tsx` cancels outstanding queries and clears the cache on
login/logout, and logout clears tokens/snapshot/cache synchronously before the `keepalive` revocation.
(8) **Phone validation disagreed with the backend**: input mirrors `PiiHasher.normalizePhone` (strip
non-digits, `0`→`62`) before the 9–15 digit check.
(9) **`crypto.randomUUID()` failed outside a secure context**: the `Idempotency-Key` uses it when available
and otherwise an RFC 4122 v4 value from `getRandomValues()`, never `Math.random()`.
Verification: `npm ci`, `npm run lint`, `npm run typecheck`, `npm test` (**17 files / 108 tests**, 2026-11-25)
and `npm run build` pass; the build exits 1 with the documented message when the env var is absent. Backend
`./gradlew check` = **121 suites / 932 tests / 0 failed** (2026-11-25, after `main` was merged in; 929 before
the merge). ADR-024, `02_TECH_SPEC.md` §2.2, `06_FRONTEND_SPEC.md` §3.1/§4 and the frontend README document
the wire format, session and build rules.

**Goal:** Stand up the Next.js application and deliver the first complete operator journey: sign in, see
the contract book, create a DRAFT contract, open its detail and activate it.

**Scope.**

- `frontend/` scaffold per 06_FRONTEND_SPEC §1.2: Next.js 16 App Router (`16.4.6`, React 19) + TypeScript,
  Tailwind + shadcn/ui, TanStack Query v5, React Hook Form + Zod, npm scripts and a pinned Node version
  (≥ 20.9.0, which Next.js 16 requires).
- `lib/api.ts`: `apiFetch` unwraps `{data, error}` exactly once (FE §1.3); `PUBLIC_AUTH_PATHS` never carry
  an access token.
- `lib/auth.ts`: in-memory access + refresh tokens, one in-flight refresh promise (FE §1.4); a 401
  refreshes and replays once; a failed refresh clears both tokens and redirects to `/login`.
- `/login` (FE §2.1): username/password, 401 message, 423 `ACCOUNT_LOCKED` message from the envelope.
- `(dashboard)` layout: sidebar/navbar/auth guard; role decoded from the JWT `roles` claim (ADR-023);
  write actions hidden or disabled for non-`ADMIN_OPERASIONAL` — UX only.
- `/contracts` (FE §2.2): table, status filter, `q` search, pagination, `created_at DESC` default,
  `outstanding` shown as "–" for DRAFT.
- `/contracts/new` (FE §2.3): every field + Zod validation; "Tanggal Mulai" maps to `planned_start_date`;
  `Idempotency-Key` generated once on mount and reused across retries; redirect to the detail page.
- `/contracts/[id]` (FE §2.4): header, status badge, activation action (no date input) and the tabs whose
  backend contracts already exist — Jadwal, Kredit, Ledger/statement link. The Pembayaran tab and the
  settlement/write-off actions are T39.
- Shared components: `MoneyDisplay` (id-ID, no decimals), `DateDisplay`, `ErrorAlert`,
  `LoadingSpinner`, `ContractStatusBadge`, and `InstallmentTable` (FE §2.5, reused by T33).
- CI: a `frontend` job in `.github/workflows/ci.yml` — `npm ci`, lint, typecheck, unit test, build —
  independent of the Gradle jobs; add `frontend/node_modules`, `.next` and coverage to `.gitignore`, and
  confirm `gitleaks dir .` stays clean (extend the path allowlist only for those build outputs, following
  T27's precedent).

**Business Rules.** 06_FRONTEND_SPEC §1–§3; ADR-006 (activation uses `planned_start_date`, is idempotent
by state machine and takes no date input); ADR-007 (mandatory `Idempotency-Key`; a replay returns the
stored DRAFT snapshot); ADR-015 (server-side authorization is the enforcement); ADR-022 D7 (the auth
endpoints never carry an access token). PII is already masked by the backend — do not reconstruct or
display raw NIK/phone.

**Implementation Notes.** The wire format is snake_case; keep a thin `types/` mapping instead of
sprinkling field names. No BFF/proxy: CORS is decided in T35 and the browser calls the API directly
(FE §4). Next.js 16.4.6 is pinned by the spec with an exact version — re-check OSV before scaffolding and
record the result in ADR-023; never take a different major silently, and if a new HIGH/CRITICAL advisory
appears, move to the patched release (or the documented backport line) and say so. Node is pinned to a
version ≥ 20.9.0.
Money arrives from `BigDecimal`; parse it with a decimal-safe helper, never `parseFloat` for values that
are re-submitted. Keep `frontend/` out of the Docker image and Compose; `npm run dev` is the demo path
(T34). The frontend CI job is part of the deliverable, not an afterthought.

**Dependencies:** T35; T7, T21a and T21b (DONE).

**Acceptance Criteria.**

- `npm ci && npm run lint && npm run typecheck && npm run test && npm run build` passes locally and in CI.
- Login keeps both tokens in memory only; a reload forces a new login (documented behaviour).
- An expired access token triggers exactly one refresh; concurrent 401s share that refresh.
- The contract list, filters, search and pagination match the API; DRAFT shows "–" for outstanding.
- Create → detail → activate works end-to-end; a retried create replays instead of duplicating.
- A non-`ADMIN_OPERASIONAL` user sees read-only screens and cannot trigger a write from the UI; the
  backend still answers 403 if the call is forced.
- No token or PII reaches `localStorage`, `sessionStorage`, console logs or URLs.

**Tests.** Vitest + React Testing Library for the pure logic and the risky paths: `apiFetch`
envelope/error mapping and public-auth header suppression, `auth` single-flight refresh and
refresh-failure logout, the create-form Zod schemas, role gating, and money/date formatting. Component
tests for list loading/empty/error states. E2E belongs to T34. Backend suites unchanged;
`./gradlew check` stays green.

**Risks.** This is the first non-JVM code in the repo, so the Node pin and the CI job are part of the
scope. `roles` in the JWT is UX data only — never authorization. Refresh-token rotation makes parallel
refreshes destructive, so the single-flight promise is load-bearing. JS dependency scanning is not
covered by OWASP Dependency-Check; `npm audit` is a candidate follow-up, not part of this task.

### T33 — G3: payment, installments, statement and credit (5 pts)

Status: DONE
Estimate: 5 pts
Decision (2026-11-25): the scope below and FE §2.6 list a "Tanggal Bayar" (paid-at, max now) and a
"Catatan" (note) input, but `PaymentRequest` accepts only `contract_id`, `amount`, `channel`: `paid_at` is
always the server clock (TS §2.0) and `payment` has no note column. Decided with the owner: **drop both
inputs, no backend change**; the success view shows the server's `paid_at`. FE §2.6 is updated. FE §2.7's
"Kontrak Ref" link has no target route yet (no `GET /payments/{id}`), so the statement shows a short `ref_id`
and the link is T39 scope.
Implementation note (2026-11-25, branch `t33-payment-statement-credit`; plan
`docs/superpowers/plans/2026-11-25-t33-payment-statement-credit.md`): frontend only. New `types/payment.ts`,
`lib/schemas/payment.ts` (positive scale-2 amount within `NUMERIC(19,2)`, four channels) and
`hooks/usePayment.ts` (`useCreatePayment` posts with the caller's `Idempotency-Key` and invalidates the
`["contract", id]` prefix plus `["contracts"]`). `RecordPaymentForm` keeps one key per mount and reuses it on
every retry; a spent key (`CONFLICT`/`IDEMPOTENCY_KEY_EXPIRED`) disables submit and asks the operator to check
the statement and choose "Mulai form baru", which remounts the form with a new key — the form never rotates a
key on its own because the first attempt may have committed. `PaymentResult` renders the server's allocation
lines in API order with period labels (`installmentLabels.ts`), the server `paid_at`, and an excess-credit
warning. `RecordPaymentView` (route `/contracts/[id]/payments/new`, behind `RequireWrite`, which gained an
optional message/back link) shows no form for a non-ACTIVE contract; the detail header shows "Catat
Pembayaran" for `ADMIN_OPERASIONAL` on ACTIVE. `useStatement` takes `{refType, from, to}` and builds the API
query (`statementPath`); `StatementView` adds Dari/Sampai/Ref type/Reset, resets to page 0 on a filter change,
and blocks an inverted range without a request; the ref column shows `ref_type` + a short `ref_id`.
`ApplyCreditDialog` (`ADMIN_OPERASIONAL`, ACTIVE, balance > 0) sends `{}` for a blank amount (apply all) or a
validated amount ≤ the balance, under a per-operation `Idempotency-Key` (backend now requires it, ADR-017 addendum); `CreditPanel` labels
applications by period. Test setup gained jsdom no-op pointer-capture/scroll polyfills so Radix Select can be driven in tests.
The installment table itself is T32's and is reused unchanged.
Verification: `npm run lint` (0 problems), `npm run typecheck`, `npm test` = **172 tests / 23 files / 0
failed**, `NEXT_PUBLIC_API_BASE_URL=https://ci-build.invalid npm run build` (lists
`/contracts/[id]/payments/new`); backend `./gradlew check` = 121 suites / 932 tests / 0 failed. Manual check in
the browser against the Compose stack as `admin`: created and activated a test contract with a past start
date, posted an overpayment → allocation to periods 1–2 (penalty → interest → principal) plus EXCESS with the
warning; schedule, header outstanding and statement refreshed without a reload; apply-credit with nothing due
showed `CREDIT_NOT_APPLICABLE` inline; the statement `ref_type=PAYMENT` filter reached the API.
Review remediation (2026-11-25, final whole-branch review): (1) **a payment that closed the contract hid its
own result** — the refetched CLOSED status was checked before the posted payment, so a maturity-closing
payment showed "only ACTIVE contracts"; `RecordPaymentView` now renders the result first, regardless of the
later contract state or a failed refetch, and `PaymentResult` drops "Catat pembayaran lain" and the
apply-credit advice once the contract is no longer ACTIVE. (2) Credit apply now invalidates on settle, so a
failed (possibly committed) apply reloads the balance before the next attempt, and (3) the dialog cannot be
closed while the apply is pending. (4) A ref-type change still applies while the date range is inverted, so
the table always matches the visible select. Each fix has a regression test that failed first. Verified:
lint, typecheck, `npm test` = **178 tests / 23 files / 0 failed**, build; re-checked in the browser by paying a
1-month test contract to completion (result shown, contract CLOSED).

Second review remediation (2026-11-25, PR #24, CodeRabbit plus an independent review; every finding was
verified against the code and specs first). (1) **Credit apply was not retry-safe (P1)**: TS §2.2 requires an
`Idempotency-Key` on credit application but T14 shipped none, so a committed apply with a lost response could be
applied again. The backend now wraps the apply in `IdempotencyService` inside its single transaction (claim,
credit consumption, installment resolution, journal), with replay, 409 `CONFLICT` for a different body, 409
`IDEMPOTENCY_KEY_EXPIRED` and a required header (ADR-017 addendum; the retention-window limitation is
recorded there). The dialog keeps one key per logical operation across close/reopen, retries with the same key,
mints a new one only after a success or an explicit "Mulai form baru", and stays mounted (with its error) when
the refreshed balance reaches zero or the credit reload fails. (2) **Payment cache** is invalidated on settle,
so an ambiguous failure refreshes every screen; the retry still reuses its key. (3) **Statement** no longer
keeps the previous page as placeholder data: a new filter or page shows the loading state, never stale rows,
and the pager is disabled while a request is in flight. (4) **Payment result** treats the contract status as
known only when the last refetch succeeded and finished; otherwise it offers no further payment or credit
advice and says the status is unconfirmed. Also fixed: a failed background contract refetch no longer
unmounts the payment form (and so its key) while a contract is cached, and an expired payment key no longer
claims the form was used with different values. Follow-up on the same PR: the balance-exceeded rejection has its
own code (`CREDIT_EXCEEDS_BALANCE`) so the UI no longer mistakes it for a spent key and the key stays usable;
after a definite 4xx the live balance is the ceiling again; and an unresolved apply (outcome unknown or key
spent) stays recoverable even when the apply closed the contract or emptied the balance, because the dialog is
gated by role only and `canStartNew` gates just a new application (a fresh form on a non-ACTIVE contract cannot submit and says why; a retry of an unresolved operation can). Verified: lint, typecheck, `npm test` = **203 tests / 23
files / 0 failed**, build; backend `./gradlew check` = 121 suites / 939 tests / 0 failed (7 new credit-apply idempotency tests). CI for PR #24 could not run (GitHub billing), so it is not
evidence either way.

**Goal:** Complete the day-to-day servicing screens: record a payment, read the schedule, read the
statement and inspect customer credit.

**Scope.**

- `/contracts/[id]/payments/new` (FE §2.6): amount, channel, paid-at (max now), note; `Idempotency-Key`
  generated on mount; the success view shows the allocation breakdown and an excess/credit warning, with
  a link back to the contract.
- Installment table (FE §2.5) on the contract detail from `GET /contracts/{id}/installments`: effective
  penalty, recognized total, resolved amount, outstanding, status badge.
- Statement page (FE §2.7) from `GET /contracts/{id}/statement`: ledger-literal debit/credit, ref type,
  reversal flag, date-range and ref-type filters; no running balance.
- Credit tab from `GET /contracts/{id}/credit`: available balance and application history; the
  apply-credit action is `ADMIN_OPERASIONAL` only (`POST …/credit/apply`, `Idempotency-Key` required since the T33 review).
- Cache invalidation after a payment so the schedule, detail and statement never show stale values.

**Business Rules.** 06_FRONTEND_SPEC §2.5–§2.7; ADR-013 A-8 (the statement is ledger-literal, with no
running balance); ADR-009/ADR-011 (allocation and recognition are backend-owned; the UI displays, never
computes); ADR-015/ADR-022 (role gating). `paid_at` is the server clock (TS §2.0): the response value is
authoritative and the form's date is only the operator's intent within the API contract.

**Implementation Notes.** The statement endpoint is already paginated (`page`/`size`) and supports
`ref_type` plus inclusive `from`/`to`; use those query parameters instead of filtering client-side. The
idempotency key stays in form state across retries; disabling submit is UX, not the guarantee. Do not add
a payments history tab here: it needs a backend read model and belongs to T39.

**Dependencies:** T32.

**Acceptance Criteria.**

- A payment posted from the UI appears on the installment table and the statement without a manual
  reload.
- The success view shows the allocation lines and flags excess credit; a replay is harmless.
- The statement shows exactly the backend rows (debit/credit literal); filters work and there is no
  running balance.
- The credit balance and history match `GET …/credit`; applying credit is hidden for non-admin roles.
- No stale schedule, detail or statement data remains after a payment.

**Tests.** Vitest/RTL: payment-form validation, idempotency-key stability across retries, allocation
rendering, statement filter wiring and credit role gating. Backend suites unchanged; `./gradlew check`
stays green.

**Risks.** Money and date formatting (id-ID grouping, no decimals, Asia/Jakarta boundaries) is the most
likely regression; keep it in `MoneyDisplay`/`DateDisplay` with tests. The payment form's "max now" must
use the business zone, not the browser's.

### T34 — G5: demo script, seed data and README close-out (2 pts)

Status: DONE
Estimate: 2 pts

Decision (2026-11-25): (1) **Relative dates (user decision).** Addendum §18 described Contract A/B as
"pre-seeded" with historical payments. The API cannot create that: `paid_at` is always the server clock,
ADR-004 forbids raw-SQL PII, and activation rejects a start date more than one year back
(`MAX_BACKDATED_ACTIVATION_YEARS = 1`). The script keeps §18's terms and golden values, backdates
`planned_start_date` relative to today in Asia/Jakarta (A: today − 70 days; B: today − 1 month), and pays
today. §18 is amended accordingly. (2) **Node, not bash/PowerShell:** a bash script needs `jq` to read JSON,
which the Windows dev environment lacks, while Node is already required by the frontend. (3) **Re-runnable:**
customers are reused (fixed NIK + phone), and each run uses a per-run asset `serial_no` without a plate, so
invariant 18 (`DUPLICATE_CONTRACT`) never fires. Every write gets a fresh `Idempotency-Key`. (4) **Opt-in
reset:** `--reset` runs `docker compose down -v`; without it the script never deletes the volume.

Implementation note (2026-11-25, branch `t34-demo-script`; plan
`docs/superpowers/plans/2026-11-25-t34-demo-script.md`): `scripts/demo/lib.mjs` holds the helpers (Jakarta
business date, month-end-clamped date math, BigInt-cent money, the `{data, error}` client with a
`DemoError` that carries method/path/status/raw body, per-request and per-attempt timeouts so a hung server
still fails, and health polling), with 17 `node:test` tests.
`scripts/demo.mjs` runs: health → admin login → Contract B (EFFECTIVE golden schedule; an on-time
4,769,959.90 payment allocates interest 1,125,000.00 + principal 3,644,959.90 with no penalty;
same-key replay returns the same `payment_no` and leaves the customer credit at zero) → Contract A (FLAT golden schedule; a 2,000,000.00 payment
pays installment 1 as penalty → interest → principal, installment 1 `PAID`, installment 2 `PARTIALLY_PAID`, installment 3 `PENDING`) →
the statement, paged and summarized per `ref_type` with Σ debit = Σ credit → aging → role split (finance:
statement 200, payment 403; manager: aging 200, statement 403). No new seed rows and no backend or
frontend source change. CI `compose-smoke` runs the helper tests and the script. Both READMEs document the
demo, the demo users and the known limitations. Verification: `node scripts/demo.mjs --reset` exit 0 on a
fresh volume; two further plain runs against the same database exit 0 with new contract numbers; the browser
follow-along matches (Jadwal: installment 1 `PAID`, installment 2 `PARTIALLY_PAID` with Rp 1.214.320 owed);
backend 121 suites / 939 tests / 0 failed (`./gradlew check --rerun-tasks`); frontend 203 tests / 23 files, lint and typecheck green; demo helpers 28/28. PR #25 review: `--up`/`--reset` refuse a non-loopback base URL before any Compose command, and money fields the API must return (excess, credit balances, outstanding) fail when absent instead of reading as zero. Observation: the
Jadwal "Denda" column shows the *remaining* penalty (ADR-019), so a paid late installment shows Denda Rp 0 and
Terbayar above Total Tagihan. This is pre-existing T32/T33 UX, documented under frontend Known limitations.

**Goal:** Make the system demonstrable from a clean checkout in one repeatable sequence.

**Scope.**

- Extend `R__demo_seed.sql` only where the demo needs more than the seeded users; customer and contract
  rows are created through the API (ADR-004: never raw SQL with plaintext PII). Prefer a script that
  calls the API over new seed rows.
- `scripts/demo.sh` (or `.ps1`): compose up → health → login as `admin` → create contract → activate →
  pay → read installments and statement → (optional) aging report. A fresh idempotency key per step;
  failures abort with the response body.
- `frontend/README.md` and the root `README.md`: how to run backend + frontend, the demo users, the demo
  sequence and the known limitations (in-memory tokens, reload = login).
- Optional: a read-only pass as `manager`/`finance` to demonstrate the role split.

**Business Rules.** PRD scenarios 1–2, ADR-004 (demo PII through the application), ADR-007
(idempotency), 04_GAPS_ADDENDUM §15. The demo must not require manual SQL or a pre-populated volume.

**Implementation Notes.** The demo is a repeatable script, not a screenshot: assert on HTTP status and a
few key fields. Do not add a frontend container or Compose service in this task; `npm run dev` plus the
Compose backend is the supported path until deployment is real. Keep the script secret-free: demo
passwords come from the seeded demo profile.

**Dependencies:** T33.

**Acceptance Criteria.**

- From an empty database and a fresh clone, the documented commands bring the stack up and the script
  exits 0.
- The script proves create → activate → payment → statement, and the browser flow can be followed in
  parallel.
- Re-running the script does not duplicate a contract (fresh key per run, or a unique payload per run)
  and does not fail on an existing customer or asset.
- README matches the actual commands and ports.

**Tests.** The script is the test: run it against a clean Compose stack and record the output in the task
close note. The T32/T33 frontend tests and `./gradlew check` stay green.

**Risks.** Demo data that is only representable through SQL would violate ADR-004; if a scenario needs
data the API cannot create, raise it instead of adding plaintext rows. The script is the first thing a
reviewer runs — a stale README is a failed deliverable.


## Completed task detail — Sprint 8 (Reporting, Outbox & PII Key Rotation: T36, T37, T38, T28a, T28b)

Moved out of `tasks.md` on 2026-11-27, once Sprint 8b closed and no sprint was left in progress. Sprint 8
is closed (T36 PR #28, T37 PR #29, T38 PR #30, T28a PR #31, T28b PR #32); these are its goal/exit header,
task bodies and close-out notes. The one-line index stays in `tasks.md` → Completed Work.

**Goal (05_SPRINT_PLAN.md Sprint 8):** management reporting and the event foundation are done, which closes
**Phase 3**. The dashboard metrics have written definitions (ADR-026), the aging and outstanding reports run
in the browser, and domain events are written transactionally and drained by a log-based publisher. The
sprint also carries CR-11: the PII key ring and a re-keying pass, so the AES and HMAC keys can rotate
without losing data.

**Scope (14 pts — a deliberate one-point stretch over the 13-point ceiling):**

- T36 — G1: reporting metrics + `GET /reports/outstanding` (3 pts) — DONE
- T37 — G4: frontend aging and outstanding pages + customer name in the aging API (3 pts) — DONE
- T38 — G6: transactional outbox writer, six domain events, log-based publisher (3 pts) — DONE
- T28a — PII key ring + `v2` envelope, ADR-025 (2 pts) — DONE
- T28b — PII/HMAC re-keying pass + `RequestFingerprint` key compatibility (3 pts) — DONE

**Exit:**

- `GET /reports/outstanding` returns the ADR-026 metrics, and its totals reconcile with the aging report.
- Both report pages render against the real backend for all three reporting roles.
- Each of the six events is written exactly once, in the business transaction, without PII, and the
  publisher moves it `PENDING → SENT`.
- A customer created under key `k1` stays readable and findable after `k2` becomes active. The re-keying
  pass moves every row to `k2`. A key is retired only once nothing signed with it is still live.
- `./gradlew check` and the frontend CI job are green, and `node scripts/demo.mjs` still exits 0.

**Order of work:** T36 → T37 → T38 → T28a → T28b.

- The reporting pair goes first because it is the sprint plan's Phase-3 goal.
- T38 comes next. It writes in the payment/contract/settlement services that T36 only reads, so it can
  start the moment T36 is merged.
- T28a/T28b come last. They touch only `shared/security`, `shared/idempotency` and `customer`.
- **If the sprint runs over, T28b is the point that slips to Sprint 8b.** T28a alone is safe to ship: one
  key in the ring behaves exactly as today. T28b landed in the sprint, so it did not slip; the HMAC key may
  now be rotated once no T28a instance runs (ADR-025 D6, D8).

> **Planned 2026-11-26.** The definitions in ADR-026, the event catalogue for T38, and the T28b retention
> rule were decided at planning. T38 grew from 2 to 3 pts because nothing writes to `outbox_events` yet: a
> publisher with no producers would drain an empty table. The decision was to take the one-point stretch,
> not to split the work. ADR-024 is already the exact-decimal wire format, so the key ring is **ADR-025**,
> the metric definitions are **ADR-026**, and T38's publisher semantics are **ADR-027**.

Business rules live in the PRD, `02_TECH_SPEC.md`, `03_DOMAIN_MODEL.md`, `04_GAPS_ADDENDUM.md`,
`06_FRONTEND_SPEC.md` and the cited ADRs. These tasks point to them and do not restate them.

### T36 — G1: reporting metrics and `GET /reports/outstanding` (3 pts)

Status: DONE
Estimate: 3 pts

Implementation note (2026-11-26, branch `t36-reporting-outstanding`): `GET /api/v1/reports/outstanding`
returns `as_of`, `total_outstanding`, `accounts_receivable`, `npl { contract_count, outstanding, ratio }`,
`collection { period_from, period_to, collected, due, rate }`, `settlement_cash { period_from, period_to,
amount }` and `active_contract_count`. Decisions:
- **Ports (ADR-026 D8).** `InstallmentAgingSnapshot` gained `due_date`. The new
  `contract.application.CollectionCohortSourcePort` returns the cohort's recognized total (net of active
  penalty adjustments, read through the existing bulk `EffectivePenaltyPort` only for contracts whose cohort
  installments carry penalty) and `settled_amount`. The new `payment.application.CollectedAllocationPort`
  sums POSTED non-EXCESS allocations in 1,000-id chunks (bind-parameter limit). The new
  `settlement.application.SettlementCashPort` turns the business dates into a half-open Jakarta instant
  range.
- **Calculator.** `reporting.application.OutstandingMetricsCalculator` is pure. Stock metrics come from
  one pass over the aging snapshots. Money and ratios follow ADR-026 D7.
- **`active_contract_count`** is the number of ACTIVE contracts with outstanding, i.e. the aging report's
  `contracts.total_elements`. The service is not `@Transactional`: each port reads in its own read-only
  transaction, so the reads share no snapshot and, under concurrent financial writes, one response can mix
  values from different points in time. The OpenAPI description says so.
- **V24** adds `idx_installment_due_date` and `idx_settlement_executed_at`. The baseline indexes lead with
  `contract_id`, so they could not serve the range reads. `OutstandingReportIT` asserts that the planner
  uses the due-date index.
- **Found in passing (not fixed here):** a partial penalty REDUCE on an OVERDUE installment with nothing
  paid fails with 409 (`ck_installment_paid_coherence`), because `Installment.recomputeResolutionStatus`
  promotes it to PARTIALLY_PAID with no `paid_at`. The IT pays 10,000 first to stay clear of it. Proposed as
  a separate bug task.

Verification: the new unit tests (`OutstandingMetricsCalculatorTest` 9, `OutstandingReportServiceTest` 2,
`CollectedAllocationServiceTest` 5, `SettlementCashServiceTest` 2), `OutstandingReportIT` 8 and
`EndpointRoleMatrixIT` 74 are green. The IT moves money through the API (payment, void, credit apply,
settlement, penalty reduce, daily job). Full `./gradlew check --rerun-tasks` = **126 suites / 968 tests /
0 failed**. Demo helpers 30/30. `node scripts/demo.mjs --up` exits 0 on a rebuilt stack, re-run
against an existing database: outstanding total = aging total, and manager → 200 on the new endpoint.

**Goal:** Serve PRD R-1's management metrics, computed exactly as ADR-026 defines them, from one read-only
endpoint that reconciles with the aging report.

**Scope.**

- `GET /api/v1/reports/outstanding?as_of=` (`as_of` optional, today only). Response, inside the standard
  envelope, in snake_case, money and ratios as strings:
  - `as_of`, `total_outstanding`, `accounts_receivable`.
  - `npl { contract_count, outstanding, ratio }`.
  - `collection { period_from, period_to, collected, due, rate }`.
  - `settlement_cash { period_from, period_to, amount }`.
  - `active_contract_count`.
  - Final field names are T36's call; the meanings are ADR-026's.
- Read-only ports owned by each data owner (ADR-026 D8):
  - **`contract`:** add `due_date` to the aging source snapshot (`InstallmentAgingSnapshot`), which AR
    needs. Add a cohort read that returns installment id, recognized total net of active penalty
    adjustments, and `settled_amount`, for installments due in a date range on non-DRAFT contracts.
  - **`payment`:** Σ of POSTED non-EXCESS allocations for a set of installment ids.
  - **`settlement`:** Σ `cash_received` for an `executed_at` business-date range.
- `reporting`: a pure metrics calculator and a service that composes the ports, plus the controller.
- **Role matrix:** a `GET /api/v1/reports/outstanding` row for `ADMIN_OPERASIONAL`, `FINANCE` and
  `MANAJEMEN` (Addendum §3.4 already lists it). Add it to `ResourceServerSecurityConfiguration` and to
  `EndpointRoleMatrixIT`. The API grows to 19 endpoints.
- OpenAPI annotations that cite ADR-026.
- **Demo:** `scripts/demo.mjs` reads the new endpoint and checks that `total_outstanding` equals the aging
  report's portfolio `total_outstanding` (sprint plan rule 8.3: update the demo every sprint).
- **Docs:**
  - `02_TECH_SPEC.md` §1: the reporting read path.
  - Current Project State: endpoint count.
  - README API list.

**Business Rules.**

- ADR-026 D1–D8: the definitions, scope, period, cohort, exclusions, null ratios and scale-4 HALF_UP
  ratios.
- ADR-013 A-6: `as_of` is today only, and any other value is `400 INVALID_AS_OF_DATE`. Reuse
  `InvalidAsOfDateException`.
- ADR-013 A-7 and `02_TECH_SPEC.md` §1: `reporting` consumes the `contract` module's
  `InstallmentBalance`/`InstallmentAging` values and never restates them.
- ADR-019: penalty is net of adjustments, through the existing effective-penalty path.
- ADR-020: only POSTED payments count.
- ADR-024: exact decimal strings on the wire.
- ADR-015: default-deny.

**Implementation Notes.**

- Total outstanding, AR and NPL come from one pass over the same snapshots the aging report reads. Do not
  add a second outstanding query.
- The collection numerator must read `payment_allocation`, never `installment.paid_amount`: credit
  applications raise `paid_amount` through `applyPaymentResolution` (`ContractCreditCommandService`).
- The period is `[as_of.withDayOfMonth(1), as_of]`. Convert `executed_at` to a business date in the
  application zone (`Asia/Jakarta`, the injectable `Clock`'s zone).
- The service is not `@Transactional` itself. Each port reads inside its own read-only transaction, as
  `AgingReportService` does.
- **Snapshot consistency:** the ports are separate read-only transactions that share no database snapshot,
  so any number of financial writes (payments, voids, credit applications, settlements, penalty changes) can
  commit between them, and under concurrent writes one response can mix values from different points in
  time. Accept this, because the report is not a ledger, and record it in the controller's OpenAPI
  description.

**Dependencies:** ADR-026 (Accepted at planning). T8, T30 and T33 are DONE.

**Acceptance Criteria.**

- `total_outstanding` equals the aging report's portfolio `total_outstanding` for the same data.
- NPL matches the aging rows: `npl.outstanding` = Σ `total_outstanding` of the aging rows with
  `dpd_over_90 > 0`, and `npl.contract_count` is the number of those rows.
- `accounts_receivable ≤ total_outstanding`, and `0 ≤ collection.collected ≤ collection.due`.
- A ratio with a zero denominator is `null`. A non-null ratio is a scale-4 decimal string.
- What is excluded from the collection rate: a voided payment's allocations, `EXCESS`, credit
  applications, settlement allocations and written-off amounts. Written-off amounts stay in `due`; the
  others are excluded from the numerator, and settlement from both sides.
- `settlement_cash` sums `cash_received` only (not `credit_used`), for settlements executed in the period.
- Any `as_of` other than today gets `400 INVALID_AS_OF_DATE`.
- `MANAJEMEN`, `FINANCE` and `ADMIN_OPERASIONAL` get `200`, and an untokened call gets `401`.
  `EndpointRoleMatrixIT` pins the row the same way it pins `GET /reports/aging`.
- The demo script still exits 0 with the new check.

**Tests.** Narrowest first:

1. **Calculator unit tests**, hand-computed fixtures:
   - A contract with one installment at DPD 91 and two future-due installments: the whole contract counts
     as NPL.
   - Grace boundaries: DPD 90 is not NPL, DPD 91 is.
   - An installment due today but inside grace: in AR, DPD 0.
   - Cohort edges: the first of the month and `as_of` are in; the last day of the previous month and
     tomorrow are out.
   - Collection rate: an early payment counted; a voided payment, `EXCESS` and a credit application
     excluded; a settled installment taken out of both sides; a written-off installment in `due` only.
   - Zero denominators. HALF_UP at scale 4.
2. **Port tests** for each owning module.
3. **`OutstandingReportIT`.** Testcontainers, with data seeded through the API on the Addendum §18
   contracts and clock moves:
   - the totals reconcile with `/reports/aging`;
   - the role matrix;
   - `INVALID_AS_OF_DATE`.
4. `EndpointRoleMatrixIT`, then `./gradlew check`.

**Risks.**

- The cohort read is the costliest query. Bound it to one month of due dates and index-check
  `installment.due_date`. A plan that scans every installment is a defect at portfolio scale.
- It is tempting to compute NPL from the bucket. ADR-026 D4 is whole-contract, and the identity test
  guards it.

### T37 — G4: frontend report pages and customer name in the aging API (3 pts)

Status: DONE
Estimate: 3 pts

Implementation note (2026-11-26, branch `t37-report-pages`; plan
`docs/superpowers/plans/2026-11-26-t37-report-pages.md`): the aging rows carry `customer_name`, and the
browser has `/reports/aging` and `/reports/outstanding` under a "Laporan" sidebar group for the three
reporting roles. Decisions:
- **Name through the `contract` port, as scalars.** `ContractRepository.findActiveContractCustomerNames()`
  selects `c.id` and `cu.fullName` only, so no `Customer` is hydrated and NIK/phone are never decrypted to
  label a row. `AgingReportSourceService` now reads at `REPEATABLE_READ`, so the installments, penalty
  adjustments and names share one PostgreSQL snapshot; a missing name throws instead of dropping a row.
- **`as_of` is never sent.** The read-only "Per tanggal" field shows the response's `as_of`; the backend
  defaults to its own today, so a browser in another zone cannot ask for the wrong day (ADR-013 A-6).
- **No browser arithmetic.** The summary row is the API's `portfolio`; the cards are the T36 values,
  formatted only. Ratios use the new `formatRatioPercent` (`"0.0150"` → `1,50%`, BigInt, no float); a
  `null` ratio renders the app's empty dash "–" (FE §2.10 updated). The NPL badge is `dpd_over_90` not
  being a zero string (ADR-026 D4). The aging API sends an empty bucket as `"0"`, which the test covers.
- **Shared table.** `AgingContractTable` serves both pages (portfolio summary row on the aging page, NPL
  badge on the outstanding page). Cards and table are separate queries that load and fail independently.
  While another page loads, the previous response stays as placeholder data so the summary row and
  `as_of` stay on screen, but its rows render as skeletons, never as the new page's rows (final review).
- **Verification:** backend `./gradlew check --rerun-tasks` = 126 suites / 969 tests / 0 failed (new
  `AgingReportIT.everyRowCarriesTheCustomerNameAndNoOtherPii`). Frontend `npm test` = 234 tests / 27 files
  / 0 failed; `lint`, `typecheck` and `build` (with `NEXT_PUBLIC_API_BASE_URL`) green. Demo helpers 30/30;
  `node scripts/demo.mjs --up` exits 0. Both pages checked in the browser against the Compose stack as
  `admin`, `finance` and `manager`: summary row = API `portfolio`, cards = API values, no request carries
  `as_of`. The demo book has no contract past 90 DPD, so the NPL badge is proven by component tests only.

**Goal:** Let an operator, finance user or manager read the aging and outstanding reports in the browser.
The pages show numbers the backend computed and never compute their own.

**Scope.**

- **Backend (small):** add `customer_name` to the aging report's per-contract row (`ContractAgingRow`).
  It is carried through `AgingReportSourcePort` from `customer.full_name`. Update OpenAPI and the aging
  report IT.
- **`/reports/aging` (`06_FRONTEND_SPEC.md` §2.9):**
  - `as_of` is shown and locked to today: read-only, with no other value ever sent.
  - A portfolio summary row comes from the response's portfolio buckets.
  - Table columns: Contract No (linking to `/contracts/[id]`), Customer, Outstanding, Current, 1–30,
    31–60, 61–90, >90.
  - Server-side `page`/`size` pagination, plus loading, empty and error states.
- **`/reports/outstanding` (§2.10):**
  - **Cards from T36:** Total Outstanding, AR (receivable due as of today), NPL % with NPL outstanding and
    contract count, Collection Rate MTD with collected/due and the period label, and Settlement Cash MTD
    as its own card.
  - **Per-contract table:** reuses the `/reports/aging` rows (ADR-026 D4 identity). Rows with
    `dpd_over_90 > 0` carry an NPL badge.
  - No second per-contract endpoint and no status column. The aging rows are ACTIVE contracts only.
- **Navigation:** a "Laporan" entry with both pages, visible to all three reporting roles. Authorization
  stays server-side.
- **Docs:** confirm `06_FRONTEND_SPEC.md` §2.9/§2.10 against the shipped pages, and update README and
  Current Project State.

**Business Rules.**

- ADR-026: what each card means. Null-ratio display: FE §2.10.
- ADR-013 A-6: `as_of` locked to today.
- ADR-024 and FE §3.1: money and ratios stay decimal strings, and percentages are formatted with
  `lib/decimal.ts`/`lib/format.ts`, never `Number`/`parseFloat`.
- ADR-023: roles drive what renders, not what is allowed.
- Addendum §9 and ADR-004 mask and encrypt only NIK and phone. The full name is unencrypted, and
  `GET /contracts` already returns it to all three roles. NIK and phone never appear on a report.

**Implementation Notes.**

- **No metric arithmetic in the browser.** The only derived value is the NPL badge, `dpd_over_90 > 0`,
  which ADR-026 D4 defines as the NPL test itself. Totals come from the API's portfolio buckets, not from
  summing a page of rows.
- Reuse the existing API client, query keys, `MoneyDisplay`, and table/loading/error components.
- Add a ratio→percent formatter in `lib/format.ts`, unit-tested in BigInt: `"0.0150"` → `1,50%`.
- The customer name must come through the `contract` port. `reporting` does not read `customer` directly.

**Dependencies:** T36 (`/reports/outstanding` and the `due_date` snapshot change; T37 extends the same
snapshot with the customer name).

**Acceptance Criteria.**

- Both pages render against the Compose stack for `ADMIN_OPERASIONAL`, `FINANCE` and `MANAJEMEN`.
- The aging page never sends an `as_of` other than today.
- The summary row equals the API's portfolio buckets.
- Every card shows the T36 value unchanged, apart from formatting. A `null` ratio renders "–".
- The NPL badge appears exactly on rows with `dpd_over_90 > 0`.
- The aging API returns `customer_name` on every row. The existing aging IT and `EndpointRoleMatrixIT` stay
  green.
- Frontend `npm test`, `lint`, `typecheck` and `build` are green in CI.

**Tests.**

1. Unit test for the ratio formatter: scale 4, HALF_UP already applied by the API, null, `"1.0000"`.
2. Component tests for both pages: loading, empty, error, populated, locked `as_of`, null ratio, NPL badge,
   pagination.
3. Backend: extend `AgingReportIT` for `customer_name`.
4. Then the full frontend suite and `./gradlew check`.

**Risks.**

- The page might sum a page of rows for the totals. That is wrong as soon as there is more than one page;
  the summary row must come from the API.
- The NPL badge is the one browser-side derivation. Any second one is scope creep and a second formula.

### T38 — G6: transactional outbox writer, six domain events and a log-based publisher (3 pts)

Status: DONE
Estimate: 3 pts

Implementation note (2026-11-26, branch `t38-outbox-events`; plan
`docs/superpowers/plans/2026-11-26-t38-outbox-events.md`): `shared.outbox.OutboxWriter` (`MANDATORY`) writes
the six events at their transitions, with dedicated PII-free payload records; ADR-027 holds the catalogue.
Decisions:
- **Write order: `created_at`, then the id.** `created_at` is stamped per persist from the application clock;
  when two events of one transaction tie on it (always under the tests' `FixedClock`), the writer's per-process
  monotonic UUIDv7 (`OutboxEventIds`) breaks the tie in write order, so `PaymentReceived` and
  `SettlementExecuted` each precede the `ContractClosed` they cause. Assumes no backward wall-clock step
  between the two writes (ADR-027 D4). No migration.
- **One MATURITY site.** `ContractClosed` is written in `closeWhenEveryInstallmentIsPaid` and
  `SettlementCloseService`; the first also covers a penalty waiver that clears the last amount (defensive:
  the penalty-first waterfall means no API flow reaches it today, so a unit test pins it).
- **`PaymentVoidPort.applyVoidRecomputation` now returns whether it reopened** a MATURITY close, for
  `contract_reopened`.
- **Publisher:** scheduler → `LockedOutboxPublisherJob` (lock `outbox-publisher`, every 5 s) →
  `OutboxBatchPublisher` (`REQUIRES_NEW`, `FOR UPDATE SKIP LOCKED`) → `OutboxSink` (`LoggingOutboxSink`, one
  ECS line per event with `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `payload`). A tick stops
  after a batch with a failure, so a refused event retries on the next tick; at `max-retries` (10) it is
  `FAILED`. Only a tick with failures writes a `job_run` row.
Verification: backend 136 suites / 1017 tests / 0 failed (`./gradlew check --rerun-tasks`); frontend 236 tests / 27 files (unchanged code); demo helpers 30/30;
`node scripts/demo.mjs --reset` exit 0, and the app logged one `Published outbox event` line per event (2
`ContractActivated`, 2 `PaymentReceived`; the replay and the refused finance payment wrote none), all `SENT`
within one or two 5-second ticks on a `serfira-scheduling-` thread,
with no demo customer name, NIK or phone. Mutation checks: `REQUIRED` instead of `MANDATORY`, the settlement
event written after the close, and a claim without `SKIP LOCKED` each fail their test. Review fix: the ShedLock
keep-alive `ScheduledExecutorService` bean had switched off Boot's task scheduler, so every `@Scheduled` job ran
on the single `shedlock-keep-alive` thread, which would have stalled the publisher for the whole nightly run and
blocked lock renewals while a job ran. `JobSchedulingConfiguration` now defines a three-thread
`serfira-scheduling-` `TaskScheduler`; `SchedulingThreadIT` pins it.

**Goal:** Make domain events durable and observable without Kafka.

- Six business transitions each write one event in the same transaction as the change.
- A separate publisher drains them to the structured log and marks them sent.
- When a broker arrives later, it replaces only the publisher's sink.

**Scope.**

- **`shared.outbox.OutboxWriter`**, reusable, `Propagation.MANDATORY`.
  - `append(aggregateType, aggregateId, eventType, payload)` inserts one `PENDING` row into
    `outbox_events` (V1) with the audit actor.
  - The payload is serialized with the application `ObjectMapper`, so money is a decimal string
    (ADR-024).
- **The six events and their emission sites**, each only when the transition is actually performed:

  | Event | Aggregate | Emitted from |
  |---|---|---|
  | `PaymentReceived` | `PAYMENT` | `POST /payments`, after the payment and its allocations persist |
  | `PaymentVoided` | `PAYMENT` | `POST /payments/{id}/void`. The payload includes `contract_reopened` when the void reopened a MATURITY close (ADR-020 D1.3) |
  | `ContractActivated` | `CONTRACT` | activation |
  | `ContractClosed` | `CONTRACT` | every `Contract.close(...)` that returns `true`: MATURITY through a payment or a credit application (`InstallmentReceivableService`), and SETTLEMENT (`SettlementCloseService`) |
  | `ContractWrittenOff` | `CONTRACT` | write-off |
  | `SettlementExecuted` | `SETTLEMENT` | settlement execution. Followed by `ContractClosed` in the same transaction |

- **Payloads.** Minimal and PII-free:
  - Allowed: `schema_version: 1`, ids, business numbers, amounts, enums, and business dates/instants.
  - Never: customer name, NIK, phone or address, and no free text, so no write-off reason and no void
    reason.
  - The event id is the outbox row id.
- **Publisher.** A scheduled job with its own ShedLock lock. Each tick:
  - Claims a bounded batch of `PENDING` rows ordered by `(created_at, id)` with `FOR UPDATE SKIP LOCKED`.
  - Emits one structured INFO log line per event: id, type, aggregate and payload (ECS JSON, T20).
  - Sets `SENT` and `sent_at`.
  - On a sink failure, increments `retry_count`; after a configured maximum the row goes to `FAILED`.
  - Batch size, delay and maximum retries are properties with safe defaults.
- **ADR-027** records the event catalogue, the payload rules, the delivery semantics, and the sink seam for
  a future broker. Delivery is at-least-once: a crash after logging but before the `SENT` commit re-emits
  the event, and consumers deduplicate by event id.
- **Docs:**
  - `02_TECH_SPEC.md` §2.7: a log-based publisher now, Kafka later.
  - `03_DOMAIN_MODEL.md` §1.15: point to the ADR-027 catalogue.
  - GLOSSARY entries for the outbox event and the publisher.

**Business Rules.**

- `02_TECH_SPEC.md` §2.7: events are written in the business transaction, and the publisher is separate.
- `03_DOMAIN_MODEL.md` §1.15: the `outbox_events` columns.
- Addendum §9: no PII in logs. The publisher's log line is a log, so the payload rule is a hard rule, not
  a style choice.
- ADR-017: an idempotent replay returns the stored response without running the operation, so it emits
  nothing.
- ADR-011, ADR-018, ADR-019 and ADR-020: the existing close, settlement, write-off and void semantics. The
  events report them and change none of them.

**Implementation Notes.**

- Emit inside the existing transactional services, after the state change and before commit, through
  `OutboxWriter` only. Controllers never write events.
- A transaction that rolls back drops its event. Retrying wrappers (`PaymentRetryingService`,
  `SettlementExecutionRetryingService`, `PaymentVoidRetryingService`) re-run in a fresh transaction, so a
  conflict-retry still yields exactly one event.
- `ContractClosed` is emitted where `close()` returns `true`, not in each caller. One site in
  `InstallmentReceivableService` covers both a payment and a credit application that matures a contract.
- **No new migration expected:** `outbox_events` and `idx_outbox_status_created` exist in V1. Retention of
  `SENT` rows is out of scope; record it as a Deferred row.
- The publisher must not write a `job_run` row on an idle tick. A busy or failed tick may record one, and
  ADR-027 decides which.

**Dependencies:** none. Sequenced after T36/T37, see the order above.

**Acceptance Criteria.**

- Each of the six flows writes exactly one row of its type, with the expected aggregate and payload.
  Settlement writes two rows: `SettlementExecuted`, then `ContractClosed`.
- A replayed `Idempotency-Key` writes no second row. A request that fails or rolls back writes none.
- A payment that does not close its contract writes no `ContractClosed`. A void that reopens one writes
  `PaymentVoided` with `contract_reopened: true`.
- No payload contains the customer's name, NIK, phone, address or any free-text reason. Assert this
  against the seeded values.
- The publisher moves `PENDING → SENT` with `sent_at`, logs one line per event, leaves `SENT` rows alone,
  and after the maximum retries marks a row `FAILED`. Two concurrent ticks never emit the same row twice
  in one pass.
- `AccountingInvariantsIT`, `EndpointRoleMatrixIT` and `./gradlew check` stay green. The demo exits 0.

**Tests.**

1. `OutboxWriter` unit test: serialization, decimal strings, no transaction means an error.
2. Publisher unit tests with a fake sink: success, failure, retry ceiling.
3. **`OutboxEventsIT`.** One case per flow, plus:
   - replay;
   - rollback;
   - a payment with no close;
   - a void reopening a MATURITY close;
   - a credit application that matures the contract;
   - the PII scan.
4. **`OutboxPublisherIT`:** batch drain, `SKIP LOCKED` under two concurrent ticks, retry → `FAILED`.

**Risks.**

- **Missed emission sites.** `ContractClosed` has two callers and one of them is reached through credit
  application. The per-flow IT is the guard.
- **PII leaking through a convenient payload** such as a response DTO. Payloads are dedicated records, and
  the PII-scan test enforces it.

### T28a — PII key ring and versioned `v2` envelope (CR-11) (2 pts)

Status: DONE
Estimate: 2 pts

Implementation note (2026-11-26, branch `t28a-pii-key-ring`; plan
`docs/superpowers/plans/2026-11-26-t28a-pii-key-ring.md`): `PiiSecuritySupport` now holds a validated
`PiiKeyRing` (`PiiKey` = `kid` + AES key + HMAC key); ADR-025 records the scheme and the rotation runbook.
Decisions:
- **Configuration is additive.** The flat `encryption-key`/`hmac-key` pair is ring entry `k1`;
  `serfira.security.pii.keys.<kid>.*` adds entries; `active-kid` and `legacy-kid` default to `k1` when the
  flat pair is set. Compose, the test profile and the README's existing variables are unchanged.
- **`kid` = 1–16 lowercase letters or digits**, so it round-trips through
  `SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY` (Spring lowercases env map keys and splits on `_`).
- **Two-phase write format (PR #31 review).** `serfira.security.pii.write-envelope` = `v1` (default) or `v2`.
  This version reads both but writes `v1` until an operator sets `v2`, so a pre-T28a instance running alongside
  it, or a rollback, can still read every row. `v1` writes are refused unless the active and the legacy key are
  both `k1` (the key every `v1:` row is under), so a rotation needs `v2` first (ADR-025 D7).
- **Envelope `v2:<kid>:<base64>`**, no AAD: a relabelled body fails its tag because no two ring keys share
  material. `v1:` reads under the legacy key. Every failure is an `IllegalArgumentException` that never echoes
  the envelope or its `kid` (a 16-digit NIK fits the `kid` syntax). No migration (`TEXT` columns since V2).
- **Candidates.** `hmacHex` stays the active key (writers, `RequestFingerprint`); `PiiHasher.nikCandidates` /
  `phoneCandidates` hash under every ring key, active first. `CustomerSearchService` and `resolveCustomer`
  (including its phone comparison) match any candidate through `CustomerLookup`; two matching rows fail with
  the count only.
- **Startup** refuses an unknown active/legacy `kid`, a duplicate or invalid `kid`, a wrong key length, shared
  key material, a half-configured entry, bad base64, and `keys.k1` alongside the flat pair; messages name the
  property and variable, never the value. The dev-key guard checks every ring member.
Rulings: the existing `v1:` prefix assertions are unchanged again, because the default is `v1` writes; the
single-hash finders `findByNikHash`/`findByPhoneLookup` stay for tests; the at-most-one check lives in
`contract.application.CustomerLookup`, because Spring Data's exception translation rewraps an exception thrown
in a repository default method. ADR-004 and Addendum §9 now name the real `SERFIRA_SECURITY_PII_*` variables.
Verification: backend 140 suites / 1100 tests / 0 failed (`./gradlew check --rerun-tasks`; T28a added
`PiiKeyRingTest`, `PiiSecuritySupportKeyRingTest`, `PiiKeyRingConfigurationTest` and `PiiKeyRotationIT`, which
writes under `k1` in one application context and reads, finds, reuses and 409s in a second context with `k2`
active on the same database); frontend and demo unchanged (no change outside the backend and docs). Review
fixes: the dev-key guard trims like the key loader (a dev key with a trailing newline no longer passes); an
active/legacy kid that is not a key id is never echoed (it may be a pasted key); `v1` create-reuse is asserted;
README and ADR-025 D4/D6 corrected on the kid defaults and the mixed-active-key window during a rolling switch.
Second review: `v1` writes need active = legacy = `k1` (`active-kid=k2` + `legacy-kid=k2` no longer starts in
`v1` mode); a ring with no legacy key is told `legacy-kid` is missing instead of being steered to `v2`; the
dev-key guard compares every key with all three public Compose values (a swapped dev key no longer passes);
a `keys.<kid>` entry of the wrong shape, an invalid `kid`, a wrong length and shared material each fail
naming the property and variable; `PiiKey` validates in its canonical constructor and prints only its `kid`;
the `PiiKeyRing` constructor without an explicit envelope is gone; two stale Javadocs corrected.
**Do not switch the active key in any environment until T28b ships** (ADR-025 D6).

**Goal:** Let the PII encryption and HMAC keys rotate without making existing rows unreadable or
unfindable (CR-11). Record the scheme in **ADR-025**.

**Scope.**

- **Key ring.** Entries keyed by a key id (`kid`). Each entry holds an AES-256 encryption key and an HMAC
  key, and rotation moves both together. One `kid` is **active**.
- **Configuration.** The existing `serfira.security.pii.encryption-key` / `hmac-key` properties and their
  `SERFIRA_SECURITY_PII_*` variables keep working unchanged as a one-key ring, so Compose, the test profile
  and the README need no edits to start. Ring properties are additive, and their exact names are ADR-025's
  call.
- **Envelope.**
  - Writes produce `v2:<kid>:<base64(iv ‖ ciphertext|tag)>` under the active key.
  - Reads accept `v2` under any ring key, and `v1:` under a designated legacy `kid`. That `kid` defaults
    to the single configured key.
  - An unknown `kid`, a malformed `v2` envelope or a failed tag throws. Nothing is silently null.
- **HMAC.**
  - New lookups and uniqueness values use the active key.
  - Search and dedup compute **candidates** under every ring key, active first, and match any of them:
    `findByNikHashIn` / `findByPhoneLookupIn`.
  - `resolveCustomer`'s phone comparison must be candidate-aware too: an existing customer hashed under
    the old key must not be reported as "registered with a different phone".
- **Startup validation:**
  - the active `kid` exists;
  - every key has the right length;
  - no two `kid`s share key material;
  - `kid` syntax has no `:`, is non-empty and is bounded.
- **`DevKeyProductionGuardConfiguration`** checks every ring member, not only the active one.
- **ADR-025.** Records the envelope, ring configuration, candidate matching and failure modes, plus the
  rotation runbook: add the key, switch active, run T28b, retire. It points to T28b for the retention rule.
- **Docs:** ADR-004 consequence line (rotation now exists), Addendum §9 key lines, GLOSSARY, README
  environment section.

**Business Rules.**

- ADR-004: AES-256-GCM with a random IV, an authenticated envelope, HMAC lookup on normalized values, and
  keys never in the database.
- Addendum §9: PII never in logs, URLs or SQL, and startup fails fast on a missing key.
- ADR-022 D13: the dev-key guard.
- The create flow never mutates an existing customer row (`ContractCommandService.resolveCustomer`;
  PRD C-5 customer update is deferred). T28a keeps that rule.

**Implementation Notes.**

- `PiiSecuritySupport` stays the static bridge (Hibernate builds the converter).
- `hmacHex` keeps meaning "active key" for writers. Candidate matching is a separate method. Callers:
  `CustomerSearchService`, `ContractCommandService.resolveCustomer`. `RequestFingerprint` stays on the
  active key here, and T28b makes its comparison key-aware.
- Unique indexes on `nik_hash`/`phone_lookup` still catch a race, because concurrent creates both hash
  under the active key.
- **A ring of one key must behave exactly as today, apart from new writes being `v2`.** This is what makes
  T28a shippable without T28b.

**Dependencies:** T21b (DONE: dev-key guard).

**Acceptance Criteria.**

- A customer written under `k1` (`v1` or `v2`) is read, found by NIK/phone search, and reused by contract
  create after the ring becomes `{k1, k2 active}`. A different phone still gets `409`.
- New writes are `v2:k2:…`.
- A `v2` envelope with a `kid` not in the ring fails loudly. Tampering fails. A `v1` envelope reads under
  the legacy key.
- Startup refuses an unknown active `kid`, a wrong-length key, duplicate key material, or any ring member
  equal to a Compose dev key under a production profile.
- Existing PII, customer and contract tests pass unchanged on the one-key ring.

**Tests.**

1. `PiiSecuritySupport` unit tests:
   - round trips for `v1` and `v2`;
   - decrypt under a non-active `kid`;
   - unknown `kid`;
   - malformed and tampered envelopes;
   - candidate order.
2. Configuration validation tests. Dev-key guard tests across the ring.
3. **`PiiKeyRotationIT`:** create under `k1`, restart the context with `{k1, k2 active}`, then search, reuse
   and `409`.
4. `./gradlew check`.

**Risks.**

- Rotating the HMAC key before T28b ships breaks idempotent replays: a stored `request_hash` no longer
  matches the active-key fingerprint, so a replay gets `409`. ADR-025 and the README must say not to
  rotate until T28b.
- If candidate matching is skipped anywhere, a duplicate customer can be inserted under the new key. The
  IT covers both lookup sites.

### T28b — PII/HMAC re-keying pass and `RequestFingerprint` key compatibility (CR-11) (3 pts)

Status: DONE
Estimate: 3 pts

Implementation note (2026-11-26, branch `t28b-pii-rekey`; plan
`docs/superpowers/plans/2026-11-26-t28b-pii-rekey.md`): ADR-025 D8–D10 record the scheme and the retention
rule. Decisions:
- **The migration is V25, not V24.** V24 is T36's reporting indexes. `V25__idempotency_request_hash_kid.sql`
  adds `request_hash_kid VARCHAR(16) NULL` with a `kid`-syntax check and no backfill (`NULL` = legacy key).
  The Scope below is corrected.
- **Replay, unexpired and on-target rules:** see ADR-025
  [D8](adr/ADR-025-pii-key-ring.md#d8--idempotency-fingerprints-record-their-key-id) (replay under the
  recorded `kid`, and the T28a-instance rollout rule),
  [D9](adr/ADR-025-pii-key-ring.md#d9--the-re-keying-pass) (when a row is on target) and
  [D10](adr/ADR-025-pii-key-ring.md#d10--retention-rule-and-retirement-verdict) (what counts as unexpired).
- **`EXECUTE` refuses `write-envelope=v1`** (the pass writes `v2`) as a `PiiRekeyPreflightException` before any
  write; `DRY_RUN` runs in either mode.
- **Runner.** A synchronous `ApplicationRunner`, so the application serves during the pass and a failure never
  stops it. Properties: `serfira.maintenance.pii-rekey.enabled` (absent = no bean), `.mode` (`DRY_RUN`
  default), `.batch-size` (500), `.lock-at-most-for` (`PT10M`); environment forms
  `SERFIRA_MAINTENANCE_PIIREKEY_ENABLED` / `_MODE` / `_BATCHSIZE`.
- **Retirement verdict per `kid`:** `ACTIVE`, `RETIRABLE` (no envelope, off-active hash, unexpired fingerprint
  or unreadable row remains; `v1:` and `NULL` count under the legacy key), `NOT_RETIRABLE`, or
  `MISSING_FROM_RING` for an unexpired fingerprint whose `kid` is not in the ring.
- **`job_run` counters:** `DRY_RUN` and `EXECUTE` complete with 0 or the rows re-keyed and 0 failed; a
  pre-flight abort records `FAILED/0/<blocking findings>` (the sum of four counts, so a row duplicated on NIK
  and phone counts twice); a later abort records the rows committed so far and 1.
- **Customer-row writes bypass JPA** (native `JdbcClient` SQL on the raw columns, as the `SYSTEM` actor), because
  the entity holds only plaintext and dirty checking would never fire.
Rulings: the resume cursor advances by the rows scanned, not rewritten, so an aborted run resumes without
looping or skipping; the startup runner logs the message only for its own abort exceptions and the class name
alone for anything else, because a unique-violation message quotes a lookup hash; every run logs one counts-only
`pii-rekey census ...` line (with `executeWouldRun`, false when `EXECUTE` would refuse or abort), so a `DRY_RUN`
shows the duplicate counts in advance; the summary line carries `unlabelledFingerprints`, and with no legacy key
any unlabelled claim keeps every non-active key `NOT_RETIRABLE`.
Verification: backend 149 suites / 1170 tests / 0 failed (`./gradlew check --rerun-tasks`, BUILD
SUCCESSFUL; T28b added the census, row-verifier, retirement-report, runner and configuration suites,
`PiiRekeyIT` and `IdempotencyKeyRotationIT`, which restart the application on one database under a different
ring); frontend and demo unchanged (no change outside the backend and docs). ADR-025 D6 now forbids switching
the active key only while a T28a instance runs.

**Goal:**

- Move every customer's ciphertext and lookup hashes to the active key, safely and resumably.
- Keep idempotent replays correct across an HMAC rotation.
- Say exactly when an old key can be retired.

**Retention rule (decided at planning).** An old HMAC key is retained until every record signed with it has
expired:

- **`customer` rows:** none remain once a verified re-keying pass completes.
- **`idempotency_keys` rows:** none are still inside their retention window (`expires_at > now`). After
  that point a reused key is rejected before its hash is compared (ADR-017).

An old encryption key is retired once no `customer` row carries its `kid`, including `v1` rows when it is
the legacy key.

**Scope.**

- **Maintenance runner.**
  - Disabled unless `serfira.maintenance.pii-rekey.enabled=true`, with `mode=DRY_RUN|EXECUTE`. No bean
    exists when it is off.
  - Runs once at startup in-process under its own ShedLock lock and records a `job_run` row (`pii-rekey`).
    The app keeps serving: customer rows have no update path, and new inserts are already on the active
    key.
  - It is the one sanctioned writer of existing `customer` rows. It changes only the ciphertext and lookup
    columns, never the plaintext values, so the create flow's never-mutate rule and its audit trail still
    hold.
- **Pre-flight.** Before any write, one keyset-paged scan decrypts every customer and computes active-key
  hashes. It **aborts with zero writes** if:
  - any two customers would share an active `nik_hash` or `phone_lookup`, or
  - any row has an unknown `kid` or fails to decrypt.

  Logs are counts only, never values.
- **Execute.**
  - Pages by `id`, one transaction per page.
  - Re-encrypts `nik`/`phone` under the active `kid` and rewrites `nik_hash`/`phone_lookup`.
    Encryption happens in the application (ADR-004: never in SQL). The write is forced, because JPA dirty
    checking does not fire when the plaintext is unchanged.
  - **Verifies each row** in the same transaction by re-reading, decrypting and comparing plaintext and
    hashes. A mismatch rolls back that page and aborts.
  - Rows already on the active key are skipped, so a re-run is a no-op and an interrupted run resumes.
  - `updated_by` is the `SYSTEM` user.
- **`RequestFingerprint` key compatibility.**
  - New migration V25 adds a nullable `idempotency_keys.request_hash_kid`. `NULL` means legacy: signed
    with the legacy key.
  - The claim records the active `kid`.
  - The replay comparison in `IdempotencyService` recomputes the fingerprint under the row's recorded
    `kid` (legacy for `NULL`). It no longer compares only against the active key, so a replay across a
    rotation is a replay, not a `409`.
  - A recorded `kid` missing from the ring fails loudly. That would mean the key was retired too early.
- **Retirement report.** Every run, dry or real, prints per-`kid` counts:
  - customer rows by envelope `kid`;
  - customer rows whose hashes are not on the active key;
  - unexpired `idempotency_keys` rows by `request_hash_kid`.

  It ends with a retirable / not-retirable verdict per `kid`, by the rule above.
- **Docs:** ADR-025 addendum (retention rule, runner, V25), README operations runbook, Addendum §9.

**Business Rules.**

- ADR-004 "Considerations for later sprints": migrations that touch existing `nik`/`phone` rows go through
  the application, never SQL encryption.
- ADR-017: a key is single-use forever, and past `expires_at` it is rejected before any hash comparison.
- Addendum §9: no PII in logs.
- ADR-007: `request_hash` is a keyed digest, never the payload.

**Implementation Notes.**

- Keyset paging follows the T18/T6 pattern (`id > :after ORDER BY id LIMIT :n`).
- The runner reuses the `job_run` lifecycle (abandon a stale `RUNNING` row, then start) from
  `IdempotencyCleanupJob`.
- Write-off idempotency rows are retained past expiry (T18). They count for retirement only while
  unexpired: an expired row is never compared.
- The retention window is the `IDEMPOTENCY_KEY_RETENTION_DAYS` system parameter (7). The report reads each
  row's own `expires_at`, so a later parameter change is handled.

**Dependencies:** T28a.

**Acceptance Criteria.**

- `DRY_RUN` writes nothing and reports the counts.
- `EXECUTE` moves every customer to the active `kid` with active hashes. A second run changes zero rows.
- After the pass, a ring **without** the old key reads, finds and reuses every customer.
- An induced duplicate makes the pre-flight abort with zero rows changed. An induced verification failure
  rolls back the page and aborts.
- With an idempotency row claimed under `k1` and `k2` now active:
  - a replay of the same body returns the stored response;
  - a different body gets `409`;
  - the report calls `k1`'s HMAC key not retirable until that row's `expires_at` passes. Advance the
    injectable clock to check.
- With the property absent, no runner bean exists and startup is unchanged.
- No log line contains a NIK, a phone or a full hash.

**Tests.**

1. Unit tests: the pre-flight duplicate detector, the per-row verifier, the retirement verdict.
2. **`PiiRekeyIT`:**
   - customers seeded under `k1` in both `v1` and `v2`;
   - dry run;
   - execute;
   - resume;
   - duplicate abort;
   - verification-failure rollback;
   - read-back with `k1` removed.
3. **`IdempotencyKeyRotationIT`:** replay, conflict and retirement across rotation, on the payment and
   contract-create endpoints.
4. `./gradlew check`.

**Risks.**

- This rewrites PII in place. The pre-flight abort and the per-row verification are the safety net. Run
  `DRY_RUN` in every environment first.
- Retiring a key early makes rows unreadable. The report's verdict is the gate; ADR-025's runbook forbids
  retiring on time alone.

## Completed task detail — Sprint 8b (Payments, Settlement & Write-off UI: T39)

Moved out of `tasks.md` on 2026-11-27. Sprint 8b is closed (T39 PR #33, including the PR review fix
`5be17f7`); this is its goal/exit header and the T39 ticket with its close-out note. The plan is
`docs/superpowers/plans/2026-11-27-t39-payments-settlement-writeoff.md`.

**Goal:** the operator can read a contract's payment history and run the two remaining servicing flows
(early settlement, write-off) from the contract detail screen.
**Scope (5 pts):** T39 — DONE.
**Exit:** `GET /api/v1/contracts/{id}/payments` is live behind the role matrix; the contract detail screen has a
payments tab, a settlement quote/execute modal and a write-off dialog; statement PAYMENT rows link to the
payment. Sprint 8b is the last planned sprint; no later story is planned yet.

> **Planned 2026-11-27.** Plan: `docs/superpowers/plans/2026-11-27-t39-payments-settlement-writeoff.md`
> (Decisions 1–13, Global Constraints, Review Focus).

### T39 — FE payments tab, settlement and write-off flows (5 pts)

Status: DONE
Estimate: 5 pts

Implementation note (2026-11-27, branch `t39-payments-settlement-writeoff`; plan
`docs/superpowers/plans/2026-11-27-t39-payments-settlement-writeoff.md`). Shipped in five tasks:
- **Backend:** `GET /api/v1/contracts/{id}/payments` in the `payment` module (`ContractPaymentController`,
  `ContractPaymentResponse`; `PaymentResponse` untouched), the existence check through the new
  `ContractLookupPort.requireExists`, and one matrix row (ADMIN, FINANCE; MANAJEMEN 403).
- **Frontend:** the Pembayaran tab with `?tab=` / `?payment=` handling and statement PAYMENT links
  (reversal rows included; SETTLEMENT rows stay unlinked); the settlement quote/execute modal; the write-off
  dialog with a trimmed reason (1-500 characters).
- **Decisions:** plan Decisions 1–13 held as planned; FE spec §2.4, §2.7 and §2.8 carry them.
- **Review fix:** "Ambil quote baru" could replace a "Coba lagi" retry still in flight; the re-quote is now
  withheld while an outcome is unknown (`fecc3f6`).
- **PR review fix (CodeRabbit, PR #33):** a 401 or 403 answering the retry of an unknown outcome no longer
  clears it, in the settlement, write-off and credit-apply dialogs alike (`isOutcomeUnknown` in
  `lib/idempotency`); FE spec §2.8 now times the countdown from the quote request, as the code does.
Verification (2026-11-27, local; GitHub CI is blocked by billing): backend `./gradlew check --rerun-tasks`
BUILD SUCCESSFUL, 150 suites / 1177 tests / 0 failed; frontend `npm test` 302 tests / 31 files / 0 failed,
`npm run lint`, `npm run typecheck` and `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 npm run build` green.

Goal: an ADMIN_OPERASIONAL or FINANCE operator sees every payment of a contract (POSTED and VOIDED, with
allocations, excess and void details), quotes and executes an early settlement, and writes a contract off
with a reason, all from the contract detail screen.
Scope:
- Backend: `GET /api/v1/contracts/{id}/payments` in the `payment` module (`ContractPaymentController`,
  `PaymentHistoryQueryService`, `ContractPaymentResponse`), `ContractLookupPort.requireExists`, one role-matrix
  row, OpenAPI, doc rows.
- Frontend: payments tab; settlement modal with countdown; write-off dialog; `?tab=` and `?payment=` deep links;
  statement PAYMENT rows link to the payment row.
Business Rules: all taken at planning, see plan Decisions 1–13, with ADR-018 (settlement), ADR-017
(idempotency) and Addendum §3.4 (roles); not restated here.
Implementation Notes: `contract` never imports `payment`, so the existence check goes through the new
`ContractLookupPort` in `contract.application`. The new endpoint adds exactly one default-deny matcher row
(ADR-015) and the same Addendum §3.4 and `EndpointRoleMatrixIT` rows. Money stays a decimal string end to end
(ADR-024). Task order and files: the plan.
Dependencies: T33, T12/T13, T19.
Acceptance Criteria:
- A settlement whose response is lost but which committed still offers "Coba lagi"; the retry replays with the
  same key and `quote_id` and ends in the success toast, even after the refetch shows CLOSED.
- The countdown starts at the server lifetime (`valid_until − quoted_at`) whatever the browser clock says, and
  execution disables at zero.
- With customer credit, cash due is `gross − credit`; credit equal to the gross shows Rp 0; credit above the
  gross blocks execution.
- A re-quote after `STALE_SETTLEMENT_QUOTE` or `SETTLEMENT_QUOTE_EXPIRED` sends a different `Idempotency-Key`
  with the new `quote_id`.
- Write-off reasons are validated and sent trimmed (whitespace only, 500 and 501 characters, surrounding
  spaces); a retry resends the reason unchanged.
- `GET /contracts/{id}/payments` answers 200 for ADMIN and FINANCE, 403 for MANAJEMEN, 404 `CONTRACT_NOT_FOUND`
  for an unknown contract.
Tests: `ContractPaymentHistoryIT` and `EndpointRoleMatrixIT` first; then the frontend unit and component tests
named in the plan; `./gradlew check` and `npm test`, `npm run lint`, `npm run typecheck`, `npm run build`.
Risks: re-estimated from 2 to 5 pts at planning (read model and role row 1, tab and links 1, settlement modal 2,
write-off dialog 1). The settlement modal carries the only browser-side money arithmetic (D5), so a mistake
there is a money-display error rather than a ledger error: the server stays the authority.
