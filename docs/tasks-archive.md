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

| #    | Discrepancy                                                                      | Resolution                                        |
| ---- | -------------------------------------------------------------------------------- | ------------------------------------------------- |
| X-1  | Payment retries optimistic-lock up to 3× then 409 `CONCURRENT_MODIFICATION`      | T5 (`PaymentRetryingService`); constant tidied T24 (CR-13) |
| X-2  | Daily jobs use ShedLock, retry per record up to 5×                               | T2/T3 (V9 lock, DB-time renewable lock)           |
| X-3  | `X-Request-Id`, JSON logs, `request_id` on `idempotency_keys`                    | Open → T20                                         |
| X-4  | DM §1.4 lacked `OVERDUE → PARTIALLY_PAID`                                         | A-5 → T1 (code wins; DM corrected)                |
| X-5  | Void catch-up delta when expected > recognized                                   | A-3 → T1 (ADR-012 d7: catch-up only for date-less rows); E4/T16 |
| X-6  | Penalty base = unpaid pokok+bunga only                                           | A-2 → T1 (ADR-012 d2 kept: paid penalty also reduces base); T10 DEFERRED |
| X-7  | `penalty_accrual` append-only / `days_late >= 1`                                 | T2 (V9 trigger + CHECK)                           |
| X-8  | Statement debit/credit per entry                                                 | A-8 → T1 (ledger-literal); built in T9            |
| X-9  | TS §7 repo/package names                                                         | Doc-only, DEFERRED                                |
| X-10 | Non-UUID `sub` fails closed                                                      | T7 (ADR-015 D5: 401 at decoder + defensive filter) |
| X-11 | Idempotency key "may be claimed again" after retention                          | T24 (ADR-017: single-use forever; 409 `IDEMPOTENCY_KEY_EXPIRED`) |
| X-12 | `system_parameter` append-only                                                   | T25 (V12 `block_modification()`)                  |
| X-13 | Statement query 500 on PostgreSQL (`42P18`)                                      | T29 (`cast(...)`-typed nullable binds)            |

---

## External review 2026-09-30 — CR-01 … CR-16 (resolved/mapped)

An external AI review of `848e8a3` reported 16 findings, renumbered CR-01…CR-16 to avoid colliding with the
2026-09-29 F-numbers. Each was checked against source, migrations, CI and the upstream compatibility
matrices on 2026-09-30 (suite not re-run for that check; last green run then was the T6 run, 460 tests).

| ID    | Finding                                                   | Verdict / task                                                                                   |
| ----- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| CR-01 | Non-UUID `sub` stays authenticated, writes as `SYSTEM`    | Fixed T7 (ADR-015 D5): decoder `sub`-is-UUID validator 401s; filter discards defensively         |
| CR-02 | ShedLock 6.9.0 on Boot 4.1.1                              | Unsupported pairing; fixed T23 (→ 7.10.1)                                                         |
| CR-03 | springdoc 2.8.9 on Boot 4                                 | Boot 4 needs springdoc v3; fixed T23 (→ 3.1.1), `OpenApiSmokeIT`                                  |
| CR-04 | Retention takeover vs permanent business-row keys         | Severity lowered (no double execution); A-13 option A → T24                                       |
| CR-05 | Ledger one-entry-per-event only in Java                   | Documented (ADR-008 d5); defense-in-depth DB guard T25, scoped to current ref types              |
| CR-06 | Zero-line entry / zero-allocation payment bypass V3       | V3 triggers child-only; parent-side checks T25                                                   |
| CR-07 | `job_run` stuck `RUNNING`                                 | Fixed T26 (`ABANDONED` + V13, loop-escape finalization)                                          |
| CR-08 | All ACTIVE ids loaded at once                             | Fixed T26 (keyset paging, zero-UUID sentinel, batch size)                                        |
| CR-09 | Daily retry without backoff                               | Fixed T26 (`Sleeper`, `{50,150,400,1000}` ms)                                                    |
| CR-10 | No `iss`/`aud`/active-user/role checks                    | Roles + `exp` fixed T7; `iss`/`aud`/active-user need a real issuer → T21                          |
| CR-11 | No key rotation                                           | Documented (ADR-007 consequences); → T28                                                          |
| CR-12 | `system_parameter` append-only by convention only         | Fixed T25 (V12 `block_modification()`; `AppendOnlyTestCleanup`)                                   |
| CR-13 | 50/150/400 ms documented, 50/150 ms real                  | `BACKOFF_MILLIS[2]` unreachable; corrected in the re-plan, constant tidied T24                    |
| CR-14 | Outstanding formula differs across read and write paths   | Dormant (no `penalty_adjustment` write path); → T15                                              |
| CR-15 | CI runs only `test` + compose build                       | → T23 (smoke IT) + T27                                                                            |
| CR-16 | Committed dev keys in Compose, no production guard        | Documented (ADR-005, README); → T21                                                              |

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
