# Serfira tasks

Canonical implementation backlog. From Sprint 4b onward this file supersedes the per-sprint allocation in
`05_SPRINT_PLAN.md`. The story IDs used there (A1…G6) are kept as cross-references. Business rules stay in
`01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → ADRs. This file points to
those documents and does not restate them.

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`.
Estimates use the sprint plan's points (1 pt ≈ 2–3 h; target velocity 8–13 pts per sprint).

---

## Current Project State

**Snapshot:** `main` @ `cdce254` (2026-09-27), in sync with `serfira/main`. Three local, uncommitted changes
are unrelated to this plan: `.gitignore` (+`.kiro/`), `backend/build.gradle.kts` (SonarQube plugin), and
`backend/gradle/wrapper/gradle-wrapper.properties` (`systemProp.sonar.host.url`).

**Verified on 2026-09-29 (through T3):**
- `./gradlew compileJava compileTestJava`: builds.
- Targeted T3 unit and Testcontainers suites: green.
- Full `./gradlew test`: 56 suites, 411 tests, 0 failures/errors/skips.
- `./gradlew check`: green.

**Implemented (verified in source):**
- Modules `contract`, `payment`, `penalty`, `ledger`, `shared`. `settlement` and `reporting` do not exist.
  `frontend/` is empty.
- Migrations V1–V10: full baseline schema, deferred accounting triggers (V3), state coherence (V4),
  settlement immutability (V5), contract create safety (V6/V7), hardened SYSTEM principal (V8), ShedLock +
  penalty-accrual integrity (V9), and explicit `job_run.business_date` for backfill audit (V10).
- Daily servicing T3 is live: configurable Jakarta cron and explicit backfill entry share one renewable
  ShedLock; every ACTIVE contract is processed atomically in billing → penalty order with five-attempt
  conflict retry, failure isolation, SYSTEM audit, and step-level `job_run` rows.
- Endpoints: `POST /api/v1/contracts`, `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`, and `POST /api/v1/payments`.
- Security: JWT HS256 resource server with default-deny **authentication**. There is **no role-based
  authorization** and no login/refresh/logout (ADR-005).

**Current sprint:** Sprint 4 ("Penalty, Aging & Phase-1 Close") is partly done. C4/D1 and re-planned
T1–T3 are DONE. C5/D3 and T4–T11 remain. The remaining work is planned below as **Sprint 4b** and
**Sprint 4c**.

**Blockers and critical gaps:**
1. **A payment can still resolve an installment before the daily job reaches it.** The T3 job charges
   penalty in production, but the payment path still bills interest without accruing through its business
   date. A late installment paid in full before that day's run can therefore lose elapsed penalty. ADR-012
   residual risk (i) is closed by the lazy trigger in **T4**.
2. **Aging state is not maintained yet.** T3 implements billing → penalty only; the third daily step that
   marks eligible installments OVERDUE is **T6**.
3. **The Addendum §3.4 role matrix is not enforced.** Any valid token can create contracts and post
   payments. PRD §2 already requires "1 role admin + read-only" for Phase 1 → **T7**.

**Specification/implementation discrepancies found:**

| # | Documented | Implemented | Resolution |
|---|---|---|---|
| X-1 | Addendum §5: payment write path retries optimistic-lock failures 3× (50/150/400 ms), then 409 `CONCURRENT_MODIFICATION` | No retry. First conflict → 409. No `spring-retry` dependency or loop | T5 |
| X-2 | TS §2.3 / Addendum §5: daily jobs use ShedLock and retry per record up to 5× | Resolved by T2/T3: V9 lock table; DB-time renewable lock; per-contract atomic billing→penalty with five total attempts | DONE |
| X-3 | Addendum §10: `X-Request-Id`, JSON logs, `request_id` stored on `idempotency_keys` | None. `idempotency_keys.request_id` is not mapped by `IdempotencyKey`. No story in `05_SPRINT_PLAN.md` owns this | T20 (Sprint 6) |
| X-4 | DM §1.4 state machine has no `OVERDUE → PARTIALLY_PAID` edge | `Installment.applyPayment` moves OVERDUE + partial → `PARTIALLY_PAID` | Ambiguity A-5 → T1 |
| X-5 | TS §4.3 / Addendum §6: after a void, add a catch-up delta when expected > recognized | ADR-012 decision 7 re-charges only dates that have **no** row yet. Dates already accrued at a reduced base are never re-priced, and `uk_penalty_accrual` allows only one row per date | Ambiguity A-3 → T1, E4 |
| X-6 | TS §4.3: base is unpaid pokok+bunga | ADR-012 decision 2: penalty already paid also reduces the base (`InstallmentBalance.penaltyBase`) | Ambiguity A-2 → T1, T10 |
| X-7 | DM §1.9 / ADR-012: `penalty_accrual` is append-only | Unlike its sibling append-only tables, it has no immutability trigger. `ck_penalty_accrual_days` allows `0` while the application requires ≥ 1 | T2 |
| X-8 | `06_FRONTEND_SPEC.md §2.7`: statement columns "Debit / Kredit sum per entry" | Every journal entry balances, so both sums are always equal. The column meaning is undefined | Ambiguity A-8 → T1 |
| X-9 | TS §7: repo `serfira-core/`, package `com.multifinance` | Repo `serfira/backend`, package `com.serfira` | Doc-only fix, deferred |

---

## Completed Work

These items are preserved and must not be re-implemented. Detail lives in `05_SPRINT_PLAN.md §5` and the
ADRs listed.

| Story | Title | Status | Reference |
|---|---|---|---|
| A1 | Repo, CI, docker-compose, README, `docs/adr/` | DONE | Sprint 0 |
| A2 | Injectable Clock (Asia/Jakarta), audit base, error envelope | DONE | ADR-003 |
| A3 | Flyway baseline, seeds, COA | DONE | V1 |
| A4 | Business document number generator | DONE | `DocumentNumberGeneratorIT` |
| — | Pre-Sprint-2 hardening: PII at rest, resource server | DONE | ADR-004, ADR-005, V2–V5 |
| B1–B4 | Entities, FLAT/EFFECTIVE engine, due dates, golden tests | DONE | Sprint 1 |
| B5 | Contract create/activate/list/detail/schedule API | DONE | ADR-006, ADR-007, V6, V7 |
| C1 | Ledger posting + disbursement journal at activation | DONE | ADR-008 |
| C2 | Allocation engine | DONE | ADR-009 |
| C3 | `POST /payments` + idempotency | DONE | ADR-010 |
| C4 | Due-date billing + maturity auto-close. **The scheduler half is moved to T3** | DONE | ADR-011 |
| D1 | Penalty calculator + per-date idempotent accrual. **Residual risks moved to T4/T10** | DONE | ADR-012 |
| T1–T3 | Phase-1 decisions, V9 integrity/ShedLock schema, daily billing→penalty job + V10 audit date | DONE | ADR-013, V9/V10 |
| — | Phase A hygiene: V8 SYSTEM hardening, idempotency retention takeover, open-in-view off | DONE | `cdce254` |

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

Status: TODO
Estimate: 2 pts

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
- Regression: remove the manual priming at `PaymentApiIT.java:316-318` and keep the scenario green.

Risks: A job-vs-payment race on the same `(installment_id, accrual_date)` makes the loser fail. T5 is
required before this runs under load.

### T5 — Payment write-path conflict retry (Addendum §5)

Status: TODO
Estimate: 2 pts

Goal: Concurrent payment and job activity on one contract resolves correctly without surfacing avoidable
409s, as documented.

Scope: Retry the whole payment transaction up to 3 times (backoff 50/150/400 ms) on optimistic-lock failure
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

Status: TODO
Estimate: 2 pts

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

## Sprint 4c — Phase-1 Close B: Access Control & Phase-1 Reads

**Goal:** Every endpoint enforces the role matrix, and aging plus the statement are available. Phase 1 exit
(PRD §7: scenarios 1–4 and 8–9, operational demo) is verified.
**Scope:** T7, T8, T9, T11 = 8 pts, plus T10 (3 pts) only if T1.a requires it.

### T7 — RBAC enforcement (F3, pulled forward from Sprint 6b)

Status: TODO
Estimate: 2 pts

Goal: Enforce the Addendum §3.4 endpoint-to-role matrix now, so every endpoint built from here on ships with
its role rule and its 403 tests.

Scope: Map the JWT roles claim to authorities, apply the matrix to existing endpoints, and make unlisted
business endpoints deny by default. The public paths from ADR-005 are unchanged.

Business Rules:
- Current endpoints: `POST /contracts`, `POST /contracts/{id}/activate` and `POST /payments` →
  ADMIN_OPERASIONAL only. `GET /contracts`, `GET /contracts/{id}` and `GET /contracts/{id}/installments` →
  ADMIN_OPERASIONAL, FINANCE, MANAJEMEN.
- `SYSTEM` is never an HTTP role (matrix "—"). A token carrying it is denied.
- Missing or unknown role → 403 `FORBIDDEN` in the standard envelope. An invalid or expired token is still
  401.
- PII masking stays unconditional. That is stricter than Addendum §9, which requires masking only for
  roles other than ADMIN_OPERASIONAL and FINANCE, and it needs no change.

Implementation Notes: The roles claim name is not documented. The existing ITs mint `roles` (A-12). Record
it in an ADR so F2's issuer matches. F2 (login) is not a prerequisite, because tokens are still minted
externally.

Dependencies: none (placed first in the sprint on purpose).

Acceptance Criteria:
- Every existing endpoint returns 2xx or 403 exactly as the matrix specifies.
- A new endpoint with no role rule is denied.
- All existing ITs pass with role-appropriate tokens.

Tests:
- IT: a parameterized matrix (role × endpoint → expected status). No roles claim → 403. `SYSTEM` role →
  403. Expired token → 401. `alg: none` token → 401.

Risks: Wide but mechanical test churn. Every IT that mints a token must choose a role.

### T8 — Aging report (D3)

Status: TODO
Estimate: 2 pts

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

Status: TODO
Estimate: 2 pts

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

Dependencies: T3–T9 (and T10 if it applies).

Acceptance Criteria: Every item above has a passing test. The CI run is green. The Phase-1 exit is recorded
in `05_SPRINT_PLAN.md` / README progress.

Tests: As listed in Scope.

Risks: None.

---

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

| ID | Story | Sprint | Status | Carry-forward requirements from this plan |
|---|---|---|---|---|
| T12 | E1 Settlement quote | 5 | TODO | Before taking the snapshot, bill and accrue through the quote date (accrue-before-resolve; ADR-012 risk ii). ACT/30 golden test (Addendum §8). |
| T13 | E2 Settlement execution | 5 | TODO | Re-bill and re-accrue at execution, then revalidate the snapshot (`STALE_SETTLEMENT_QUOTE`). Decide whether settlement creates one entry or several before adding any `(ref_type, ref_id)` DB index (ADR-008 decision 5). Born with RBAC tests (T7). |
| T14 | E3 Excess → `contract_credit` + apply endpoint | 5 | TODO | Credit applies only to recognized receivable. DB cap on Σ applications ≤ credit amount (invariant 11; not enforced today). |
| T15 | E5 Penalty waive/reduce | 5 | TODO | Replace the native `penalty_adjustment` read in `InstallmentRepository` with a `penalty`-owned port (ADR-010 seam). Subtract adjustments in `sumTotalsByContractId(s)` and in the status derivation of `Installment.applyPayment` (its E5 extension point). Reconcile the gross vs net penalty caps between V3 `assert_installment_amounts` and `assert_payment_allocation_component_caps`. |
| T16 | E4 Void payment + synchronous penalty recalc | 6 | TODO | The void re-pricing rule is resolved by ADR-013 (A-3): no re-pricing of already-accrued dates, catch-up only for dates with no row yet. Make `payment.amount` non-updatable and add parent-side deferred checks (a payment with no allocations, an entry with < 2 lines). Revisit the V3 allocation-total trigger, which counts VOIDED rows (C3 note). Void is blocked when credit was consumed (invariant 16). |
| T17 | F1 Consistency check job | 6 | TODO | Runs after aging in the T3 job, reusing its ShedLock and `job_run` infrastructure. |
| T18 | F4 Idempotency key cleanup job | 6 | TODO | Reuses the T3 job infrastructure. The retention takeover already exists (`reclaimExpired`). |
| T19 | F5 Write-off | 6 | TODO | Accrue through the write-off date first. Require `write_off_recorded_by` for TERMINATED at DB level (V4 checks only the reason). |
| T20 | Observability baseline (Addendum §10) | 6 | TODO | `X-Request-Id` filter + MDC, JSON logs (`timestamp`, `level`, `request_id`, `contract_id`, `module`), write `idempotency_keys.request_id`. Not owned by any story in `05_SPRINT_PLAN.md` (X-3). |
| T21 | F2 Auth: login/refresh/logout, Argon2id, lockout, refresh rotation | 6b | TODO | Issue the roles claim decided in T7. Consider RS256 (ADR-005). Add a production guard against the dev-key fallbacks in `docker-compose.yml`. |
| T22 | G1–G6 Reporting, frontend, demo, outbox | 7–8 | TODO | Unchanged from `05_SPRINT_PLAN.md`. NPL/AR depend on the DPD definition from T1.b. |

### Deferred

| Item | Status | Reason |
|---|---|---|
| PRD C-5 update DRAFT contract | DEFERRED | Already deferred in `05_SPRINT_PLAN.md`. |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring | DEFERRED | P2 / Fase 4 per PRD. |
| Unique `(ref_type, ref_id) WHERE reversal_of_id IS NULL` on `journal_entry` | DEFERRED | ADR-008 decision 5 chose an application-only guard. Every current event has its own DB guard. Revisit in E2/E3/E5 if an event type lacks one. |
| Require auth for OpenAPI/Swagger outside dev | DEFERRED | ADR-005 made it public by design. Revisit with T21. |
| Rate limiting, security headers, CORS policy | DEFERRED | No browser client or public deployment yet. Revisit with G2. |
| Remove the unused Lombok dependency. Move the Sonar host out of `gradle-wrapper.properties` | DEFERRED | Hygiene only. The Sonar change is uncommitted local work owned by the developer. |
| Fix TS §7 repo structure (X-9) | DEFERRED | Documentation only. |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role) | DEFERRED | Deployment concern. No deployment exists yet. |

---

## Planning Notes

### Accrue-before-resolve invariant (proposed for T1.a)

Every flow that lowers an installment's penalty base (payment, settlement, write-off) must first bill and
accrue through its business date in the same transaction. If that holds, the run-time base that
`PenaltyCalculator` uses equals the base that was actually in force on each missing date. With no
resolution in between, the base did not change. So the historical-base concern from the review reduces to
two cases:
- **Void (E4):** the base goes *up* retroactively (A-3).
- **Any resolution path that skips accrual.** Today that is the payment path (T4). Later it would be
  settlement and write-off (T12, T13, T19).

This is why T10 is conditional rather than planned.

### Correction to the 2026-09-29 code review

The review rated run-time pricing (F-01) CRITICAL and "undocumented", and said lazy accrual would not fix
backfill across a missed job window. Both claims were wrong:
- ADR-012 *Consequences* explicitly accepts the run-time base as a conservative trade-off, and records the
  pay-before-run loss as residual risk (i), with lazy accrual deferred to D2.
- Under the invariant above, lazy accrual also covers backfill after a missed window. The loss is real only
  while the payment path skips accrual, which is exactly T4.

Also already documented, not new findings:
- F-05 is ADR-012 decision 2.
- The missing ShedLock table (F-02) is noted in the sprint plan.
- The application-only ledger guard (F-08) is ADR-008 decision 5.
- The dev-key fallbacks (F-03) are ADR-005 / README.

### Ambiguities

| ID | Question | Documents in tension | Affects |
|---|---|---|---|
| A-1 | Is date D charged at the base at the start of D (before resolutions on D)? | TS §4.3 formula; ADR-012 d3 (unspecified) | T1.a, T3, T4, T10, T12, T13, T16 |
| A-2 | Does penalty already paid reduce the pokok+bunga base? | TS §4.3 vs ADR-012 d2 | T1.a, T10 |
| A-3 | After a void, are dates already accrued at a reduced base re-priced, and how, given one row per `(installment, date)`? | TS §4.3 / Addendum §6 vs ADR-012 d7 | T1.a, T16 (possibly schema) |
| A-4 | When does DPD start (due date or due date + grace), and what makes an installment OVERDUE (outstanding > 0? PAID with billed-but-unpaid interest)? | DM §1.4, PRD glossary, Addendum §14 (all silent) | T1.b, T6, T8, T22 |
| A-5 | OVERDUE + partial payment → stay OVERDUE or become PARTIALLY_PAID? | DM §1.4 state machine vs `Installment.applyPayment` | T1.b, T6, T8 |
| A-6 | Aging `as_of`: today only, or historical reconstruction? | FE §2.9 filter vs current-state data model | T1.b, T8 |
| A-7 | Bucket basis (installment outstanding per bucket vs contract max DPD), whether not-yet-due principal counts as Current, ACTIVE-only scope? | PRD glossary, FE §2.9, Addendum §14 | T1.b, T8, T22 |
| A-8 | Statement columns: what debit/credit mean for a balanced entry, which accounts appear, running balance? | FE §2.7 | T1.c, T9, T22 |
| A-9 | `job_run`: one row per step or per run? Units of `records_processed`/`records_failed`? Status after a partial failure? | Addendum §10, V1 comment, `PenaltyAccrualPort` Javadoc | T1.d, T3, T6, T17, T18 |
| A-10 | Confirm that no penalty accrues after maturity close (accrual requires ACTIVE) | DM invariant 17 (silent on penalty) | T1.a, T4 |
| A-11 | Confirm the payment retry of Addendum §5 is still wanted (planned as written in T5) | Addendum §5 vs current 409-immediately behavior | T5 |
| A-12 | JWT roles claim name (`roles` is used only by tests) | ADR-005, Addendum §3 (silent) | T7, T21 |

### Assumptions preserved by this plan

- The business date is always `clock.today()` in Asia/Jakarta (TS §2.0). No backdated payments (Addendum
  §10A).
- Jobs run in-process as `SYSTEM` with no HTTP trigger (Addendum §3.3).
- Settlement, write-off and void are not built before Sprint 4b/4c completes. The penalty semantics decided
  in T1.a must be in place first, because those flows consume `penalty_outstanding` and re-run accrual.
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
T7 RBAC ─► T8, T9
T3…T10 ─► T11 exit verification ─► Sprint 5 (T12–T15) ─► Sprint 6 (T16–T20) ─► T21 ─► T22
```
