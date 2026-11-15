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

**Snapshot:** `main` @ `278c57e`. **Sprint 6 is COMPLETE** — T16 (PR #11), T17 (PR #12), T19 (PR #13), T18
(PR #14), T27a (PR #15) and T31 (PR #16 + review fixes PR #17) are all **merged to `main`**. **Sprint 6b has
started:** T20 (observability baseline) is **DONE and merged** (PR #18, including the post-review
`LogContext`/`CorrelationIdFilter` fixes in `0c05dfe`), and **T21a** (auth foundation) is **DONE and merged
via PR #19** (`278c57e`, including the review fixes in `070e7ad`).

The live backlog is **Sprint 6b** (Phase-2 security boundary + CI hardening): **T20** DONE → **T21a** DONE
→ **T21b** (refresh rotation + lockout) is the frontier, with **T27** (CI & runtime gates) independent
within the sprint. T22 and T28 remain later-sprint work.

**Verified test counts:** full local `./gradlew check` run (2026-11-15, on the merged T21a tree) = 112
suites / 866 tests / 0 failed (the previous T20 baseline was 111 / 856). T21a adds 7 `AuthLoginIT` cases and
3 `/auth/login` matrix cells, so the counts, not just compilation, confirm the new suites ran. CI on `main`
currently reports red for a GitHub billing reason, not a test failure.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `auth`, `shared`.
  `frontend/` is empty.
- Migrations **V1–V23 plus the profile-gated demo seed `R__demo_seed.sql`** (a **repeatable** migration that
  lives in `db/demo` and is applied only by the `demo` profile). V1 baseline + seeds/COA; V2 PII at rest; V3 deferred accounting invariants; V4
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
- Endpoints (16): `POST /api/v1/auth/login`, `POST /api/v1/contracts`,
  `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, `POST /api/v1/payments/{id}/void`,
  `GET /api/v1/reports/aging`, `POST /api/v1/contracts/{id}/credit/apply`,
  `GET /api/v1/contracts/{id}/credit`, `POST /api/v1/contracts/{id}/write-off`,
  `POST /api/v1/penalty-adjustments`, `POST /api/v1/settlements/quote`, `POST /api/v1/settlements`.
- Auth foundation (T21a, ADR-022): new `auth` module with `AppUser`/`RefreshToken` and Argon2id login.
  `POST /api/v1/auth/login` returns a 15-minute HS256 access token (`sub` = active `app_user.id`, `roles`
  string array) plus an opaque refresh token stored only as a SHA-256 hash. The decoder now rejects a
  `sub` that is unknown or inactive (closes T7-D2). Login failure is a generic 401; when the per-instance
  Argon2 capacity guard is saturated it is 503 `AUTHENTICATION_CAPACITY_EXCEEDED` (ADR-022 D9). Demo users
  (`admin`/`finance`/`manager`) are seeded by the repeatable `R__demo_seed.sql` only under the `demo`
  profile.
- Security: JWT HS256 resource server, default-deny, Addendum §3.4 role matrix enforced on all sixteen
  endpoints (T7, ADR-015; every mutation above is ADMIN_OPERASIONAL only). `sub` must be the UUID of an
  active `app_user` with `exp`. No refresh/logout/lockout, no `iss`/`aud` validation yet (ADR-005; T21b).

Full per-task implementation notes for all DONE work are in `tasks-archive.md`; the sprint narrative is in
`PROGRESS.md`.

**Blockers and critical gaps:** none open.

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). X-3 was resolved by T20. **X-14 is open:** `06_FRONTEND_SPEC.md`
assumes login sets the refresh token as an httpOnly cookie while T21a returns it in the response body
(ADR-022 D3/D5); T21b decides the transport. The resolved X-table with per-item detail is in
`tasks-archive.md`.

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
| T21a  | Auth foundation: `app_user` login, Argon2id, 15-min HS256 token, active-user decoder check, demo seed              | ADR-022, PR #19                                         |
| —     | Pre-Sprint hygiene: PII at rest/resource server; V8 SYSTEM; input DoS bounds (ADR-016)                             | ADR-004/005/016, V2–V5, V8                              |

### Sprint summaries (detail in the archive)

- **Sprint 4 — Phase-1 Close (4b/4c/4d):** COMPLETE. T1–T9, T11, T23–T26, T29 DONE; T10 DEFERRED. Detail in
  `tasks-archive.md`.
- **Sprint 5 — Settlement, Credit & Waiver (Epic E):** COMPLETE. T14, T15, T30, T12, T13 DONE (ADR-018,
  ADR-019). Detail in `tasks-archive.md`.
- **Sprint 6 — Void, Consistency & Write-off:** COMPLETE. T16, T17, T19, T18 DONE and merged (ADR-020,
  PR #14); T27a DONE and merged (PR #15); T31 DONE and merged (PR #16 + review fixes PR #17). No
  implementation work remains.
- **Sprint 6b — Phase-2 security boundary + CI hardening:** IN PROGRESS. T20 (observability baseline, PR #18)
  and T21a (auth foundation, PR #19) are DONE and merged; T21b and T27 are the remaining tickets below.

---

## Open tasks — Sprint 6b (Phase-2 security boundary + CI hardening)

**Goal (05_SPRINT_PLAN.md Sprint 6b):** a clear security boundary before the frontend is built — real
authentication (login/refresh/logout, Argon2id, brute-force lockout), `created_by` sourced from a real
`app_user`, and CI/runtime gates. The default-deny RBAC role matrix on sixteen endpoints is already live
(T7, ADR-015), and T21a supplied the identity *source* those matchers authorize against (ADR-022).

**Dependency order:** T21a (DONE) → T21b. T27 is independent within the sprint.

**Estimated load:** T21b (3) + T27 (2) = **5 pts**.

Business rules live in `04_GAPS_ADDENDUM.md` §3, §10 and the cited ADRs; these tasks point to them and do
not restate them.

### T21b — Refresh rotation, logout, brute-force lockout, dev-key guard (3 pts)

**Status:** TODO
**Estimate:** 3 pts
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

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

T21b and T27 remain the active **Sprint 6b** backlog (see "Open tasks" above); T20 and T21a are DONE and
merged. This table carries only the later, not-yet-active sprints.

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
| Require auth for OpenAPI/Swagger outside dev                                                | ADR-005 made it public by design. Revisit with T21b (or G2, once a browser client exists). |
| Rate limiting, security headers, CORS policy                                                | No browser client or public deployment yet. Revisit with G2.                     |
| SonarQube/SonarCloud in CI                                                                  | Decided out of scope for T27 (2026-11-07): needs a running server, an external dependency not worth it for this portfolio repo. The `org.sonarqube` plugin stays local/manual, never wired into `check` or CI. |
| Envelope-rendering `ErrorController` for container-level errors                             | Boot's default JSON leaks no internals. Low value until a browser client exists. |
| Reconcile the V12 `assert_payment_has_allocations` backstop vs consistency check B's end-to-end IT | V12 (DEFERRABLE, rejects Σallocations≠amount at COMMIT, "zero included") makes check B's drift unstageable in the DB, so `ConsistencyCheckIT` only exercises check A; check B is covered at the engine level (`ConsistencyCheckEngineTest`). Decide whether check B needs an end-to-end guard given V12 already forecloses its drift, or document it as engine-only by design. Test-only follow-up; no production change implied. |
| Fix TS §7 repo structure (X-9)                                                              | Documentation only.                                                              |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role)             | Deployment concern. No deployment exists yet.                                    |
| `refresh_token` retention / cleanup job                                                     | Rotation inserts a row per refresh and revoked rows are never deleted (~96 rows per user per day at a 15-minute access token). Same shape as T18's keyset delete on `expires_at`, own ShedLock lock, one `job_run` row. Not needed for T21b; schedule it with the reporting work. |

### T10 — Component-exact penalty base (conditional, DEFERRED)

DEFERRED — T1.a / ADR-013 kept ADR-012 decision 2 unchanged, so no code change is required unless a **new**
business decision explicitly demands component-exact penalty precision (base from unpaid principal +
recognized interest only, excluding penalty already paid; and/or a per-date historical base). If taken, this
reopens at ~3 pts: carry the component split (and business dates, if a historical base is chosen) across the
`contract` port, change `InstallmentBalance.penaltyBase`, and prove the next-day charge after a penalty-only
payment equals `rate × unpaid (principal + recognized interest)` (worked check: 1,573.33, not 1,570.19).
Depends on T1.a, T4. ADR-012 alternatives 5/6 list the rejected options.

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
tail is Sprint 6b (T21b, plus T27) and later (T22, T28). One decision is deferred to implementation inside its
task, non-blocking: the OWASP Dependency-Check severity threshold + suppression policy (T27). The T21b
lockout threshold/window is settled at 5 attempts / 15 minutes, configurable via `serfira.auth.lockout.*`
(Decision 2026-11-15). The F2 auth decisions recorded so far are in **ADR-022** (T21a: Argon2id, 15-min HS256,
active-user decoder check, hashed refresh tokens, and the **D9** Argon2 concurrency cap → 503
`AUTHENTICATION_CAPACITY_EXCEEDED`); rotation/lockout/transport remain T21b.

**A-14 (resolved by T21b, 2026-11-15) — Lombok and entity conventions in `auth.domain`.** `AppUser` used
Lombok `@Getter`/`@Setter`, an implicit public no-arg constructor and no validating constructor, while
`.kiro/steering/tech.md` ("Lombok … not used") and `.kiro/steering/structure.md` ("no Lombok"; entities need a
`protected` no-arg constructor plus a validating public constructor and intention-revealing methods) say
otherwise. T21b edits `AppUser` for lockout behaviour anyway, so it moves to project style rather than
amending the steering rules for one entity.

### Dependency order (open tail)

```text
T26 job robustness ─► T18 (idempotency cleanup) — DONE (PR #14)
ADR-020 D3 (all-status uq_payment_idempotency) ─► T18 (voided key stays spent after cleanup)
T16 (void) + T18 ─► T31 (durable void key) — DONE (PR #16 + PR #17), closes Sprint 6
T27a (CI check gate) — DONE and merged (PR #15)

Sprint 6b (active):
  T20 (observability) — DONE (PR #18)
  T20 ─► T21a (auth foundation) — DONE (PR #19) ─► T21b (refresh rotation, logout, lockout,
                                                      dev-key guard, refresh-token transport X-14)
  T27a ─► T27 (OWASP Dependency-Check + gitleaks + Compose smoke)   [independent within 6b]

  T21b ─► T22 (frontend login), T28 (key rotation)
```
