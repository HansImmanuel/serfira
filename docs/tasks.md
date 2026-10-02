# Serfira tasks

Canonical implementation backlog. From Sprint 4b onward this file supersedes the per-sprint allocation in
`05_SPRINT_PLAN.md`. The story IDs used there (A1…G6) are kept as cross-references. Business rules stay in
`01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → ADRs. This file points to
those documents and does not restate them.

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`.
Estimates use the sprint plan's points (1 pt ≈ 2–3 h; target velocity 8–13 pts per sprint).

---

## Current Project State

**Snapshot:** `main` @ `0b282c0` plus the T7 implementation (ADR-015: RBAC matrix, roles-claim converter,
fail-closed JWT identity, `support/TestJwts`, retired audit probe), the T8 implementation (new read-only
`reporting` module with the aging report), the T9 implementation (contract statement read through a new
`ledger` statement port, V11 index), and the T24 implementation (ADR-017: idempotency keys are single-use
forever, 409 `IDEMPOTENCY_KEY_EXPIRED`, the payment classifier no longer retries `uq_payment_idempotency`,
`BACKOFF_MILLIS` tidied).

**Verified on 2026-10-02 (through T8):**

- `./gradlew compileJava compileTestJava`: pass.
- Narrowest T8 tests (`com.serfira.reporting.*`, `*EndpointRoleMatrixIT`): pass.
- Full `./gradlew test`: 72 suites / 570 tests pass.
- `./gradlew check`: pass.

**Verified on 2026-10-02 (T9) — Docker unavailable on the machine, so the Testcontainers `*IT` suites did
not run:**

- `./gradlew compileJava compileTestJava`: pass.
- `./gradlew test --tests "*Test"` (all unit tests): pass.
- `ContractStatementIT`, `LedgerAccountNameIT`, the updated `EndpointRoleMatrixIT`, and the full
  `./gradlew test`/`./gradlew check` still need a run with Docker up before T9 is counted toward the suite
  totals above.

**Verified on 2026-10-02 (T24) — Docker still unavailable, so the Testcontainers `*IT` suites did not run:**

- `./gradlew compileJava compileTestJava`: pass.
- `./gradlew test --tests "*Test"` (all unit tests): 43 suites / 335 tests pass, including the new
  `PaymentConflictClassifierTest` (7) and `PaymentRetryingServiceTest`.
- The rewritten `IdempotencyRetentionIT`, the new retention cases in `ContractIdempotencyIT` and
  `PaymentIdempotencyIT`, and the full `./gradlew test` / `./gradlew check` still need a run with Docker up
  before T9 and T24 are counted toward the suite totals above.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `shared`. `settlement` does not exist.
  `frontend/` is empty.
- Migrations V1–V11: full baseline schema, deferred accounting triggers (V3), state coherence (V4),
  settlement immutability (V5), contract create safety (V6/V7), hardened SYSTEM principal (V8), ShedLock +
  penalty-accrual integrity (V9), explicit `job_run.business_date` for backfill audit (V10), and the
  `journal_line (contract_id, entry_date)` index for the contract statement (V11, T9).
- Daily servicing T3 is live: configurable Jakarta cron and explicit backfill entry share one renewable
  ShedLock; every ACTIVE contract is processed atomically in billing → penalty order with five-attempt
  conflict retry, failure isolation, SYSTEM audit, and step-level `job_run` rows.
- Lazy payment accrual T4 is live: `POST /api/v1/payments` calls `PenaltyAccrualPort` after billing and before
  snapshot/allocation/resolution inside the idempotency supplier, using one captured business date. Replay
  skips the supplier and a failed attempt rolls back accrual, journals, payment writes, resolution, and the
  claim together (ADR-014).
- Payment conflict retry T5 is live: `PaymentController` calls `PaymentRetryingService`, which retries
  `PaymentApplicationService.create` up to 3 attempts (pauses of 50 and 150 ms between them) on optimistic-lock or
  `uk_penalty_accrual` conflicts, each attempt in a fresh transaction with a fresh idempotency claim.
  Exhausted retries return 409 `CONCURRENT_MODIFICATION` (ADR-014 decision 7).
- Aging step T6 is live: after each contract's billing → penalty transaction, the daily job runs a separate
  aging transaction through `contract.application.InstallmentAgingPort` that marks `PENDING`/`PARTIALLY_PAID`
  installments `OVERDUE` from `due_date + grace + 1` while `outstanding > 0`, with its own five-attempt retry
  and its own `aging` `job_run` row (ADR-013 implementation note T6).
- Aging report T8 is live: `GET /api/v1/reports/aging` in a new read-only `reporting` module returns the
  Current/1–30/31–60/61–90/>90 buckets as a portfolio total and per contract (per-installment basis,
  ACTIVE-only, `as_of` today-only → 400 `INVALID_AS_OF_DATE`). The DPD and outstanding formulas stay in
  `contract` and are read through `contract.application.AgingReportSourcePort`; penalty is included,
  penalty adjustments are not (no E5 yet). ADR-013 implementation note T8.
- Statement T9 is live: `GET /api/v1/contracts/{id}/statement` returns the contract's posted journal lines,
  oldest first, in the `{data, error}` envelope. The endpoint is in `contract` (existence check → 404
  `CONTRACT_NOT_FOUND`); the read goes through the new read-only `ledger.application.ContractStatementPort`
  (the existing `contract → ledger` edge, no new edge). Ledger-literal (ADR-013 A-8): one row per
  `journal_line`, `debit`/`credit` the posted amounts, no running balance; reversals surface as their own
  rows flagged `is_reversal`. Optional `ref_type` and inclusive business-zone `from`/`to` filters, chrono
  order, `page`/`size` paging; a DRAFT contract → empty. Roles ADMIN_OPERASIONAL/FINANCE (MANAJEMEN → 403).
  V11 adds the `journal_line (contract_id, entry_date)` index. The account name is read from
  `LedgerAccount.displayName()` (not an `accounts` join), pinned to the V1 seed by `LedgerAccountNameIT`.
  ADR-013 implementation note T9.
- Idempotency lifecycle T24 is live (ADR-017, amends ADR-007 d9, option A): an `Idempotency-Key` is
  single-use forever per endpoint and retention only bounds replay. `IdempotencyService` drops the expiry
  takeover; a reused key past its window is 409 `IDEMPOTENCY_KEY_EXPIRED` (new `ErrorCode`) before the
  supplier runs, so no billing/accrual/business write happens, whatever the body. After T18 deletes the
  claim row the permanent backstops give the same code (`uq_contract_idempotency`,
  `uq_payment_idempotency`). `PaymentConflictClassifier` is constraint-aware: only `uk_penalty_accrual` and
  optimistic-lock conflicts are retried, never `uq_payment_idempotency` (CR-04). `PaymentRetryingService`'s
  `BACKOFF_MILLIS` is `{50, 150}` (the unreachable 400 ms slot removed, CR-13). No migration.
- Endpoints: `POST /api/v1/contracts`, `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, and `GET /api/v1/reports/aging`.
- Security: JWT HS256 resource server, default-deny, with the Addendum §3.4 **role matrix enforced** on all
  eight existing endpoints (T7/T8/T9, ADR-015). The statement is ADMIN_OPERASIONAL/FINANCE only (MANAJEMEN
  → 403). A token's `sub` must be a UUID and it must carry `exp`. There is still no login/refresh/logout and
  no `iss`/`aud` validation (ADR-005; T21).

**Current sprint:** Sprint 4 ("Penalty, Aging & Phase-1 Close") is partly done. C4/D1 and re-planned
T1–T6 are DONE, so Sprint 4b is complete. Sprint 4c (T23, T7, T8, T9) is complete. **Sprint 4d** is in
progress: T24 (idempotency key lifecycle) is DONE; T25 (DB accounting backstops), T26 (daily job
robustness) and T11 (exit verification) remain.

**Blockers and critical gaps:** none open. The two that remained after T6 (the unenforced Addendum §3.4
matrix, and a non-UUID `sub` writing as `SYSTEM`) were closed by T7 (ADR-015).

**Specification/implementation discrepancies found:**

| #    | Documented                                                                                                                         | Implemented                                                                                                                                                                                                                                                                | Resolution                          |
| ---- | ---------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| X-1  | Addendum §5: payment write path retries optimistic-lock failures up to 3× with a small backoff, then 409 `CONCURRENT_MODIFICATION` | Resolved by T5: `PaymentRetryingService` makes 3 attempts with 50 and 150 ms pauses via an injectable `Sleeper`, no `spring-retry` dependency added. The unreachable 400 ms entry in `BACKOFF_MILLIS` was removed in T24 (now `{50, 150}`), so code and docs agree (CR-13) | DONE (constant tidied in T24)       |
| X-2  | TS §2.3 / Addendum §5: daily jobs use ShedLock and retry per record up to 5×                                                       | Resolved by T2/T3: V9 lock table; DB-time renewable lock; per-contract atomic billing→penalty with five total attempts                                                                                                                                                     | DONE                                |
| X-3  | Addendum §10: `X-Request-Id`, JSON logs, `request_id` stored on `idempotency_keys`                                                 | None. `idempotency_keys.request_id` is not mapped by `IdempotencyKey`. No story in `05_SPRINT_PLAN.md` owns this                                                                                                                                                           | T20 (Sprint 6)                      |
| X-4  | DM §1.4 state machine has no `OVERDUE → PARTIALLY_PAID` edge                                                                       | `Installment.applyPayment` moves OVERDUE + partial → `PARTIALLY_PAID`                                                                                                                                                                                                      | Ambiguity A-5 → T1                  |
| X-5  | TS §4.3 / Addendum §6: after a void, add a catch-up delta when expected > recognized                                               | ADR-012 decision 7 re-charges only dates that have **no** row yet. Dates already accrued at a reduced base are never re-priced, and `uk_penalty_accrual` allows only one row per date                                                                                      | Ambiguity A-3 → T1, E4              |
| X-6  | TS §4.3: base is unpaid pokok+bunga                                                                                                | ADR-012 decision 2: penalty already paid also reduces the base (`InstallmentBalance.penaltyBase`)                                                                                                                                                                          | Ambiguity A-2 → T1, T10             |
| X-7  | DM §1.9 / ADR-012: `penalty_accrual` is append-only                                                                                | Unlike its sibling append-only tables, it has no immutability trigger. `ck_penalty_accrual_days` allows `0` while the application requires ≥ 1                                                                                                                             | T2                                  |
| X-8  | `06_FRONTEND_SPEC.md §2.7`: statement columns "Debit / Kredit sum per entry"                                                       | Resolved by T1 (A-8, ADR-013 d7) as ledger-literal: debit/credit are per-`journal_line` posted values, no running balance; built in T9                                                                                                                                     | DONE (A-8 → T1, built in T9)        |
| X-9  | TS §7: repo `serfira-core/`, package `com.multifinance`                                                                            | Repo `serfira/backend`, package `com.serfira`                                                                                                                                                                                                                              | Doc-only fix, deferred              |
| X-10 | ADR-005 decision 3 / `AuditActorBindingFilter` Javadoc: a non-UUID `sub` fails closed and authorization rejects the request        | Closed by T7 (ADR-015 D5): the decoder rejects such a token (401), and the filter now discards the authentication defensively                                                                                                                                              | T7                                  |
| X-11 | `IdempotencyService` Javadoc: after retention "the key may be claimed again" (cites ADR-007 decision 9)                            | Resolved by T24 (ADR-017): the takeover is removed, the Javadoc now describes single-use-forever semantics, and a reused expired key is 409 `IDEMPOTENCY_KEY_EXPIRED` before the operation runs                                                                            | DONE (A-13 option A → T24, ADR-017) |
| X-12 | V1 comment / Addendum §1.2: `system_parameter` is append-only                                                                      | No trigger blocks UPDATE/DELETE (CR-12)                                                                                                                                                                                                                                    | T25                                 |

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
| —     | Phase A hygiene: V8 SYSTEM hardening, idempotency retention takeover (semantics revisited in T24), open-in-view off                                  | DONE   | `cdce254`                      |

---

## Sprint 4b — Phase-1 Close A: Penalty Correctness & Daily Job

**Goal:** Penalty is charged every day by an auditable job and by the payment path, with no loss when a
payment comes before the job, and aging states are maintained.
**Scope:** T1–T6 = 11 pts. **Exit:** PRD scenario 2 passes end-to-end with no manual accrual call. A second
run for the same business date writes nothing. `job_run` rows are written for every step.

### T1 — Resolve Phase-1 specification decisions

Status: DONE
Estimate: 1–2 pts

Implementation note (2026-09-29): Recorded in `ADR-013-phase1-close-semantics.md` (Accepted, amends
ADR-012). All of A-1…A-10 resolved: A-1 accepted as the accrue-before-resolve invariant (binds T3/T4);
A-2/A-3 keep ADR-012 decisions 2/7 unchanged, no schema change (T10 moved from BLOCKED to DEFERRED); A-4/A-5
defined DPD-start = first chargeable penalty day, and OVERDUE + partial → PARTIALLY_PAID (code wins over the
old DM diagram, which is corrected); A-6 as_of is today-only, historical `as_of` rejected 400
`INVALID_AS_OF_DATE`; A-7 buckets are per-installment, summed at contract/portfolio level, ACTIVE-only scope;
A-8 statement is ledger-literal (no running balance); A-9 `job_run` is one row per job step per business
date, `records_processed`/`records_failed` count contracts, status COMPLETED/FAILED only; A-10 confirmed, no
code change. Derived docs updated in the same change: `02_TECH_SPEC.md` §4.3, `03_DOMAIN_MODEL.md` §1.4,
`04_GAPS_ADDENDUM.md` §6/§10/§14, `06_FRONTEND_SPEC.md` §2.7/§2.9, and `05_SPRINT_PLAN.md` §5 Sprint 4
(pointer to this file, per T1.e). No code or migration changed by this task.

Goal: Get owner decisions on the ambiguities that block T6, T8, T9 and T10 (and later E4), recorded in the
authoritative documents before dependent code is written.

Scope:

- **T1.a Penalty semantics (ADR-013, amends ADR-012).** Decide A-1 (the base used to charge date D), A-2
  (whether penalty paid reduces the base), A-3 (how a void re-prices dates already accrued at a reduced
  base, given `uk_penalty_accrual`), and A-10 (no accrual after maturity close). Record the
  **accrue-before-resolve invariant** (see Planning Notes) if it is accepted.
- **T1.b Aging definition.** Decide A-4 (when DPD starts, when an installment becomes OVERDUE), A-5 (OVERDUE
  plus a partial payment), A-6 (`as_of` semantics), and A-7 (bucket basis and contract scope).
- **T1.c Statement semantics.** Decide A-8: which accounts appear, what debit/credit mean from the
  contract's point of view, and whether there is a running balance.
- **T1.d Job accounting.** Decide A-9: `job_run` granularity, the units of `records_processed` and
  `records_failed`, and the final status after a partial failure.
- **T1.e** Update `05_SPRINT_PLAN.md §5 Sprint 4` so it points to this file for the re-planned work.

Business Rules: None created here. Only choices between documented alternatives.

Implementation Notes: Update TS §4.3, DM §1.4/§1.9 and Addendum §6/§10/§14 together with the ADR. Per
`80-documentation`, the ADR alone is not enough. Mark each resolved ambiguity in Planning Notes with its
reference.

Dependencies: none.

Acceptance Criteria:

- ADR-013 (penalty) is Accepted. Aging, statement and job-accounting decisions are recorded in an ADR or in
  the relevant spec section.
- A-1 … A-10 are each marked resolved or explicitly deferred, with the tasks they affect.
- No spec section contradicts another on the decided points. In particular, TS §4.3 and ADR-012/013 agree
  on void catch-up.

Tests: None (documentation). Verification is a cross-document consistency check against the table in
Planning Notes.

Risks: If A-3 is decided as "re-price already-accrued dates", the penalty schema must change (a second
representation per date). That changes E4's estimate and may add a migration.

### T2 — Migration V9: job lock table and penalty-accrual integrity

Status: DONE
Estimate: 1 pt

Implementation note (2026-09-29): Added `V9__job_lock_and_penalty_accrual_integrity.sql`: creates `shedlock`
with the exact PostgreSQL schema documented by `shedlock-provider-jdbc-template` (`name` PK, `lock_until`,
`locked_at`, `locked_by` — no audit columns, this table is owned by the library); attaches
`trg_penalty_accrual_immutable` (`block_modification()`) for UPDATE/DELETE on `penalty_accrual`; tightens
`ck_penalty_accrual_days` to `days_late >= 1`. `PenaltyAccrual.version` was kept mapped (not removed) with a
Javadoc note explaining it is harmless — the entity has no setters and the service only ever inserts new
rows. Added tests to `BaselineSchemaIT` (`shedlock` in `allDomainTablesExist`) and `AccountingInvariantsIT`
(new "V9" section: `penaltyAccrualCannotBeUpdatedOrDeleted`, `penaltyAccrualRejectsDaysLateBelowOne`,
`shedlockTableExistsWithTheProvidersExpectedColumns`).

Goal: Provide the schema the daily job needs, and make `penalty_accrual` as immutable as the documents say.

Scope:

- Create the ShedLock JDBC provider table, using the schema documented for the ShedLock version pinned in
  `build.gradle.kts` (6.9.0).
- Attach `block_modification()` to `penalty_accrual` for UPDATE and DELETE (same as `penalty_adjustment`,
  V1).
- Tighten `ck_penalty_accrual_days` to `days_late >= 1`, matching `PenaltyAccrual` and `PenaltyCharge`.

Business Rules:

- Invariant 8 and ADR-012 decision 5: accrual rows are append-only, and reductions are `penalty_adjustment`
  rows (E5).
- Forward-only migration. Never edit V1–V8.

Implementation Notes: `PenaltyAccrual` maps `@Version` on a table that can no longer be updated. Remove the
mapping or document why it is harmless. Check the ShedLock table definition against current docs
(Context7). The migration must apply cleanly to a database that already holds accrual rows.

Dependencies: none.

Acceptance Criteria:

- V9 applies on a clean database and on a database migrated to V8 that already holds accrual rows.
- UPDATE and DELETE on `penalty_accrual` are rejected. An insert with `days_late = 0` is rejected.
- The ShedLock table exists with the provider's expected columns.

Tests:

- DB IT (`BaselineSchemaIT`/`AccountingInvariantsIT`): UPDATE/DELETE on `penalty_accrual` throw. Insert
  with `days_late = 0` throws. The lock table exists.
- `PenaltyAccrualIT` and the full `./gradlew test` stay green.

Risks: None of note. The trigger does not fire on `TRUNCATE`, which the IT cleanup relies on (V3 header).

### T3 — Daily servicing job: billing → penalty accrual (D2, part 1)

Status: DONE
Estimate: 3 pts

Implementation note (2026-09-29): Added a contract-owned ACTIVE-id/status port; a separate transactional
per-contract processor that joins existing billing and penalty ports in mandatory order; and a
non-transactional batch orchestrator with fixed-date validation, SYSTEM audit binding, five-total-attempt
retry for optimistic-lock/SQLSTATE `23505` conflicts, non-ACTIVE skip, and per-contract failure isolation.
Cron and explicit backfill use the same public `LockedDailyServicingJob` ShedLock boundary. The JDBC lock
uses database time plus ShedLock 6.9 `KeepAliveLockProvider`; cron is configurable and disabled in tests.
`JobRunService` writes `billing` and `penalty-accrual` invocation rows in `REQUIRES_NEW` transactions. V10
adds/backfills non-null `job_run.business_date` for auditable backfills. Documentation synchronized in TS
§1/§2.3, DM job metadata, Addendum §5/§7.3/§10, and ADR-013 A-9. Verification: focused unit tests and T3
Testcontainers ITs passed; full `./gradlew test` = 56 suites / 411 tests / 0 failures; `./gradlew check`
passed.

Goal: Recognize due interest and accrue penalty for every ACTIVE contract once per business date,
reliably, observably, and on one instance only.

Scope:

- A scheduled job, locked with ShedLock, that iterates ACTIVE contracts and runs `billDueInterest` and then
  `accrueDuePenalty` for each one.
- `job_run` recording, following T1.d.
- A job entry point that accepts an explicit business date, for tests and backfill. No HTTP endpoint
  (Addendum §3.3: jobs run in-process as `SYSTEM`).

Business Rules:

- **Business date** `D = clock.today()` (Asia/Jakarta), fixed once at the start of the run. Never run for
  `D > clock.today()`.
- **Per-contract atomicity:** billing and accrual for one contract commit together or not at all. Accrual
  for a contract must **never** commit without billing through `D`. Otherwise the base misses recognized
  interest and that day is under-charged permanently.
- **Idempotency:** a second run for the same `D` writes no accrual row, billing entry or journal. A rerun
  after a crash processes only the missing work.
- **Failure isolation (Addendum §5):** an optimistic-lock or uniqueness conflict on one contract is retried
  up to 5 times inside the run. After that, the contract counts as failed, an ERROR is logged with the
  contract id (never PII), and the batch continues.
- A contract that stops being ACTIVE during the run is skipped, not counted as failed.
- **Single instance:** a concurrent second invocation does not process contracts (ShedLock).
- **Audit actor:** every row written by the job has `created_by = SYSTEM` (`AuditContext.SYSTEM_USER_ID`).
- Order within the daily job follows Addendum §7.3: billing → penalty → aging (T6) → consistency check (F1,
  later).

Implementation Notes:

- `job_run` rows are written in their own transactions so they survive per-contract rollbacks.
- The orchestrator must not query `contract` tables from another module. Listing ACTIVE contracts needs a
  `contract` application port.
- The schedule (cron) is a property and is disabled in tests.
- Verify Spring Boot 4.1 + ShedLock 6.9 wiring against current docs (Context7).
- For long backlogs, batch the accrual and journal writes. Today each day costs one save, one existence
  query and one flush (review F-06).

Dependencies: T2 (lock table). T1.d (job_run semantics).

Acceptance Criteria:

- A run for `D` over ACTIVE contracts produces exactly the billing entries and accrual rows the existing
  ports produce when called directly, plus `job_run` rows per T1.d.
- A rerun for the same `D` writes zero new financial rows.
- A forced failure on one contract leaves the other contracts committed, and `records_failed` reflects it.
- Two concurrent invocations → only one processes contracts.
- DRAFT, CLOSED and TERMINATED contracts are never processed.

Tests:

- Unit: the orchestrator runs billing before accrual. Accrual is not committed when billing fails. The retry
  count is bounded at 5.
- IT: three contracts (overdue, current, DRAFT) → correct rows. Rerun same `D` → no new rows. Backfill run
  for `D` and then `D+3` → only the missing days are added. One contract made to fail → the others commit
  and `job_run` is correct. Two concurrent triggers → one run. `created_by = SYSTEM` on accrual and journal
  rows.

Risks: Long transactions for long-overdue contracts. Lock contention with payments on the same contract,
handled by T5.

### T4 — Lazy penalty accrual in the payment path (D2, part 2)

Status: DONE
Estimate: 2 pts

Implementation note (2026-09-30): Accepted in `ADR-014-lazy-penalty-accrual-in-payment-path.md` and
implemented through the one-way `payment → penalty.application.PenaltyAccrualPort` edge; payment never
touches penalty persistence. Inside the idempotency supplier, one captured business date drives billing →
accrual → snapshot → allocation → resolution → payment journal in the same transaction. A completed replay
skips every financial step; a failure rolls back the claim, billing/accrual rows and journals,
payment/allocation, installment resolution, and payment journal together. The HTTP ITs cover grace (no
accrual), first chargeable day (`1,573.33`), 28-day catch-up (`44,053.24`, total payment `1,617,386.57`),
penalty-first allocation, replay across the next chargeable date, multi-installment resolution, rollback,
authenticated audit actor, and maturity close. Verification: `compileJava compileTestJava` pass;
`PaymentApiIT` 17 pass; full `test --rerun` 413 pass; `check` pass. No schema or API/OpenAPI change was
required.

Goal: Make sure no payment can resolve an installment before the penalty for already-elapsed days has been
recognized. This closes ADR-012 residual risk (i).

Scope: Inside the `POST /payments` operation, the order becomes: bill through `D` → **accrue through `D`** →
load snapshot → allocate → resolve → journal. Record the decision (ADR-012 deferred it to D2).

Business Rules:

- The charge for date `D` uses the base at the **start** of `D`, before any resolution on `D` (A-1; confirm
  in T1.a). This is the same rule the job applies.
- Accrual runs inside the idempotent operation. An identical retry replays the stored response and must
  **not** accrue again (`IdempotencyService` never invokes the operation on replay).
- A failed payment (validation, conflict, state) rolls back its accrual rows and journals together with
  everything else.
- The penalty-first waterfall now applies to penalty accrued in the same transaction (PRD P-2, scenario 2).
- Maturity close runs only after the final payment has covered the penalty accrued through `D` (A-10).

Implementation Notes:

- `payment` already depends on `contract` ports and on `ledger`. Calling `penalty` is a **new
  module edge** (`payment → penalty`, application interface). Record it in TS §1 per `10-architecture`.
- Expected values in `PaymentApiIT` will change. Re-derive and document each changed number. Do not just
  update assertions until they pass (`50-testing`).

Dependencies: T1.a (confirm A-1 and A-10). T3 is not required but shares the ADR.

Acceptance Criteria:

- A late installment paid in full with no prior job run ends with its chargeable days accrued, a `PENALTY`
  allocation, and a `PENALTY_ACCRUAL` journal per day.
- A replay produces no additional accrual rows.
- A payment rejected after the claim leaves no accrual rows.

Tests:

- IT (HTTP): PRD scenario 2 with no manual `accrueDuePenalty`. Payment within grace → no accrual. Payment on
  the first chargeable day → exactly one row. Replay → no new rows. Rolled-back payment → no rows. Final
  payment → contract CLOSED only after the penalty is paid.
- Regression: the former manual accrual priming is absent; `PaymentApiIT` keeps the end-to-end late-payment
  scenario green through the real payment path.

Risks: A job-vs-payment race on the same `(installment_id, accrual_date)` makes the loser fail. T5 is
required before this runs under load.

### T5 — Payment write-path conflict retry (Addendum §5)

Status: DONE
Estimate: 2 pts

Implementation note (2026-09-30): Added `PaymentRetryingService` (`payment.application`), a
non-transactional bean that wraps `PaymentApplicationService.create` from outside its `@Transactional`
boundary; `PaymentController` now depends on it instead of the application service directly. Each attempt
calls `create` fresh, so it opens its own transaction and takes a new idempotency claim
(`IdempotencyService` stays `Propagation.MANDATORY`) — a failed attempt never consumes the key. Retryable
failures are classified by a payment-owned `PaymentConflictClassifier` (package-private, mirrors
`penalty.application.DailyServicingConflictClassifier`'s optimistic-lock/SQLSTATE `23505` rule; kept
separate rather than shared because `penalty`'s classifier is package-private and the two call sites don't
justify a `shared` abstraction for ~15 lines). Policy: 3 attempts total, pauses of 50 and 150 ms (the
listed 400 ms is never reached with 3 attempts; corrected 2026-09-30, CR-13) via an
injectable `Sleeper` (`shared.concurrency`, mirrors `ClockConfiguration`'s `@ConditionalOnMissingBean`
pattern) — no `spring-retry` dependency added. Exhausted retries throw
`PaymentConflictRetriesExhaustedException` (extends `SerfiraException`, 409 `CONCURRENT_MODIFICATION`);
`GlobalExceptionHandler` needed no change since `handleSerfira` already reads `ex.status()`/`ex.code()`
dynamically. Verification: `PaymentRetryingServiceTest` (5 unit tests, injected fake `Sleeper`) covers the
attempt count, the documented backoff sequence, and that validation/business-state errors pass through
unretried. `PaymentConflictRetryIT` forces both races deterministically against Testcontainers PostgreSQL:
one test parks the payment's own accrual save right after it reads "nothing accrued yet" via a JDK dynamic
proxy over `PenaltyAccrualRepository`, lets a concurrent "job" transaction commit its own
`(installment_id, accrual_date)` row, then releases — producing a real SQLSTATE `23505` collision that the
retry recovers from with exactly one accrual row and one payment; the other forces a persistent conflict
through 3 exhausted attempts, asserts 409 `CONCURRENT_MODIFICATION`, confirms the key stayed unclaimed, and
then succeeds on a plain retry. Full `./gradlew test --rerun` = 420 tests (413 + 7); `./gradlew check`
passed. No schema or API/OpenAPI change was required.

Goal: Concurrent payment and job activity on one contract resolves correctly without surfacing avoidable
409s, as documented.

Scope: Retry the whole payment transaction up to 3 attempts (pauses of 50 and 150 ms) on optimistic-lock failure
**and** on `uk_penalty_accrual` uniqueness conflicts. After retries are exhausted → 409
`CONCURRENT_MODIFICATION`.

Business Rules:

- Each attempt is a fresh transaction, including the idempotency claim. A failed attempt must not consume
  the key.
- Money is never received twice. After any interleaving: `Σ allocations = payment.amount`,
  `penalty_amount = Σ penalty_accrual.amount`, one accrual row per `(installment, date)`.
- Validation and business-state errors (400/404/409 `CONTRACT_STATE_INVALID`) are **not** retried.

Implementation Notes: The retry must wrap the `@Transactional` boundary, not sit inside it. A new dependency
(`spring-retry`) needs justification per `35-coding-standards §18`. A small hand-written loop with an
injectable sleeper is an acceptable alternative.

Dependencies: T3, T4.

Acceptance Criteria:

- A payment racing the job for the same contract and `D` succeeds after a retry, and the invariants above
  hold.
- Exhausted retries return 409 `CONCURRENT_MODIFICATION` in the standard envelope.

Tests:

- Unit: the retry policy (attempt count, backoff sequence, non-retryable exceptions) with an injected
  sleeper.
- IT (concurrency, `CountDownLatch`): payment vs job accrual on the same contract. Two payments with
  different keys on the same contract. Assert the specific exception types, not `isNotNull()`.
- IT: forced repeated conflict → HTTP 409 `CONCURRENT_MODIFICATION`, no payment row, key still reusable.

Risks: Retrying a non-idempotent side effect. None exists today, but keep the operation free of external
calls.

### T6 — Aging status step (D2, part 3)

Status: DONE
Estimate: 2 pts

Implementation note (2026-09-30): Recorded as ADR-013 "Implementation Note — T6"; TS §1/§2.3, DM §1.4 and
Addendum §5/§7.3/§10 synchronized. The transition lives in `contract`: `Installment.markOverdue(D, grace)`
moves only `PENDING`/`PARTIALLY_PAID` with `outstanding > 0` to `OVERDUE` from
`InstallmentAging.firstOverdueDate` (= `due_date + grace + 1`) onwards; every other case touches no field, so
an already-`OVERDUE` installment is never rewritten and `PAID`/`SETTLED`/`WRITTEN_OFF` never change.
`InstallmentAgingPort`/`InstallmentAgingService` apply it per contract with `Propagation.MANDATORY` (a call
without a transaction fails instead of silently losing the change). The job calls it through a new
`@Transactional DailyServicingContractProcessor.age`, a separate transaction after billing → penalty that
still runs when billing → penalty failed; a contract already observed non-ACTIVE is skipped.
`DailyServicingOrchestrator` now runs both steps through one step-generic retry (five attempts, same
conflict classifier) and writes a third `job_run` row, `aging`, with its own counters. The formula is kept in
`contract.domain` (no `contract → penalty` edge) and pinned to `PenaltyTerms` by `AgingPenaltyParityTest`. No
migration, API or dependency change. Verification: unit `InstallmentAgingTest` (11), `InstallmentMarkOverdueTest`
(11, every source status incl. the boundary day), `AgingPenaltyParityTest` (2), orchestrator/processor tests
(+6); Testcontainers `InstallmentAgingServiceIT` (4: boundary, rerun keeps `version`, resolved states untouched,
guards) and `DailyAgingJobIT` (6: boundary via the locked job, rerun no version change, partial payment on
`OVERDUE` → `PARTIALLY_PAID` → next run `OVERDUE`, full payoff 1,574,906.66 → `PAID` stays `PAID`,
`SETTLED`/`WRITTEN_OFF` untouched, only ACTIVE aged). `DailyServicingJobIT`/`LockIT`/`TransactionIT` updated for
three `job_run` rows per invocation (structural, not a financial value); `TransactionIT` also proves a
contract whose billing → penalty exhausted its retries is still aged. Full `./gradlew test --rerun` = 460
tests (420 + 40); `./gradlew check` pass.

Goal: Keep installment aging states current as the third step of the daily job.

Scope: Mark installments `OVERDUE` for business date `D` according to T1.b, and record the step in
`job_run`.

Business Rules:

- DM §1.4: `PENDING/PARTIALLY_PAID → OVERDUE` by the job. `SETTLED`, `WRITTEN_OFF` and `PAID` are never
  changed by aging.
- The overdue condition and the OVERDUE + partial behavior come from T1.b (A-4, A-5). Do not choose them
  here.
- Idempotent: an installment already `OVERDUE` is not written again (no version bump).
- The status transition belongs to the `contract` module. The job calls a `contract` port and never writes
  `installment` from outside.
- V4 coherence: `OVERDUE` may carry `paid_at`.

Implementation Notes: The aging report (T8) must not depend solely on status for DPD, because a partial
payment can temporarily move an installment out of `OVERDUE` (current code).

Dependencies: T1.b, T3.

Acceptance Criteria:

- After a run for `D`, every installment meeting the T1.b condition is `OVERDUE`, and no other status
  changed.
- A rerun writes nothing.
- A full regular payment moves `OVERDUE → PAID` (existing behavior, now covered by a test).

Tests:

- Unit: the transition table for every source status, including the boundary day (the day the condition
  first holds).
- IT: the job marks the right installments. Rerun → no version change. Partial payment on `OVERDUE` →
  behaves per A-5. Settled and written-off installments are untouched.

Risks: More installment version bumps increase payment contention. Covered by T5.

---

## Sprint 4c — Phase-1 Close B: Stack Alignment, Access Control & Phase-1 Reads

**Goal:** The runtime stack is on the Boot 4 compatibility lines, every endpoint enforces the role matrix
with a fail-closed JWT identity, and aging plus the statement are available.
**Scope:** T23 (1 pt, DONE), T7 (3 pts, DONE), T8 (2 pts, DONE), T9 (2 pts, DONE) = 8 pts, all DONE.
Order: T23 → T7 → T8/T9 (T8 and T9 are born with their role rules and 403 tests). **Exit:** the Addendum
§3.4 rows for existing endpoints hold (met by T7), and `/v3/api-docs` is covered by a test (met by T23).

### T23 — Spring Boot 4 dependency alignment (review CR-02, CR-03)

Status: DONE
Estimate: 1 pt

Implementation note (2026-10-01): Implemented in PR #1 (`d5de2ea`, merged as `0b282c0`), verified here
because that commit ran no Testcontainers ITs. `shedlock-spring` and `shedlock-provider-jdbc-template` are
pinned to `7.10.1`, and `springdoc-openapi-starter-webmvc-ui` to `3.1.1`. `JobSchedulingConfiguration`
compiles unchanged against the 7.x API (`JdbcTemplateLockProvider` with `usingDbTime()`,
`KeepAliveLockProvider`, `@EnableSchedulerLock`). The 7.10.1 README documents the same PostgreSQL DDL as V9
(`name` VARCHAR(64) PK, `lock_until`/`locked_at` TIMESTAMP NOT NULL, `locked_by` VARCHAR(255) NOT NULL), so no
migration was added. The V9 header still cites `6.9.0` and is left as is, because applied migrations are
never edited. The springdoc 3.x default paths are unchanged, so ADR-005's `PUBLIC_PATHS` still cover them.
New `OpenApiSmokeIT` (3 tests): `/v3/api-docs` answers 200 without a token and lists `/api/v1/payments` and
`/api/v1/contracts`; `/swagger-ui.html` resolves and `/swagger-ui/index.html` answers 200 without a token.
Verification: `compileJava compileTestJava` pass; `OpenApiSmokeIT` + `DailyServicing*IT` 10 pass; full
`test --rerun` = 64 suites / 463 tests (460 + 3); `check` pass. No behavior change.

Goal: Run the scheduler lock and the OpenAPI surface on library lines that are tested with Spring Boot 4,
before T7 adds security tests against the public OpenAPI paths.

Scope:

- ShedLock `6.9.0` → the current 7.x release (`shedlock-spring`, `shedlock-provider-jdbc-template`). The
  ShedLock README compatibility matrix lists 7.x as tested with Spring Boot 4.x and 6.x only up to Boot 3.5.
- springdoc `2.8.9` → the current 3.x release of `springdoc-openapi-starter-webmvc-ui`. The springdoc README
  says Boot 4 needs springdoc v3 (the major version moves in lockstep with Boot).
- Pin exact versions (look them up on Maven Central at implementation time). Update the tech steering file
  (`ShedLock 6.9`, `springdoc-openapi 2.8`) in the same change.

Business Rules: None. Behavior must not change.

Implementation Notes:

- Check the 7.x release notes for API or package moves around `@EnableSchedulerLock`, `JdbcTemplateLockProvider`
  (`usingDbTime()`) and `KeepAliveLockProvider`. The V9 `shedlock` table must still match the provider's
  documented schema. If it does not, add a new migration and never edit V9.
- Check springdoc 3.x property names and the default paths (`/v3/api-docs`, `/swagger-ui.html`). ADR-005's
  `PUBLIC_PATHS` must still cover them.
- Use Context7 for both (`00-core`).

Dependencies: none.

Acceptance Criteria:

- `DailyServicingLockIT`, `DailyServicingJobIT` and the full suite are green.
- `GET /v3/api-docs` returns 200 without a token and lists `/api/v1/payments` and `/api/v1/contracts`.
  `GET /swagger-ui.html` resolves (200 or a redirect to the UI) without a token.

Tests:

- New `OpenApiSmokeIT` (Testcontainers context): the two requests above. This is the first test that
  catches a runtime springdoc incompatibility.
- `./gradlew test` and `./gradlew check`.

Risks: A ShedLock major upgrade can change lock-table expectations. The V9 schema check above covers it.

### T7 — RBAC enforcement and JWT identity hardening (F3 pulled forward from Sprint 6b; review CR-01, CR-10)

Status: DONE
Estimate: 3 pts (was 2; the CR-01 fix and token-claim validation were added)

Implementation note (2026-10-01): Accepted as ADR-015 (amends ADR-005 d3/d5). Authorities come from
`RolesClaimAuthoritiesConverter` reading the `roles` array (`AppRole` mirrors `ck_app_user_role`); a `SYSTEM`
element withholds every authority (D4). The decoder validator is `JwtValidators.createDefault()` plus a
`JwtTimestampValidator` with `allowEmptyExpiryClaim=false` (Spring Security 7 still defaults it to true, so a
no-`exp` token would otherwise pass) plus a `sub`-is-UUID `JwtClaimValidator`, so a token that names no actor
is 401 before it authenticates (D5, CR-01). The Addendum §3.4 matrix is one matcher table in
`ResourceServerSecurityConfiguration` ending in `denyAll()`, with `dispatcherTypeMatchers(ERROR).permitAll()`
so a container error dispatch keeps its status (verified: both that rule and the `exp` requirement were
mutation-tested — removing either turns a 400/401 case red). `AuditActorBindingFilter` now discards a
non-UUID-`sub` authentication (holder + request-attribute copy). Retired the `AuditedAssetTestController`
probe; its attribution assertion moved onto `POST /api/v1/contracts` in `JwtAuthenticationIT`. Test token
shapes consolidated into `support/TestJwts`; `ContractApiIT`/`PaymentApiIT` use it unchanged otherwise. OpenAPI:
every operation now documents its 403 and allowed roles. New tests: `RolesClaimAuthoritiesConverterTest` (12),
`EndpointRoleMatrixIT` (38 cells + deny-by-default + denied-writes-store-nothing), `JwtAuthenticationIT` (16:
8 × 401, 5 × 403, FINANCE read, attribution, public health), `ErrorDispatchSecurityIT` (4, real
`RANDOM_PORT` server via JDK `HttpClient`), updated `AuditActorBindingFilterTest`. Known limitation, deferred:
container-level errors (firewall rejections, direct `/error`) render Boot's default JSON, not the `{data,
error}` envelope — no internals leak. Verification: `shared.security.*` + `OpenApiSmokeIT` 100 pass; full
`test --rerun` = 67 suites / 529 tests; `check` pass. No schema or dependency change.

Goal: Enforce the Addendum §3.4 endpoint-to-role matrix now, and make an authenticated request always carry
a real actor. From here on, every new endpoint ships with its role rule and its 403 tests.

Scope:

- Map the JWT `roles` claim to Spring authorities.
- Apply the matrix to the six existing endpoints. Deny every other request (`denyAll`). The ADR-005 public
  paths are unchanged.
- Reject tokens that cannot identify an actor (CR-01) or that carry no expiry, with 401.
- Out of scope: `iss`/`aud` validation, an `app_user` lookup per request, login/refresh/logout, and
  role-dependent PII unmasking. These all belong to T21, because no token issuer exists yet (ADR-005).

**Decisions (owner, 2026-09-30; recorded in ADR-015 as part of this task):**

| #   | Decision                                                                                                                                                                                                                                                               | Rejected alternative                                                                                |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------- |
| D1  | Roles claim = `roles`, a JSON **array of strings**. T21 issues `["<app_user.role>"]`. Values are case-sensitive and match `ck_app_user_role`. A non-array claim counts as "no roles". Unknown values are ignored.                                                      | `role` as one string: mirrors the column, but every IT changes and multi-role is closed off.        |
| D2  | **Trust the signed claim.** No `app_user` lookup per request. A deactivated user or a changed role takes effect when the short-lived token expires (T21: 15 min, TS §2.6). The FK `created_by → app_user(id)` still rejects writes by a `sub` that is not a real user. | Per-request `app_user` lookup: immediate revocation, but one query per request and a T21 concern.   |
| D3  | **One URL-matcher table in the `SecurityFilterChain`**, ending in `anyRequest().denyAll()`. It is the only place to audit against §3.4, and deny-by-default holds for any endpoint nobody registered.                                                                  | `@PreAuthorize` per method: unannotated methods are allowed unless extra machinery is added.        |
| D4  | A token whose `roles` contain `SYSTEM` is **denied as a whole** (403), even alongside another role. `SYSTEM` only exists for in-process jobs (Addendum §3.3, matrix "—").                                                                                              | Ignore `SYSTEM` and keep the other roles: a token that should never have been minted keeps working. |
| D5  | A token with a missing or non-UUID `sub`, or with no `exp`, is **401**. It is rejected by the decoder's validators, so it never becomes an `Authentication` (fixes CR-01 / X-10).                                                                                      | 403: a token that names no actor is an authentication failure, not a permission failure.            |

Business Rules:

- Matrix for existing endpoints (Addendum §3.4):

  | Method + path                                                        | ADMIN_OPERASIONAL | FINANCE | MANAJEMEN |
  | -------------------------------------------------------------------- | ----------------- | ------- | --------- |
  | `POST /api/v1/contracts`                                             | allow             | 403     | 403       |
  | `POST /api/v1/contracts/{id}/activate`                               | allow             | 403     | 403       |
  | `POST /api/v1/payments`                                              | allow             | 403     | 403       |
  | `GET /api/v1/contracts`                                              | allow             | allow   | allow     |
  | `GET /api/v1/contracts/{id}`                                         | allow             | allow   | allow     |
  | `GET /api/v1/contracts/{id}/installments`                            | allow             | allow   | allow     |
  | anything else, including `PUT /api/v1/contracts/{id}` (C-5 deferred) | 403               | 403     | 403       |

- Status precedence: no token or invalid token (bad signature, `alg: none`, expired, no `exp`, missing or
  non-UUID `sub`) → 401 `UNAUTHORIZED`. Valid token without a permitted role → 403 `FORBIDDEN`. Both use the
  standard envelope. An unauthenticated request to an unlisted path is 401, not 403.
- A denied request writes nothing: no `idempotency_keys` claim, no business row, no journal.
- An allowed request behaves exactly as today, including 400/404/409 from the application.
- PII masking stays unconditional (stricter than Addendum §9; unchanged).
- Audit attribution: every authenticated request binds `sub` as the actor. `SYSTEM` is never the actor of an
  HTTP request.

Implementation plan (in order):

1. **`shared.security.AppRole`** enum `ADMIN_OPERASIONAL, FINANCE, MANAJEMEN, SYSTEM` (mirrors
   `ck_app_user_role`), with `authority()` → `ROLE_<name>` and `isHttpRole()` (false for `SYSTEM`).
2. **`shared.security.RolesClaimAuthoritiesConverter`** (`Converter<Jwt, Collection<GrantedAuthority>>`):
   reads `roles`. If the claim is absent or not a list → empty. Any element equal to `SYSTEM` → empty (D4).
   Otherwise each `String` element that names an HTTP `AppRole` becomes its authority, and everything else is
   ignored. It returns an unmodifiable list. Wire it through a `JwtAuthenticationConverter` on
   `oauth2ResourceServer().jwt(...)`. The default `SCOPE_` converter is not used.
3. **Decoder validators** in `jwtDecoder(...)`: `DelegatingOAuth2TokenValidator` of the default validators,
   plus `JwtClaimValidator` for `exp` present and for `sub` parseable as a UUID. A validation failure surfaces
   as `InvalidBearerTokenException` → the existing envelope entry point → 401. Check with Context7 whether
   Spring Security 7's defaults already require `exp` (6.x accepted a token without it). Add the explicit
   validator either way, so the rule is visible.
4. **`AuditActorBindingFilter`** keeps a defensive fail-closed branch. If a `JwtAuthenticationToken` with a
   non-UUID `sub` ever reaches it, it clears the `SecurityContext`, so authorization answers 401 for an
   anonymous request. This makes the Javadoc true (X-10). The validator in step 3 makes the branch
   unreachable in normal operation.
5. **Matrix in `ResourceServerSecurityConfiguration.securityFilterChain`**, in the order above:
   `PUBLIC_PATHS` permitAll → `DispatcherType.ERROR` permitAll (so a denied `/error` forward cannot turn an
   application 400/404 into 401/403; verify with the existing 404 `CONTRACT_NOT_FOUND` IT) → the six method +
   path matchers with `hasRole`/`hasAnyRole` → `anyRequest().denyAll()`. Use `HttpMethod`-qualified matchers,
   and single-segment `*` for `{id}`. Update the class Javadoc (drop "Sprint 6b", cite ADR-015).
6. **Test token helper** `src/test/java/com/serfira/support/TestJwts` (test-only). It replaces the three
   copied `jwtFor(...)` methods in `ResourceServerSecurityIT`, `ContractApiIT` and `PaymentApiIT`:
   `forRoles(UUID sub, String... roles)`, plus builders for an expired token, a token without `exp`, a raw
   `sub`, a string-valued `roles`, and `alg: none`. The secret mirrors
   `src/test/resources/application.properties`. `ContractApiIT` and `PaymentApiIT` keep `ADMIN_OPERASIONAL`
   because they only call admin or read endpoints. They are the only ITs that go over HTTP with a token.
7. **Retire `AuditedAssetTestController`** (`POST /api/v1/__test-audit/assets`). Under `denyAll` it returns
   403, and it bypassed the real write path anyway. Move `authenticatedWriteRecordsJwtSubjectAsCreatedBy` onto
   `POST /api/v1/contracts` and assert `contract.created_by = sub`.
8. **OpenAPI**: add a `403 FORBIDDEN` response and the allowed roles to every `@Operation` in
   `ContractController` and `PaymentController` (the convention is to list every error code).
9. **Docs** in the same change:
   - ADR-015 "JWT roles claim and endpoint authorization" (D1–D5; amends ADR-005 decisions 3 and 5).
   - TS §2.6: the roles claim and the 401/403 precedence.
   - Addendum §3.4: an implementation note that the matrix is enforced from T7, by one matcher table, with
     `denyAll`.
   - `ErrorCode`/controller docs if needed.
   - tasks.md: A-12 resolved, X-10 closed.
   - `PROGRESS.md`.

Dependencies: T23 (so the public-path tests run against the final springdoc). No dependency on T21: tokens are
still minted externally.

Acceptance Criteria:

- Each cell of the matrix above answers exactly as specified. "allow" means neither 401 nor 403.
- An authenticated request to an unlisted method or path → 403. An unauthenticated one → 401.
- The token cases below answer as listed, and a rejected write leaves zero rows in `contract`, `payment`,
  `idempotency_keys` and `journal_entry`.
- `contract.created_by` equals the token `sub` for a write through the real endpoint.
- Public paths (`/actuator/health`, `/v3/api-docs`) still answer without a token.
- The full `./gradlew test` and `./gradlew check` are green.

Tests:

- Unit `RolesClaimAuthoritiesConverterTest`:
  - one role;
  - several roles;
  - an unknown value ignored;
  - wrong case ignored;
  - a non-string element ignored;
  - `SYSTEM` alone → empty;
  - `SYSTEM` + `ADMIN_OPERASIONAL` → empty;
  - a string-valued claim → empty;
  - a missing claim → empty.
- Unit `AuditActorBindingFilterTest`: update `nonUuidSubjectIsNotFabricatedIntoAnActor` so it also asserts
  that the security context was cleared.
- IT `EndpointRoleMatrixIT` (`shared.security`), parameterized role × endpoint → expected class (allowed /
  403). Requests carry no fixtures: `POST /contracts` with an empty body, `POST /payments` without a body, and
  `/activate` on a random id. So "allowed" shows up as 400/404, never 401/403, and "denied" must be exactly 403
  with `error.code = FORBIDDEN`. It also checks that denied writes store nothing.
- IT `JwtAuthenticationIT` (extends today's `ResourceServerSecurityIT`):
  - 401 cases: no token, tampered signature, wrong key, `alg: none`, expired, no `exp`, missing `sub`,
    non-UUID `sub` (CR-01, and no row written).
  - 403 cases: no `roles`, string `roles`, only unknown roles, `SYSTEM`, `SYSTEM` + admin.
  - Other cases: `FINANCE` on `GET /contracts` → 200; the real-endpoint `created_by` attribution.
- Existing `ContractApiIT` and `PaymentApiIT` pass unchanged apart from using `TestJwts`.
- Narrowest first: `.\gradlew test --tests "com.serfira.shared.security.*"`, then the full `.\gradlew test`,
  then `check`.

Risks:

- `denyAll` turns every unknown path into 403 for authenticated callers (it was 404 before). This is intended.
  Clients must not rely on 404 for unrouted paths.
- HS256 means anyone holding the signing key can mint any role. That is unchanged from today and bounded by
  key custody. RS256 and a real issuer come with T21.
- D2 means a revoked user keeps access until the token expires. Tokens are minted externally until T21, so
  test/dev tokens should use short expiries.

### T8 — Aging report (D3)

Status: DONE
Estimate: 2 pts

Implementation note (2026-10-02): New read-only `reporting` module. `GET /api/v1/reports/aging` returns the
Current/1–30/31–60/61–90/>90 buckets as a portfolio total and per contract, in the `{data, error}` envelope.
Bucketing/aggregation live in `reporting.domain` (`AgingBucket`, `AgingBreakdown`) and
`reporting.application` (`AgingReportService`); the DPD calendar (`InstallmentAging`) and the outstanding
formula (`InstallmentBalance`) stay owned by `contract` and are read through the new one-way
`contract.application.AgingReportSourcePort` → `AgingReportSourceService` (`InstallmentAgingSnapshot` per
ACTIVE-contract installment with `outstanding > 0`), so there is no second formula (A-7, recorded in TS §1).
Penalty is included in outstanding; penalty adjustments are not subtracted (no E5 yet) and the OpenAPI
description says so. Scope is ACTIVE contracts only; `SETTLED`/`WRITTEN_OFF`/`PAID` installments drop out at
`outstanding = 0`. `as_of` defaults to today and must be today, else 400 `INVALID_AS_OF_DATE` (new
`ErrorCode`); Σ bucket = outstanding holds at both levels by construction. One matcher row added to
`ResourceServerSecurityConfiguration` (ADMIN_OPERASIONAL/FINANCE/MANAJEMEN), with its matrix and 401/403
tests. No migration, dependency, or decision change (A-6/A-7 were resolved in ADR-013; see its T8
implementation note). Verification: `AgingBucketTest`, `AgingBreakdownTest`, `AgingReportServiceTest` (unit)
and `AgingReportIT` + updated `EndpointRoleMatrixIT` (Testcontainers) pass; full `./gradlew test` = 72 suites
/ 570 tests / 0 failures; `./gradlew check` passes.

Goal: Deliver PRD D-2, an aging report per contract and for the portfolio.

Scope: `GET /api/v1/reports/aging` in a new read-only `reporting` module, with buckets Current, 1–30, 31–60,
61–90 and >90 DPD (PRD glossary, FE §2.9).

Business Rules:

- Outstanding = principal residual + recognized interest residual + effective penalty residual (PRD §5A,
  Addendum §14). Future unrecognized interest is excluded.
- For every contract and for the portfolio, Σ bucket amounts = total outstanding.
- DPD, bucket basis, contract scope and `as_of` semantics come from T1.b (A-4, A-6, A-7).
- Roles: ADMIN_OPERASIONAL, FINANCE, MANAJEMEN.
- Read-only. The report never writes.

Implementation Notes: TS §1 allows `reporting` to read all tables read-only. Keep the outstanding
definition consistent with `InstallmentBalance`, and do not introduce a second formula. Penalty adjustments
are not subtracted until E5; note this in the response docs. Pagination follows TS §2.2 (size ≤ 100).

Dependencies: T1.b, T6, T7.

Acceptance Criteria:

- Bucket boundaries match T1.b. Σ buckets = outstanding at both contract and portfolio level.
- Out-of-scope contracts (per T1.b) are excluded.
- OpenAPI is documented.

Tests:

- IT: installments at DPD 0, 1, 30, 31, 60, 61, 90 and 91 land in the right buckets. Penalty is included in
  outstanding. Σ invariant holds. Invalid `as_of` → 400. Pagination bounds. All three roles → 200.

Risks: A-6 (historical `as_of`) could expand scope a lot if historical reconstruction is required.

### T9 — Contract statement (C5)

Status: DONE
Estimate: 2 pts

Implementation note (2026-10-02): `GET /api/v1/contracts/{id}/statement` returns the contract's posted
journal lines, oldest first, in the `{data, error}` envelope. The endpoint lives in `contract`
(`ContractController` + `ContractQueryService`): `contract` validates the id (404 `CONTRACT_NOT_FOUND`)
and reads the lines through the new `ledger.application.ContractStatementPort` → `ContractStatementService`
(`@Transactional(readOnly = true)`), which projects `journal_line` joined to its parent `journal_entry`.
This reuses the existing `contract → ledger` edge (activation already posts via `LedgerPostingService`), so
it is not a new module edge and `ledger` still depends on nothing; the port does no existence check. The
statement is ledger-literal (ADR-013 A-8): one row per `journal_line`, `debit`/`credit` the posted line
amounts (no running balance, no customer-view re-interpretation), with `account_code` (+ `account_name`
from the new `LedgerAccount.displayName()`), `description`/`ref_type`/`ref_id`/`reversal_of_id` from the
parent entry, and an `is_reversal` flag — a reversal appears as its own row, history is never hidden.
Optional filters: `ref_type` (one `LedgerRefType`) and a `from`/`to` business-date range (Asia/Jakarta,
both inclusive, resolved to a half-open instant window because `entry_date` is `timestamptz`); order is
`entry_date asc, line.id asc`; pagination is `page`/`size` (size ≤ 100) via `PageResponse`. A DRAFT
contract has posted nothing → an empty page; `from > to`/out-of-range paging → 400 `VALIDATION_ERROR`; a
bad `ref_type`/date → 400 via `GlobalExceptionHandler`. Roles ADMIN_OPERASIONAL/FINANCE (MANAJEMEN → 403),
enforced by one matcher row in `ResourceServerSecurityConfiguration` (Addendum §3.4). Migration
`V11__journal_line_contract_statement_index.sql` adds `(contract_id, entry_date)` on `journal_line` (V1 had
no `contract_id` index). No new `ErrorCode`, dependency, or decision. ADR-013 implementation note T9;
X-8 closed. Tests: `ContractStatementIT` (statement behaviour), the flipped `EndpointRoleMatrixIT` cell, and
`LedgerAccountNameIT` (account name = seed). Post-review cleanup: a shared `validatePaging` guard (removes
the duplicated paging check) and the `statementPort` field rename. Verification: `compileJava
compileTestJava` pass and all `*Test` unit tests pass; the `*IT` Testcontainers tests were **not** run here
because Docker was unavailable on the machine — run `.\gradlew test` and `.\gradlew check` with Docker up to
confirm.

Goal: Deliver the rekening koran (DM §2, FE §2.7): a chronological list of the contract's financial
movements.

Scope: `GET /api/v1/contracts/{id}/statement` with date-range and `ref_type` filters, chronological order and
pagination.

Business Rules:

- The source is the ledger (`journal_line.contract_id`). The statement reads what was posted and computes
  nothing.
- Column semantics come from T1.c (A-8).
- Reversal entries appear as their own rows and are marked as reversals. History is never hidden.
- Roles: ADMIN_OPERASIONAL, FINANCE. MANAJEMEN → 403 (matrix).
- Unknown contract → 404 `CONTRACT_NOT_FOUND`. A DRAFT contract → an empty statement.

Implementation Notes: Expose a read-only query on the `ledger` application layer. `ledger` stays
independent (callers depend on it, never the reverse). Filter on `entry_date` in the business zone.

Dependencies: T1.c, T7.

Acceptance Criteria:

- After activation, billing, accrual and payment, the statement lists each journal entry once, in
  `entry_date` order, with amounts matching the ledger.
- Filters and pagination work as specified.

Tests:

- IT: an end-to-end flow → expected rows and amounts. `ref_type` filter. Date-range boundaries (inclusive,
  business zone). MANAJEMEN → 403. Unknown id → 404.

Risks: A-8 may turn into a customer-balance view that needs a running balance and account selection.

---

## Sprint 4d — Phase-1 Close C: Integrity Hardening & Exit

**Goal:** Close the verified 2026-09-30 review findings that affect financial integrity or batch reliability
before Sprint 5 adds settlement, credit and waivers on top of them, then verify the Phase-1 exit (PRD §7:
scenarios 1–4 and 8–9, operational demo).
**Scope:** T24 (2 pts), T25 (2 pts), T26 (2 pts), T11 (2 pts) = 8 pts, plus T10 (3 pts) only if a new
business decision requires it. A-13 was decided 2026-09-30 (option A), so T24 is unblocked.

### T24 — Idempotency key lifecycle (review CR-04, CR-13)

Status: DONE (A-13 decided: option A)
Estimate: 2 pts

Implementation note (2026-10-02): Accepted as `ADR-017-idempotency-key-lifecycle.md` (amends ADR-007
decision 9). **The new ADR is ADR-017, not ADR-016** — this file's original scope said "ADR-016", but that
number was already taken by `ADR-016-decimal-magnitude-and-activation-date-bounds.md`, so the lifecycle ADR
got the next free number. Option A implemented: a key is single-use forever per endpoint, and retention only
bounds replay. `IdempotencyService` drops the `reclaimExpired` takeover; when a claim already exists it reads
the row and, if `expires_at` has passed, throws the new `IdempotencyKeyExpiredException` (409
`IDEMPOTENCY_KEY_EXPIRED`, new `ErrorCode`) **before** the supplier runs — no billing, accrual, contract or
payment work for an expired key, whatever the body. The permanent backstops return the same code once T18
has deleted the row: `ContractCommandService.translateCreateViolation` now maps `uq_contract_idempotency` to
the expired code (was `CONFLICT`), and a new `PaymentApplicationService.translatePaymentViolation` maps
`uq_payment_idempotency` likewise. `PaymentConflictClassifier` now matches on the constraint name — only
`uk_penalty_accrual` and optimistic-lock conflicts are retryable, so a `uq_payment_idempotency` `23505` is no
longer retried (CR-04). `PaymentRetryingService.BACKOFF_MILLIS` trimmed to `{50, 150}` with its Javadoc
corrected (CR-13); no behaviour change. The `IdempotencyService`/`IdempotencyKeyRepository` Javadoc now
describes single-use-forever semantics (X-11). No migration: option A keeps the existing ADR-007 decision 8
backstops. Docs synchronized: ADR-017, TS §2.5, DM (idempotency), and the `ContractController`/
`PaymentController` OpenAPI 409 lists. The T13/T18 (and T16) carry-forward rows were reviewed and already
state the A-13 option A / T24 rule and `IDEMPOTENCY_KEY_EXPIRED` (recorded when A-13 was resolved), so they
needed no change. Verification: `compileJava --rerun-tasks` and `compileTestJava` pass;
`PaymentConflictClassifierTest` (7) and `PaymentRetryingServiceTest` pass; full unit run
`./gradlew test --tests "*Test"` = 43 suites / 335 tests / 0 failures. **Docker was unavailable on the
machine, so the Testcontainers `*IT` suites did not run** — the rewritten `IdempotencyRetentionIT`, the new
retention cases in `ContractIdempotencyIT`/`PaymentIdempotencyIT`, and the full `./gradlew test` / `./gradlew
check` still need a run with Docker up before T24 counts toward the suite totals.

Goal: Make every layer agree on what an `Idempotency-Key` means after its retention window, so a reused key
gets one deterministic, truthful answer.

Verified behavior today (2026-09-30):

- After `IDEMPOTENCY_KEY_RETENTION_DAYS` (7), `reclaimExpired` takes over the row. It clears `response_json`,
  does not compare the fingerprint, and re-runs the operation.
- `POST /contracts`: the re-run always fails, with 409 `DUPLICATE_CONTRACT` (live contract on the same
  identified asset) or 409 `CONFLICT` (`uq_contract_idempotency`, V7).
- `POST /payments`: the re-run bills and accrues, then hits `uq_payment_idempotency` (SQLSTATE `23505`).
  `PaymentConflictClassifier` retries every `23505`, so three attempts each redo billing and accrual and roll
  back, then return 409 `CONCURRENT_MODIFICATION`. That tells the client to retry, which can never succeed.
- Money is never moved twice. The defect is a misleading answer plus wasted work, so it is MEDIUM, not HIGH.
- `IdempotencyRetentionIT` only covers a probe endpoint.

Scope:

- Implement the A-13 decision (owner, 2026-09-30, option A: **a key is single-use forever per endpoint;
  retention only bounds how long the stored response is replayed**). Example: key `abc` creates payment
  `PAY-202609-0001` on 1 Oct. A retry of `abc` on 5 Oct replays that response. A retry on 9 Oct (past the 7-day
  window) gets 409 `IDEMPOTENCY_KEY_EXPIRED` with no billing, accrual or payment work, whether or not the body
  matches. It never gets a second payment and never gets `CONCURRENT_MODIFICATION`.
  - Drop the takeover.
  - Reusing an expired key → 409 with a dedicated `ErrorCode` (for example `IDEMPOTENCY_KEY_EXPIRED`) before
    any business code runs.
  - After T18 cleanup has deleted the row, the business-row backstops give the same code:
    `translateCreateViolation` for contracts, and a payment-side translation for `uq_payment_idempotency`.
- `PaymentConflictClassifier`: never retry a `uq_payment_idempotency` violation. Only `uk_penalty_accrual`
  and optimistic-lock conflicts are retryable.
- Remove the unreachable `400` from `PaymentRetryingService.BACKOFF_MILLIS` and fix its Javadoc (CR-13).
- Record the decision in ADR-017, which amends ADR-007 decision 9. (This file originally said "ADR-016", but
  that number was taken by `ADR-016-decimal-magnitude-and-activation-date-bounds.md`; the lifecycle ADR is
  therefore ADR-017.) Fix the `IdempotencyService` Javadoc (X-11).
- Update TS §2.5, DM (idempotency), and the T13 (`uq_settlement_idempotency`, fully unique) and T18 carry-forwards.

Business Rules: A retry inside the window replays or conflicts exactly as today (TS §2.5). No request can
create a second business row for a key.

Dependencies: none (A-13 resolved). Independent of T7.

Acceptance Criteria: For both endpoints, "success → clock past retention → same key, same body" and "…
different body" each return the documented status and code. No billing or accrual row is written by the
rejected attempt, and there is only one payment attempt (no retry).

Tests:

- IT per endpoint with `FixedClock` advanced past retention (same body / different body / after the row was
  deleted).
- Unit: the classifier refuses `uq_payment_idempotency`.
- The existing `IdempotencyRetentionIT` is rewritten for the new rule. The spec changed; the behavior was
  not merely made to pass.

Risks: Clients that recycled keys after a week now get a hard 409 instead of a misleading one. That is
intended, and it is documented in OpenAPI and TS §2.5. No migration is needed: option A keeps the existing
business-row backstops (ADR-007 decision 8) unchanged.

### T25 — Complete the database accounting backstops (review CR-05, CR-06, CR-12)

Status: TODO
Estimate: 2 pts

Goal: Make the claim that "the database is the backstop" true for the cases the V3 triggers cannot see,
before Sprint 5 adds new posting types.

Scope (one forward-only migration, V11):

- **Parent-side deferred checks (CR-06).** Today `trg_journal_entry_balance_deferred` fires only on
  `journal_line`, and the allocation-total trigger fires only on `payment_allocation`. So a `journal_entry`
  with zero lines, or a `payment` with zero allocations, commits.
  - Add `DEFERRABLE INITIALLY DEFERRED` constraint triggers on `journal_entry` INSERT (≥ 2 lines, balanced)
    and `payment` INSERT (Σ allocations = amount).
  - These are moved here from T16's carry-forward.
- **Event uniqueness (CR-05).** Add a partial unique index on `journal_entry (ref_type, ref_id)` for
  `reversal_of_id IS NULL AND ref_type IN ('CONTRACT_ACTIVATION','BILLING','PENALTY_ACCRUAL','PAYMENT')`.
  - Limiting it to today's ref types keeps ADR-008 decision 5(b) open: settlement may still post several
    entries (E2).
  - Before creating the index, check that existing data has no duplicates.
  - Amend ADR-008 decision 5.
- **`system_parameter` append-only (CR-12, X-12).** Add `block_modification()` for UPDATE and DELETE.
  - Two ITs delete their own override rows (`SystemParameterServiceIT`, `ContractActivationIT`).
  - Change their cleanup to a test-only trigger bypass (`SET LOCAL session_replication_role = replica` inside
    the cleanup statement; the Testcontainers user is a superuser). Alternatively, give each override a
    unique future-dated key. Never weaken the trigger.

Business Rules: Invariants 1 and 3 (DM) now hold for parent rows too. Corrections remain reversal entries.

Implementation Notes: Every existing write path already satisfies these checks, so no Java change is
expected. If one fails, that is a real bug. Deferred triggers fire at commit, and the ITs already commit for
real.

Dependencies: none. It must land before T12–T15 add posting types.

Acceptance Criteria:

- V11 applies to an empty database and to one migrated to V10 with data.
- Each rejected case below fails at commit with the specific PostgreSQL exception.

Tests (`AccountingInvariantsIT`):

- a journal entry with 0 lines is rejected;
- a journal entry with 1 line is rejected;
- a payment with 0 allocations is rejected;
- a second non-reversal entry for the same `(BILLING, installment)` is rejected, while a reversal entry is
  allowed;
- `system_parameter` UPDATE and DELETE are rejected;
- the full suite stays green.

Risks: A trigger bypass in test cleanup must stay inside test sources.

### T26 — Daily job robustness (review CR-07, CR-08, CR-09)

Status: TODO
Estimate: 2 pts

Goal: Keep the daily job's audit trail truthful after a crash, and keep it bounded in memory and in
contention as the portfolio grows.

Scope:

- **Stale `RUNNING` rows (CR-07).** Today a JVM death, or any exception escaping `runAsSystem`, leaves the
  three `job_run` rows `RUNNING` forever.
  - (a) Wrap the loop so that an escaping exception completes the rows as `FAILED`.
  - (b) At the start of a run, while holding the ShedLock, mark every older `RUNNING` row of the same job
    names as abandoned. The lock guarantees that no other run is live, so no timeout heuristic is needed.
  - "Abandoned" needs either a new status (`ABANDONED`, a migration changing `ck_job_run_status`) or `FAILED`
    with a note. Either way it amends ADR-013 A-9 ("COMPLETED/FAILED only"). Recommended: `ABANDONED`.
- **Keyset batching (CR-08).** Replace `ActiveContractListingPort.findActiveContractIds()` (the full `List`) with
  `findActiveContractIdsAfter(UUID afterId, int limit)`, ordered by id.
  - The batch size is a property.
  - Each contract keeps its own transactions.
  - The port edge already exists. Update the port Javadoc and TS §1.
- **Backoff (CR-09).** `runWithRetry` retries 5 times with no pause. Inject the existing `Sleeper` and pause
  between attempts (for example 50/150/400/1000 ms for 4 pauses). Record the values in TS §2.3 and
  Addendum §5.

Business Rules: Unchanged from T3/T6. Same contracts, same rows, same counters. Rerun idempotency holds.

Dependencies: none.

Acceptance Criteria:

- A simulated crash leaves no `RUNNING` row after the next run.
- A run over more contracts than one batch processes each contract exactly once.
- The retry pauses follow the documented sequence.

Tests:

- Unit: backoff sequence with a fake `Sleeper`.
- IT:
  - seeded stale `RUNNING` rows are closed by the next locked run;
  - a batch size of 2 with 5 contracts produces the same rows as a single batch;
  - an exception escaping the loop completes the rows;
  - `DailyServicing*IT` stay green.

Risks: A contract activated during a run with an id below the cursor waits for the next day. The current
snapshot list behaves the same way.

### T10 — Component-exact penalty base (conditional)

Status: DEFERRED (T1.a / ADR-013 kept ADR-012 decision 2 unchanged; no schema change required unless a new
business decision explicitly demands component-exact precision)
Estimate: 3 pts if required

Goal: Only if T1.a decides it: compute the base from unpaid principal and interest only, excluding penalty
already paid (TS §4.3 read literally), and/or from a per-date historical base.

Scope (conditional):

- Carry the component split of resolutions (and their business dates, if a historical base is chosen)
  across the `contract` port. Document any new module edge in TS §1.
- Change `InstallmentBalance.penaltyBase` or its replacement.

Business Rules:

- Worked check (demo contract A, period 1): 2 days accrued (3,146.66), then 3,146.66 paid to penalty. The
  next day's charge must be 1,573.33 (base 1,573,333.33), not 1,570.19.
- A golden or expected value changes only because the specification changed. `PenaltyCalculatorTest.java:94-105`
  is updated only in that case (`50-testing`).

Implementation Notes: ADR-012 alternatives 5 and 6 list the options that were rejected earlier and why.
T1.a must supersede them explicitly.

Dependencies: T1.a, T4.

Acceptance Criteria: The next-day charge after a penalty-only payment equals `rate × unpaid (principal +
recognized interest)`. All previously green penalty tests either stay green or are updated with a
documented spec reason.

Tests:

- Unit: the calculator with a component-split base.
- IT: accrue → penalty-only payment via HTTP (not SQL seeding) → next-day charge.
- If a historical base is chosen: a day-by-day run and a single backfill over the same period spanning a
  payment produce identical totals.

Risks: If T1.a keeps ADR-012 decision 2, this task becomes DEFERRED with no code change.

### T11 — Phase-1 test hardening and exit verification

Status: TODO
Estimate: 2 pts

Goal: Prove the Phase-1 exit criteria and close the test gaps found in the 2026-09-29 review.

Scope:

- Immutability tests for `journal_line`, `payment_allocation`, `settlement_allocation` and
  `penalty_adjustment` (`penalty_accrual` is covered in T2).
- Replace weak assertions: `PaymentIdempotencyIT.java:157` and `ContractIdempotencyIT.java:150-151` should
  assert the expected exception type. The bare `RuntimeException` in `AccountingInvariantsIT` should become
  the specific DB exception.
- PRD scenarios 1–4 and 8–9 end-to-end over HTTP plus the job, with no SQL seeding for the behavior under
  test.
- A log-capture test: raw NIK and phone never appear in logs across contract create, payment and the job
  (Addendum §9).
- Full `./gradlew test` green, including Testcontainers, locally and in CI.

Business Rules: Invariants 1, 3, 6, 8, 9 and 12 hold after every scenario.

Implementation Notes: Consider one shared IT cleanup extension instead of the 20-table `TRUNCATE` copied
into each suite before Sprint 5 adds more tables. Optional, and only if it stays behavior-neutral.

Dependencies: T3–T9 and T23–T26 (and T10 if it applies).

Acceptance Criteria: Every item above has a passing test. The CI run is green. The Phase-1 exit is recorded
in `05_SPRINT_PLAN.md` / README progress.

Tests: As listed in Scope.

Risks: None.

---

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

| ID  | Story                                                              | Sprint | Status | Carry-forward requirements from this plan                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| --- | ------------------------------------------------------------------ | ------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T12 | E1 Settlement quote                                                | 5      | TODO   | Before taking the snapshot, bill and accrue through the quote date (accrue-before-resolve; ADR-012 risk ii). ACT/30 golden test (Addendum §8).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| T13 | E2 Settlement execution                                            | 5      | TODO   | Re-bill and re-accrue at execution, then revalidate the snapshot (`STALE_SETTLEMENT_QUOTE`). Decide whether settlement creates one entry or several, then extend T25's scoped `(ref_type, ref_id)` index to `SETTLEMENT` accordingly (ADR-008 decision 5). Apply the A-13 (option A) / T24 rule: an expired settlement key → 409 `IDEMPOTENCY_KEY_EXPIRED`, and a `uq_settlement_idempotency` violation (V1, fully unique, already consistent with A) maps to the same code. Born with RBAC tests (T7 matcher table).                                                                                                                                               |
| T14 | E3 Excess → `contract_credit` + apply endpoint                     | 5      | TODO   | Credit applies only to recognized receivable. DB cap on Σ applications ≤ credit amount (invariant 11; not enforced today).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| T15 | E5 Penalty waive/reduce                                            | 5      | TODO   | Replace the native `penalty_adjustment` read in `InstallmentRepository` with a `penalty`-owned port (ADR-010 seam). Subtract adjustments in `sumTotalsByContractId(s)` and in the status derivation of `Installment.applyPayment` (its E5 extension point). Reconcile the gross vs net penalty caps between V3 `assert_installment_amounts` and `assert_payment_allocation_component_caps`. One outstanding formula for payment snapshot, contract totals, settlement and reporting (review CR-14).                                                                                                                                                                 |
| T16 | E4 Void payment + synchronous penalty recalc                       | 6      | TODO   | The void re-pricing rule is resolved by ADR-013 (A-3): no re-pricing of already-accrued dates, catch-up only for dates with no row yet. Make `payment.amount` non-updatable (the parent-side deferred checks moved to T25). Revisit the V3 allocation-total trigger, which counts VOIDED rows (C3 note), and T25's payment-insert check for voided payments. Under A-13 (option A) a voided payment's key stays consumed; because `uq_payment_idempotency` only covers `POSTED`, keep that true after T18 deletes the claim row (for example by widening the index to all statuses in the void migration). Void is blocked when credit was consumed (invariant 16). |
| T17 | F1 Consistency check job                                           | 6      | TODO   | Runs after aging in the T3 job, reusing its ShedLock, `job_run`, keyset batching and backoff (T26).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| T18 | F4 Idempotency key cleanup job                                     | 6      | TODO   | Reuses the T3/T26 job infrastructure. Deleting a row must not re-open the key: the T24/A-13 rule and the business-row backstops decide what a later reuse returns.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
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

### Correction to the 2026-09-29 code review

The review rated run-time pricing (F-01) CRITICAL and "undocumented", and said lazy accrual would not fix
backfill across a missed job window. Both claims were wrong:

- ADR-012 _Consequences_ explicitly accepts the run-time base as a conservative trade-off, and records the
  pay-before-run loss as residual risk (i), with lazy accrual deferred to D2.
- Under the invariant above, lazy accrual also covers backfill after a missed window. At review time, the
  loss was real only while the payment path skipped accrual; T4/ADR-014 now closes that payment risk.

Also already documented, not new findings:

- F-05 is ADR-012 decision 2.
- The missing ShedLock table (F-02) is noted in the sprint plan.
- The application-only ledger guard (F-08) is ADR-008 decision 5.
- The dev-key fallbacks (F-03) are ADR-005 / README.

### External review 2026-09-30 (verified)

An external AI review of `848e8a3` reported 16 findings, F-01…F-16. They are renumbered **CR-01…CR-16** here
so they do not collide with the 2026-09-29 review's F-numbers above. Each finding was checked against the
source, the migrations, CI and the upstream compatibility matrices on 2026-09-30. The suite was not re-run
for this check; the last green run is the T6 run (460 tests).

| ID    | Finding                                                   | Verdict (what was checked)                                                                                                                                                                                                                                                                                                    | Severity after check         | Task                |
| ----- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------- | ------------------- |
| CR-01 | Non-UUID `sub` stays authenticated and writes as `SYSTEM` | **Fixed (T7, ADR-015 D5).** The decoder's `sub`-is-UUID validator rejects the token (401) before it authenticates; `AuditActorBindingFilter` also discards such an authentication defensively. `JwtAuthenticationIT` covers it                                                                                                | HIGH (resolved)              | T7 (DONE)           |
| CR-02 | ShedLock 6.9.0 on Boot 4.1.1                              | **Confirmed.** The ShedLock README matrix lists 7.x as tested with Boot 4.x and 6.x with Boot 3.3–3.5. No defect is observed: the lock ITs are green                                                                                                                                                                          | MEDIUM (unsupported pairing) | T23 (DONE)          |
| CR-03 | springdoc 2.8.9 on Boot 4                                 | **Confirmed.** The springdoc README says Boot 4 needs springdoc v3. No test requests `/v3/api-docs`, so runtime compatibility is unverified                                                                                                                                                                                   | MEDIUM                       | T23 (DONE)          |
| CR-04 | Retention takeover vs permanent business-row keys         | **Confirmed, severity lowered.** No double execution. After 7 days: contracts → 409 `DUPLICATE_CONTRACT`/`CONFLICT`. Payments → 3 attempts that redo billing and accrual, then 409 `CONCURRENT_MODIFICATION`, because `PaymentConflictClassifier` retries every `23505`. `uq_settlement_idempotency` will behave the same way | MEDIUM                       | A-13 (A) → T24      |
| CR-05 | Ledger one-entry-per-event only in Java                   | **Partially true.** It is a documented decision (ADR-008 d5), and every current event has its own DB guard, so the race described does not occur today. Adopted as defense in depth, scoped to current ref types                                                                                                              | LOW                          | T25                 |
| CR-06 | Zero-line entry / zero-allocation payment bypass V3       | **Confirmed.** The V3 triggers are on child tables only. This was already a T16 carry-forward; moved earlier                                                                                                                                                                                                                  | MEDIUM                       | T25                 |
| CR-07 | `job_run` stuck in `RUNNING`                              | **Confirmed.** It also happens when any exception escapes `runAsSystem`, not only on JVM death. It affects the audit trail, not money                                                                                                                                                                                         | MEDIUM                       | T26                 |
| CR-08 | All ACTIVE ids loaded at once                             | **Confirmed** (`findIdsByStatusOrderById` returns a `List`). It is ids only, so it is not a Phase-1 concern                                                                                                                                                                                                                   | LOW                          | T26                 |
| CR-09 | Daily retry without backoff                               | **Confirmed** (`continue`, 5 attempts, no pause)                                                                                                                                                                                                                                                                              | LOW–MEDIUM                   | T26                 |
| CR-10 | No `iss`/`aud`/active-user/role checks                    | **Roles + `exp` fixed (T7, ADR-015).** Role matrix enforced and `exp` now required. `iss`/`aud`/active-user still need a real issuer                                                                                                                                                                                          | MEDIUM                       | T7 (DONE), T21      |
| CR-11 | No key rotation                                           | **Confirmed and documented** (ADR-007 consequences). The `v1:` envelope carries no key id                                                                                                                                                                                                                                     | MEDIUM (pre-production)      | T28                 |
| CR-12 | `system_parameter` append-only only by convention         | **Confirmed.** No trigger exists. Two ITs delete rows during cleanup                                                                                                                                                                                                                                                          | MEDIUM                       | T25                 |
| CR-13 | 50/150/400 ms documented, 50/150 ms real                  | **Confirmed.** `BACKOFF_MILLIS[2]` is unreachable. The docs are corrected in this re-plan; the code constant is tidied in T24. Addendum §5 only gave the values as an example ("mis.") and is unchanged                                                                                                                       | LOW                          | docs now, T24       |
| CR-14 | Outstanding formula differs across read and write paths   | **Confirmed but dormant.** No `penalty_adjustment` write path exists. It was already a T15 carry-forward                                                                                                                                                                                                                      | LOW                          | T15                 |
| CR-15 | CI runs only `test` + compose build                       | **Confirmed**                                                                                                                                                                                                                                                                                                                 | LOW                          | T23 (smoke IT), T27 |
| CR-16 | Committed dev keys in Compose, no production guard        | **Confirmed and documented** (ADR-005, README). It was already a T21 carry-forward                                                                                                                                                                                                                                            | LOW                          | T21                 |

Corrections to the review itself:

- CR-04 is not HIGH. The permanent backstops prevent any second business row. The defect is a misleading
  error plus wasted work.
- CR-05 ignores ADR-008 decision 5. Its per-event DB guards are the reason no race exists today.
- CR-02/CR-03 are compatibility risks, not observed failures.

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
