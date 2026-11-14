# Serfira tasks

Canonical implementation backlog. From Sprint 4b onward this file supersedes the per-sprint allocation in
`05_SPRINT_PLAN.md`. The story IDs used there (A1…G6) are kept as cross-references. Business rules stay in
`01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → ADRs. This file points to
those documents and does not restate them.

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`.
Estimates use the sprint plan's points (1 pt ≈ 2–3 h; target velocity 8–13 pts per sprint).

Completed-task detail (full implementation notes for DONE work, the resolved X-table, the CR-01…CR-16
review table, and historical verification snapshots) is archived in `tasks-archive.md`; `PROGRESS.md` is the
sprint-by-sprint narrative. This file keeps the live backlog: the current-state snapshot, the compact
Completed Work index, open tasks, and open ambiguities.

---

## Current Project State

**Snapshot:** `main` @ `92a10aa`. **Sprint 6 is COMPLETE** — T16 (PR #11), T17 (PR #12), T19 (PR #13), T18
(PR #14), T27a (PR #15) and T31 (PR #16 + review fixes PR #17) are all **merged to `main`**. **Sprint 6b
has started:** T20 (observability baseline) is **DONE and merged to `main`** (PR #18, including the
post-review `LogContext`/`CorrelationIdFilter` fixes in `0c05dfe`).

The live backlog is **Sprint 6b** (Phase-2 security boundary + CI hardening): **T20** (observability) DONE
→ **T21a** (auth foundation) is the frontier → **T21b** (refresh rotation + lockout), with **T27** (CI &
runtime gates) independent within the sprint. T22 and T28 remain later-sprint work.

**Verified test counts:** last full local run (2026-11-07, T20 branch) = 111 suites / 856 tests / 0 failed.
CI on `main` currently reports red for a GitHub billing reason, not a test failure; treat the local run as
the verification record until CI is restored.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `shared`. `frontend/` is
  empty.
- Migrations **V1–V23**. V1 baseline + seeds/COA; V2 PII at rest; V3 deferred accounting invariants; V4
  record-state coherence; V5 settlement immutability; V6/V7 contract-create safety; V8 SYSTEM hardening;
  V9 ShedLock + penalty-accrual integrity; V10 `job_run.business_date`; V11 statement index;
  V12 accounting backstops; V13 `ABANDONED` status; V14 credit-application cap; V15 penalty-waiver
  accounting (`BEBAN_WAIVER_DENDA` + cap); V16 penalty-adjustment cap includes paid; V17 settlement
  backstops; V18 all-status `uq_payment_idempotency`; V19 `reconciliation_exception` OPEN uniqueness (T17);
  V20 write-off requires `write_off_recorded_by` (T19); V21 `idx_idempotency_keys_expires_at` for the T18
  cleanup job's keyset delete; V22 `payment_void_idempotency` durable void-key backstop
  (`uq_payment_void_idempotency`, T31); V23 `void_excludes_allocations_from_caps` (both cap triggers
  count POSTED allocations only, T31/ADR-021). (T19's migration was authored as V19 on its branch;
  because T17's V19 merged first, T19 renumbered forward-only to V20 at merge.)
- Daily servicing (T3, hardened T26, extended T17): one renewable ShedLock for cron + backfill; every
  ACTIVE contract processed atomically **billing → penalty → aging → consistency-check**, with backoff
  retry, failure isolation, SYSTEM audit, four `job_run` rows; crashed `RUNNING` rows closed `ABANDONED`;
  keyset paging. The consistency-check step is read-only reconciliation that records drift to
  `reconciliation_exception` and never corrects money.
- Payment path: lazy billing + accrual inside the idempotent transaction (T4, ADR-014); write-path conflict
  retry → 409 `CONCURRENT_MODIFICATION` (T5); single-use-forever idempotency keys → 409
  `IDEMPOTENCY_KEY_EXPIRED` past retention (T24, ADR-017); void (T16, ADR-020). A void key is now
  single-use-forever too, backed by the durable `payment_void_idempotency` table (V22, T31) so it stays
  spent after the T18 cleanup deletes its claim row.
- Idempotency-key cleanup (T18, F4): a second scheduled job in `shared/idempotency`, `job_name`
  `idempotency-cleanup`, on its own ShedLock lock, deletes `idempotency_keys` rows past `expires_at` in
  keyset pages (one `job_run` row, A-9), backed by V21's `expires_at` index. Pure hygiene: a deleted spent
  key stays spent via the permanent backstops, so no permanent-key probe was added (ADR-017 2026-10-11).
- Observability baseline (T20, Addendum §10, X-3): `X-Request-Id` correlation filter ahead of Spring
  Security (new `shared/observability` package), Boot 4 native ECS JSON logs with `request_id` /
  `contract_id` / `module` carried via the MDC, and `idempotency_keys.request_id` written at claim time so
  a replayed retry is traceable to its originating request.
- Settlement (`settlement` module): quote (T12) and execution (T13), ADR-018. Credit (T14) and penalty
  waive/reduce (T15); effective penalty owned by `penalty` behind `EffectivePenaltyPort` (T15/T30, ADR-019).
- Write-off (T19, F5): accrue-before-resolve, pure `WriteOffEngine` caps recognized receivable, open
  installments → `WRITTEN_OFF`, contract `ACTIVE → TERMINATED`, one balanced `WRITE_OFF` journal.
- Endpoints (15): `POST /api/v1/contracts`, `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, `POST /api/v1/payments/{id}/void`,
  `GET /api/v1/reports/aging`, `POST /api/v1/contracts/{id}/credit/apply`,
  `GET /api/v1/contracts/{id}/credit`, `POST /api/v1/contracts/{id}/write-off`,
  `POST /api/v1/penalty-adjustments`, `POST /api/v1/settlements/quote`, `POST /api/v1/settlements`.
- Security: JWT HS256 resource server, default-deny, Addendum §3.4 role matrix enforced on all fifteen
  endpoints (T7, ADR-015; every mutation above is ADMIN_OPERASIONAL only). `sub` must be a UUID with `exp`.
  No login/refresh/logout, no `iss`/`aud` validation yet (ADR-005; T21).

Full per-task implementation notes for all DONE work are in `tasks-archive.md`; the sprint narrative is in
`PROGRESS.md`.

**Blockers and critical gaps:** none open.

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). X-3 was resolved by T20. The resolved X-table with per-item detail is
in `tasks-archive.md`.

---

## Completed Work

These items are DONE and must not be re-implemented. Long-form notes are in `tasks-archive.md`; decisions
are in the cited ADRs; the sprint narrative is in `PROGRESS.md`.

| Story | Title                                                                                                              | Reference / branch                                      |
| ----- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------- |
| A1–A4 | Repo/CI/compose/README; Clock + audit + error envelope; Flyway baseline + COA; doc-number gen                      | Sprint 0, ADR-003, V1                                   |
| B1–B5 | Entities, FLAT/EFFECTIVE engine, due dates, golden tests; contract create/activate/list/detail                     | Sprint 1–2, ADR-006/007, V6/V7                          |
| C1–C4 | Ledger posting + disbursement journal; allocation engine; `POST /payments` + idempotency; billing + maturity close | Sprint 3, ADR-008/009/010/011                           |
| D1    | Penalty calculator + per-date idempotent accrual                                                                   | ADR-012, ADR-014                                        |
| T1–T6 | Phase-1 decisions; V9 integrity/ShedLock; daily billing→penalty→aging job; lazy accrual; payment retry; aging step | ADR-013, ADR-014, V9/V10                                |
| T23   | Spring Boot 4 dependency alignment (ShedLock 7.10.1, springdoc 3.1.1)                                              | PR #1, `OpenApiSmokeIT`                                 |
| T7    | RBAC enforcement + JWT identity hardening                                                                          | ADR-015                                                 |
| T8    | Aging report (`GET /api/v1/reports/aging`): read-only `reporting` module                                           | ADR-013 impl note T8                                    |
| T9    | Contract statement (`GET /api/v1/contracts/{id}/statement`): ledger read port, V11                                 | ADR-013 impl note T9                                    |
| T24   | Idempotency key lifecycle: single-use forever, 409 `IDEMPOTENCY_KEY_EXPIRED`                                       | ADR-017                                                 |
| T25   | DB accounting backstops: V12 parent-side checks, scoped `uq_journal_entry_event`                                   | ADR-008 d5 note                                         |
| T26   | Daily job robustness: `ABANDONED` + V13, keyset batching, retry backoff                                            | ADR-013 A-9 note T26                                    |
| T29   | Fix contract statement query on PostgreSQL (X-13, `42P18`)                                                         | X-13 bug task                                           |
| T11   | Phase-1 test hardening + exit verification                                                                         | commit `a8fa1a6`                                        |
| T14   | E3 Excess → `contract_credit` + credit-apply/read; V14 cap/status                                                  | ADR-009, `t14-contract-credit`                          |
| T15   | E5 Penalty waive/reduce + `EffectivePenaltyPort`; V15/V16                                                          | ADR-019, `t15-penalty-waive-effective-port`             |
| T30   | Route schedule + aging through effective penalty (ADR-019 D4, PR #7/#8 findings)                                   | ADR-019 D4, `t30-effective-penalty-reads`               |
| T12   | E1 Settlement quote: new `settlement` module, `SettlementQuoteEngine`, `POST /settlements/quote`                   | ADR-018, `t12-settlement-quote`                         |
| T13   | E2 Settlement execution: `POST /settlements`, SETTLEMENT journal, V17                                              | ADR-018, PR #10                                         |
| T16   | E4 Payment void: `POST /payments/{id}/void`, reversal journal, recompute, V18                                      | ADR-020, PR #11                                         |
| T17   | F1 Consistency-check job: fourth daily step, read-only reconciliation, V19                                         | Addendum §7.1, PR #12                                   |
| T18   | F4 Idempotency key cleanup job: daily delete of expired `idempotency_keys`, one `job_run` row, V21 index           | ADR-017, ADR-020 D3, `t18-idempotency-cleanup`          |
| T19   | F5 Write-off: `POST /contracts/{id}/write-off`, `WriteOffEngine`, V20                                              | DM §1.12, ADR-013 §1, PR #13                            |
| T27a  | Minimal CI gate: `ci.yml` runs `./gradlew check` instead of `test`                                                 | `t27a-ci-check-gate`, PR #15                            |
| T31   | Durable void idempotency key: `payment_void_idempotency` side table, V22, closes ADR-020 finding #5                | ADR-017, ADR-020 D3, `t31-durable-void-idempotency-key` |
| T20   | Observability baseline: `X-Request-Id` filter, ECS JSON logs, `request_id` on idempotency claims (X-3)             | Addendum §10, `t20-observability-baseline`, PR #18      |
| —     | Pre-Sprint hygiene: PII at rest/resource server; V8 SYSTEM; input DoS bounds (ADR-016)                             | ADR-004/005/016, V2–V5, V8                              |

---

## Sprints 4–6 (completed work): see the archive

- **Sprint 4 — Phase-1 Close (4b/4c/4d):** COMPLETE. T1–T9, T11, T23–T26, T29 DONE; T10 DEFERRED. Detail in
  `tasks-archive.md`.
- **Sprint 5 — Settlement, Credit & Waiver (Epic E):** COMPLETE. T14, T15, T30, T12, T13 DONE (ADR-018,
  ADR-019). Detail in `tasks-archive.md`.
- **Sprint 6 — Void, Consistency & Write-off:** COMPLETE. T16, T17, T19, T18 DONE and merged (ADR-020,
  PR #14); T27a DONE and merged (PR #15); T31 DONE and merged (PR #16 + review fixes PR #17). No implementation work remains. Observability (T20), auth (T21a/T21b) and the heavier CI/runtime
  gates (T27) are the Sprint 6b backlog below.

### T10 — Component-exact penalty base (conditional, DEFERRED)

DEFERRED — T1.a / ADR-013 kept ADR-012 decision 2 unchanged, so no code change is required unless a **new**
business decision explicitly demands component-exact penalty precision (base from unpaid principal +
recognized interest only, excluding penalty already paid; and/or a per-date historical base). If taken, this
reopens at ~3 pts: carry the component split (and business dates, if a historical base is chosen) across the
`contract` port, change `InstallmentBalance.penaltyBase`, and prove the next-day charge after a penalty-only
payment equals `rate × unpaid (principal + recognized interest)` (worked check: 1,573.33, not 1,570.19).
Depends on T1.a, T4. ADR-012 alternatives 5/6 list the rejected options.

---

## Open tasks — Sprint 6b (Phase-2 security boundary + CI hardening)

**Goal (05_SPRINT_PLAN.md Sprint 6b):** a clear security boundary before the frontend is built — real
authentication (login/refresh/logout, Argon2id, brute-force lockout), `created_by` sourced from a real
`app_user`, and CI/runtime gates. The default-deny RBAC role matrix on all fifteen endpoints is already live
(T7, ADR-015); Sprint 6b adds the identity *source* those matchers authorize against.

**Dependency order:** T20 → T21a → T21b. T27 is independent within the sprint. Do T20 first: request-id
plumbing is low-risk and makes every later auth flow traceable.

**Estimated load:** T20 (3) + T21a (3) + T21b (2) + T27 (2) = **10 pts** (within the 8–13 target).

Business rules live in `04_GAPS_ADDENDUM.md` §3, §10 and the cited ADRs; these tasks point to them and do
not restate them.

### T20 — Observability baseline (3 pts)

**Status: DONE** (branch `t20-observability-baseline`, merged to `main` via PR #18). Full implementation
note is under "Recently completed" below; the one-line summary is in Completed Work. Resolves discrepancy
**X-3**. Not owned by any `05_SPRINT_PLAN.md` story; source is Addendum §10.

### T21a — Auth foundation: app_user, login, JWT identity binding (3 pts)

**Status:** TODO
**Estimate:** 3 pts

**Goal:** Issue real tokens from real users so `created_by`/`updated_by` carry a genuine `app_user.id`
instead of a raw `sub`, realising the login half of story **F2** and closing the T7-D2 deferred active-user
check.

**Scope.** Introduce users and token issuance.

- **Schema.** No new schema migration — the `app_user` and `refresh_token` tables already exist in
  `V1__baseline.sql` (Addendum §3.1). V1 seeds only the non-interactive `SYSTEM` principal; the demo login
  users (`admin`/`finance`/`manager`, Addendum §18.3) still need a separate profile-gated
  `V99__demo_seed.sql` with Argon2id hashes (Addendum §18.4) — it does not exist yet, so add it here.
- **`POST /api/v1/auth/login`** `{username, password}` → `{access_token, refresh_token}`. Access token is a
  15-minute HS256 JWT (ADR-005 decoder bean, unchanged) whose `sub` is the `app_user.id` UUID and whose
  `roles` is a **string array** (ADR-015). Refresh-token *issuance* here stores a hashed token (T21b owns
  rotation). Verify the password with Argon2id; on success reset `failed_login_attempts` and set
  `last_login_at`.
- **Bind identity to a real user (closes T7-D2, Addendum §3.3).** The resource server requires `sub` to be
  the UUID of an **active** `app_user` (`is_active = true`); a `sub` that is unknown or inactive → 401. The
  audit-actor binding continues to bind `sub` to the audit context, so `created_by`/`updated_by` carry a
  real `app_user.id`. `SYSTEM` still never acts over HTTP (ADR-015 D4/D5).
- **Role matrix.** Add `POST /auth/login` to the matrix (all three business roles allowed; §3.4). `/login`
  is unauthenticated to *reach* (it mints the first token) but still goes through the matcher table ending
  in `denyAll()` — add its row plus a test. `/auth/refresh` and `/auth/logout` rows come with T21b.

**Business Rules.** Argon2id only; never store or log plaintext passwords or hashes (steering `60-security`,
TS §2.5). Never trust identity from a request body — the audit actor comes from the authenticated context
(ADR-015, Addendum §3.3). Access-token lifetime is 15 minutes (Addendum §3.3). Default-deny stays: a new
endpoint is denied until it has a matrix row plus allowed-role and 403 tests in `EndpointRoleMatrixIT`.

**Implementation Notes.** Owner: new `auth` (or `shared/security`) module per the module layout rules.
Source: Addendum §3.1–3.4, ADR-005, ADR-015. Add an Argon2id password encoder (Spring Security
`Argon2PasswordEncoder`; it pulls BouncyCastle, a new dependency to justify in the PR). Record the F2 auth
decisions (Argon2id, 15-min HS256) in a new ADR when this lands.

**Dependencies:** T20 (DONE), T7/ADR-015 (DONE — resource server + role matrix enforced).

**Acceptance Criteria.** A seeded user logs in and receives a 15-min access token with `sub = app_user.id`
and a `roles` string array, plus a refresh token persisted hashed. A token whose `sub` is not an active
`app_user` → 401. A financial write after login records `created_by = app_user.id`, not the raw claim string.
`/auth/login` has a matrix row and role tests.

**Tests.** IT (Testcontainers + MockMvc + `spring-security-test`): login happy path returns both tokens and
the expected claims; wrong password → 401; a JWT with an unknown/inactive `sub` → 401; a mutation after
login stamps the real `app_user.id` in `created_by`; `EndpointRoleMatrixIT` extended for `/auth/login`. Use
`TestJwts`/`FixedClock`; do not assert on Argon2 hash values, only on verify behaviour.

**Risks.** Making `sub` resolve to an active `app_user` tightens token acceptance for every endpoint; the
existing matrix ITs mint `sub`s that must now correspond to seeded active users, so `TestJwts`/fixtures may
need aligning to avoid mass 401s.

### T21b — Refresh rotation, logout, brute-force lockout, dev-key guard (2 pts)

**Status:** TODO
**Estimate:** 2 pts

**Goal:** Complete story **F2** with the stateful half of auth — rotating refresh tokens, logout,
brute-force lockout — plus a production guard against the dev-only key fallbacks.

**Scope.**

- **`POST /api/v1/auth/refresh`** — rotate: validate the presented refresh token against its stored hash and
  expiry, issue a new access + new refresh token, revoke the old row (`revoked_at`) and point it at the new
  one via `replaced_by_id`. A reused/revoked refresh token is rejected (detect replay). Never stateless-only
  for refresh (Addendum §3.3).
- **`POST /api/v1/auth/logout`** — revoke the presented refresh token (`revoked_at`); idempotent.
- **Brute-force lockout (Addendum §3.1/§3.3).** On failed login increment `failed_login_attempts`; past a
  threshold set `locked_until`; a locked account is rejected until the window passes; a success resets the
  counter.
- **Dev-key production guard (CR-16).** Fail fast at startup in a production profile if the JWT/PII keys are
  still the documented `docker-compose` dev defaults, so a deployment cannot silently run on demo keys.
- **Role matrix.** Add `/auth/refresh` and `/auth/logout` rows (§3.4) with allowed-role and 403 tests.

**Business Rules.** Refresh tokens are stored hashed and single-use via rotation; a revoked or
already-rotated token never mints a new access token (replay detection). Logout must not error on an
already-revoked token. Lockout state lives on `app_user` (`failed_login_attempts`, `locked_until`). The
dev-key guard must not weaken dev ergonomics — it only trips under the production profile.

**Implementation Notes.** Owner: same `auth` module as T21a. Source: Addendum §3.2–3.3, §18, ADR-005,
CR-10/CR-16. The lockout threshold/window is the one open decision — propose 5 attempts / 15 min; fix it in
the T21a/T21b auth ADR at implementation (tracked as the open ambiguity in Planning Notes, non-blocking).

**Dependencies:** T21a (app_user, refresh_token, login all exist).

**Acceptance Criteria.** A refresh rotates both tokens, revokes the old, and links `replaced_by_id`; a
reused old refresh token is rejected. Logout revokes and is idempotent. N failed logins lock the account for
the window; a correct login afterward is still blocked until it expires; a success resets the counter. Under
the production profile, startup fails if the keys are the dev defaults. `/auth/refresh` and `/auth/logout`
have matrix rows and role tests.

**Tests.** IT (Testcontainers): rotation happy path + old-token-rejected replay; logout idempotency; lockout
after N failures and reset on success (`FixedClock` drives `locked_until`); a slice/context test that the
production profile with dev keys fails to start. Extend `EndpointRoleMatrixIT` for the two endpoints.

**Risks.** The production-profile startup guard can break CI or local `prod`-profile runs if the test
harness supplies dev keys; scope the guard to a real production profile and keep the test/dev profiles out
of its reach.

### T27 — CI & runtime gates: dependency + secret scan + Compose smoke (2 pts)

**Status:** TODO
**Estimate:** 2 pts

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

---

## Recently completed (full implementation notes)

These Sprint 6 / 6b tasks are **DONE**; the notes are retained here for traceability and will migrate to
`tasks-archive.md` at the next archive pass. They must not be re-implemented.

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

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

T20, T21a, T21b and T27 were promoted into the active **Sprint 6b** backlog (see "Open tasks" above); this
table now carries only the later, not-yet-active sprints.

| ID  | Story                                     | Sprint | Status | Carry-forward requirements                                                                                                                                                                                              |
| --- | ----------------------------------------- | ------ | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T22 | G1–G6 Reporting, frontend, demo, outbox   | 7–8    | TODO   | Unchanged from `05_SPRINT_PLAN.md`. NPL/AR depend on the DPD definition from T1.b.                                                                                                                                       |
| T28 | PII / HMAC key rotation (CR-11)           | 7      | TODO   | Key id in the PII envelope (`v2:<kid>:…`), a key ring for decrypt and HMAC lookups, and a re-keying procedure for `nik_hash`/`phone_lookup` and `idempotency_keys.request_hash` (ADR-004, ADR-007 consequences). New ADR. |

> **iss/aud + RS256 (CR-10) and the T7-D2 active-user check** were part of the old T21 carry-forward. The
> active-user check is now folded into **T21a**. `iss`/`aud` validation and an RS256 migration stay deferred
> until a real external token issuer exists (ADR-005) — not needed while login mints HS256 tokens in-process;
> revisit when an issuer is introduced.

### Deferred

| Item                                                                                        | Reason                                                                           |
| ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| PRD C-5 update DRAFT contract                                                               | Already deferred in `05_SPRINT_PLAN.md`.                                         |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring                     | P2 / Fase 4 per PRD.                                                             |
| Require auth for OpenAPI/Swagger outside dev                                                | ADR-005 made it public by design. Revisit with T21a/T21b.                        |
| Rate limiting, security headers, CORS policy                                                | No browser client or public deployment yet. Revisit with G2.                     |
| SonarQube/SonarCloud in CI                                                                  | Decided out of scope for T27 (2026-11-07): needs a running server, an external dependency not worth it for this portfolio repo. The `org.sonarqube` plugin stays local/manual, never wired into `check` or CI. |
| Remove the unused Lombok dependency. Move the Sonar host out of `gradle-wrapper.properties` | Hygiene only. The Sonar change is uncommitted local work owned by the developer. |
| Envelope-rendering `ErrorController` for container-level errors                             | Boot's default JSON leaks no internals. Low value until a browser client exists. |
| Reconcile the V12 `assert_payment_has_allocations` backstop vs consistency check B's end-to-end IT | V12 (DEFERRABLE, rejects Σallocations≠amount at COMMIT, "zero included") makes check B's drift unstageable in the DB, so `ConsistencyCheckIT` only exercises check A; check B is covered at the engine level (`ConsistencyCheckEngineTest`). Decide whether check B needs an end-to-end guard given V12 already forecloses its drift, or document it as engine-only by design. Test-only follow-up; no production change implied. |
| Fix TS §7 repo structure (X-9)                                                              | Documentation only.                                                              |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role)             | Deployment concern. No deployment exists yet.                                    |

---

## Planning Notes

### Accrue-before-resolve invariant (ADR-013; implemented for payment T4, settlement T12/T13, write-off T19)

Every flow that lowers an installment's penalty base (payment, settlement, write-off) must first bill and
accrue through its business date in the same transaction, so the run-time base equals the base actually in
force on each missing date. With no resolution in between, the base did not change. The historical-base
concern therefore reduces to:

- **Void (E4):** the base goes _up_ retroactively (A-3); ADR-020 D2 accepts the resulting undercharge on
  already-accrued dates and does not re-price them.
- **A resolution path that still skips accrual:** none remains — payment (T4), settlement (T12/T13) and
  write-off (T19) all run accrue-before-resolve. This is why T10 is conditional, not planned.

### Review history (archived)

The 2026-09-29 code review and the 2026-09-30 external review (16 findings, CR-01…CR-16) are resolved or
mapped to tasks. The full CR verdict table is in `tasks-archive.md`. The still-open findings map to TODO
tasks: CR-10 → deferred (RS256/`iss`/`aud`, needs a real issuer), CR-16 → T21b (dev-key production guard),
CR-11 → T28, CR-15 → T27. CR-14 was closed by T15. All others are DONE.

### Ambiguities

Resolved items (A-11, A-12, A-13) and the fully-resolved Phase-1 decisions (A-1…A-10) are recorded in
ADR-012, ADR-013, ADR-015 and ADR-017; detail is in `tasks-archive.md` (T1). Sprint 6 is DONE, so the open
tail is Sprint 6b (T20 → T21a → T21b, plus T27) and later (T22, T28). Two decisions are deferred to
implementation inside their tasks, neither blocking: the brute-force lockout threshold/window (T21b proposes
5 attempts / 15 min, to be fixed in an ADR note) and the OWASP Dependency-Check severity threshold +
suppression policy (T27). A new ADR should record the F2 auth decisions (Argon2id, 15-min HS256, hashed
rotating refresh tokens) when T21a lands.

### Dependency order (open tail)

```text
T26 job robustness ─► T18 (idempotency cleanup)
ADR-020 D3 (all-status uq_payment_idempotency) ─► T18 (voided key stays spent after cleanup)
T16 (void) + T18 ─► T31 (durable void key) — DONE, closes Sprint 6
T27a (CI check gate) — DONE and merged (PR #15)

Sprint 6b (active):
  T20 (observability) ─► T21a (auth foundation: app_user, login, identity binding) ─► T21b (refresh rotation,
                         logout, lockout, dev-key guard)
  T27a ─► T27 (OWASP Dependency-Check + gitleaks + Compose smoke)   [independent within 6b]

  T21b ─► T22 (frontend login), T28 (key rotation)
```
