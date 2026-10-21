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

**Snapshot:** `main` @ `1efd888`. Sprint 6 work T16 (payment void, PR #11), T17 (consistency-check job,
PR #12) and T19 (write-off, PR #13) are all **merged to `main`**. The remaining Sprint 6 tasks are T18
(idempotency-key cleanup job), T27a (minimal `./gradlew check` CI gate) and T31 (durable void idempotency
key) — all TODO.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `shared`. `frontend/` is
  empty.
- Migrations **V1–V20**. V1 baseline + seeds/COA; V2 PII at rest; V3 deferred accounting invariants; V4
  record-state coherence; V5 settlement immutability; V6/V7 contract-create safety; V8 SYSTEM hardening;
  V9 ShedLock + penalty-accrual integrity; V10 `job_run.business_date`; V11 statement index;
  V12 accounting backstops; V13 `ABANDONED` status; V14 credit-application cap; V15 penalty-waiver
  accounting (`BEBAN_WAIVER_DENDA` + cap); V16 penalty-adjustment cap includes paid; V17 settlement
  backstops; V18 all-status `uq_payment_idempotency`; V19 `reconciliation_exception` OPEN uniqueness (T17);
  V20 write-off requires `write_off_recorded_by` (T19). (T19's migration was authored as V19 on its branch;
  because T17's V19 merged first, T19 renumbered forward-only to V20 at merge.)
- Daily servicing (T3, hardened T26, extended T17): one renewable ShedLock for cron + backfill; every
  ACTIVE contract processed atomically **billing → penalty → aging → consistency-check**, with backoff
  retry, failure isolation, SYSTEM audit, four `job_run` rows; crashed `RUNNING` rows closed `ABANDONED`;
  keyset paging. The consistency-check step is read-only reconciliation that records drift to
  `reconciliation_exception` and never corrects money.
- Payment path: lazy billing + accrual inside the idempotent transaction (T4, ADR-014); write-path conflict
  retry → 409 `CONCURRENT_MODIFICATION` (T5); single-use-forever idempotency keys → 409
  `IDEMPOTENCY_KEY_EXPIRED` past retention (T24, ADR-017); void (T16, ADR-020).
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

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-3** (observability:
`X-Request-Id`, JSON logs, `idempotency_keys.request_id` — open, owned by T20) and **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). The resolved X-table with per-item detail is in `tasks-archive.md`.

---

## Completed Work

These items are DONE and must not be re-implemented. Long-form notes are in `tasks-archive.md`; decisions
are in the cited ADRs; the sprint narrative is in `PROGRESS.md`.

| Story | Title                                                                                                              | Reference / branch                          |
| ----- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------- |
| A1–A4 | Repo/CI/compose/README; Clock + audit + error envelope; Flyway baseline + COA; doc-number gen                      | Sprint 0, ADR-003, V1                       |
| B1–B5 | Entities, FLAT/EFFECTIVE engine, due dates, golden tests; contract create/activate/list/detail                     | Sprint 1–2, ADR-006/007, V6/V7              |
| C1–C4 | Ledger posting + disbursement journal; allocation engine; `POST /payments` + idempotency; billing + maturity close | Sprint 3, ADR-008/009/010/011               |
| D1    | Penalty calculator + per-date idempotent accrual                                                                   | ADR-012, ADR-014                            |
| T1–T6 | Phase-1 decisions; V9 integrity/ShedLock; daily billing→penalty→aging job; lazy accrual; payment retry; aging step | ADR-013, ADR-014, V9/V10                    |
| T23   | Spring Boot 4 dependency alignment (ShedLock 7.10.1, springdoc 3.1.1)                                              | PR #1, `OpenApiSmokeIT`                     |
| T7    | RBAC enforcement + JWT identity hardening                                                                          | ADR-015                                     |
| T8    | Aging report (`GET /api/v1/reports/aging`): read-only `reporting` module                                           | ADR-013 impl note T8                        |
| T9    | Contract statement (`GET /api/v1/contracts/{id}/statement`): ledger read port, V11                                 | ADR-013 impl note T9                        |
| T24   | Idempotency key lifecycle: single-use forever, 409 `IDEMPOTENCY_KEY_EXPIRED`                                       | ADR-017                                     |
| T25   | DB accounting backstops: V12 parent-side checks, scoped `uq_journal_entry_event`                                   | ADR-008 d5 note                             |
| T26   | Daily job robustness: `ABANDONED` + V13, keyset batching, retry backoff                                            | ADR-013 A-9 note T26                        |
| T29   | Fix contract statement query on PostgreSQL (X-13, `42P18`)                                                         | X-13 bug task                               |
| T11   | Phase-1 test hardening + exit verification                                                                         | commit `a8fa1a6`                            |
| T14   | E3 Excess → `contract_credit` + credit-apply/read; V14 cap/status                                                  | ADR-009, `t14-contract-credit`              |
| T15   | E5 Penalty waive/reduce + `EffectivePenaltyPort`; V15/V16                                                          | ADR-019, `t15-penalty-waive-effective-port` |
| T30   | Route schedule + aging through effective penalty (ADR-019 D4, PR #7/#8 findings)                                   | ADR-019 D4, `t30-effective-penalty-reads`   |
| T12   | E1 Settlement quote: new `settlement` module, `SettlementQuoteEngine`, `POST /settlements/quote`                   | ADR-018, `t12-settlement-quote`             |
| T13   | E2 Settlement execution: `POST /settlements`, SETTLEMENT journal, V17                                              | ADR-018, PR #10                             |
| T16   | E4 Payment void: `POST /payments/{id}/void`, reversal journal, recompute, V18                                      | ADR-020, PR #11                             |
| T17   | F1 Consistency-check job: fourth daily step, read-only reconciliation, V19                                         | Addendum §7.1, PR #12                       |
| T19   | F5 Write-off: `POST /contracts/{id}/write-off`, `WriteOffEngine`, V20                                              | DM §1.12, ADR-013 §1, PR #13                |
| —     | Pre-Sprint hygiene: PII at rest/resource server; V8 SYSTEM; input DoS bounds (ADR-016)                             | ADR-004/005/016, V2–V5, V8                  |

---

## Sprints 4–6 (completed work): see the archive

- **Sprint 4 — Phase-1 Close (4b/4c/4d):** COMPLETE. T1–T9, T11, T23–T26, T29 DONE; T10 DEFERRED. Detail in
  `tasks-archive.md`.
- **Sprint 5 — Settlement, Credit & Waiver (Epic E):** COMPLETE. T14, T15, T30, T12, T13 DONE (ADR-018,
  ADR-019). Detail in `tasks-archive.md`.
- **Sprint 6 — Void, Consistency & Write-off:** IN PROGRESS. T16, T17, T19 DONE and merged (ADR-020). T18,
  T27a, T31 remain TODO (below). T20 and the heavier T27 move to Sprint 6b alongside T21.

### T10 — Component-exact penalty base (conditional, DEFERRED)

DEFERRED — T1.a / ADR-013 kept ADR-012 decision 2 unchanged, so no code change is required unless a **new**
business decision explicitly demands component-exact penalty precision (base from unpaid principal +
recognized interest only, excluding penalty already paid; and/or a per-date historical base). If taken, this
reopens at ~3 pts: carry the component split (and business dates, if a historical base is chosen) across the
`contract` port, change `InstallmentBalance.penaltyBase`, and prove the next-day charge after a penalty-only
payment equals `rate × unpaid (principal + recognized interest)` (worked check: 1,573.33, not 1,570.19).
Depends on T1.a, T4. ADR-012 alternatives 5/6 list the rejected options.

---

## Open tasks

### T18 — F4 Idempotency key cleanup job (2 pts)

**Status: TODO.** **Dependencies:** T3/T26 daily-job infrastructure; ADR-017 (lifecycle, including the
2026-10-11 probe resolution); ADR-020 D3 (the all-status `uq_payment_idempotency` that keeps a voided key
spent after cleanup). First candidate to defer if Sprint 6 exceeds 13 pts.

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

### T27a — Minimal CI gate: `./gradlew check` (1 pt)

**Status: TODO.** **Dependencies:** none. Sprint 6 prerequisite, because `ci.yml` today runs only
`./gradlew test`.

**Scope.** Change the CI `test` job in `.github/workflows/ci.yml` to run `./gradlew check` instead of
`./gradlew test`, so the quality gate runs on every PR and push to `main`. **Not** in scope (→ T27): a
dependency-vulnerability scan, a secret scan, and the `docker compose up` → `/actuator/health` →
`/v3/api-docs` smoke → `down` runtime gate.

**Acceptance criteria.** CI runs `./gradlew check` and the build is green on the current `main` (verify
`check` does not pull in an unconfigured plugin that fails the build; if it does, configure it minimally or
scope it to T27). No change to application code.

**Tests.** CI config change; verified by a green CI run on the PR.

### T31 — Durable single-use-forever void idempotency key (2 pts)

**Status: TODO.** **Dependencies:** T16 (DONE, void path + V18), ADR-017, ADR-020 D3, T18 (the cleanup job
that makes the hole reachable). Raised by the PR #11 review (Qodo finding #5).

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

---

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

| ID  | Story                                                              | Sprint | Status | Carry-forward requirements                                                                                                                                                                                                                              |
| --- | ------------------------------------------------------------------ | ------ | ------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T20 | Observability baseline (Addendum §10)                              | 6b     | TODO   | `X-Request-Id` filter + MDC, JSON logs (`timestamp`, `level`, `request_id`, `contract_id`, `module`), write `idempotency_keys.request_id`. Not owned by any `05_SPRINT_PLAN.md` story (X-3).                                                            |
| T21 | F2 Auth: login/refresh/logout, Argon2id, lockout, refresh rotation | 6b     | TODO   | Issue `roles` as a string array per ADR-015. Validate `iss`/`aud` once a real issuer exists; consider RS256 (ADR-005, CR-10). Revisit T7 D2 (active `app_user` check). Add a production guard against the dev-key compose fallbacks (CR-16).            |
| T27 | CI & runtime gates — security scans + Compose smoke (CR-15)        | 6b     | TODO   | The minimal `./gradlew check` gate is split to **T27a** (Sprint 6). T27 keeps a dependency-vulnerability and a secret scan (justify each tool per `35-coding-standards`), and `docker compose up` → `/actuator/health` → `/v3/api-docs` smoke → `down`. |
| T22 | G1–G6 Reporting, frontend, demo, outbox                            | 7–8    | TODO   | Unchanged from `05_SPRINT_PLAN.md`. NPL/AR depend on the DPD definition from T1.b.                                                                                                                                                                      |
| T28 | PII / HMAC key rotation (CR-11)                                    | 7      | TODO   | Key id in the PII envelope (`v2:<kid>:…`), a key ring for decrypt and HMAC lookups, and a re-keying procedure for `nik_hash`/`phone_lookup` and `idempotency_keys.request_hash` (ADR-004, ADR-007 consequences). New ADR.                               |

### Deferred

| Item                                                                                        | Reason                                                                           |
| ------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------- |
| PRD C-5 update DRAFT contract                                                               | Already deferred in `05_SPRINT_PLAN.md`.                                         |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring                     | P2 / Fase 4 per PRD.                                                             |
| Require auth for OpenAPI/Swagger outside dev                                                | ADR-005 made it public by design. Revisit with T21.                              |
| Rate limiting, security headers, CORS policy                                                | No browser client or public deployment yet. Revisit with G2.                     |
| Remove the unused Lombok dependency. Move the Sonar host out of `gradle-wrapper.properties` | Hygiene only. The Sonar change is uncommitted local work owned by the developer. |
| Envelope-rendering `ErrorController` for container-level errors                             | Boot's default JSON leaks no internals. Low value until a browser client exists. |
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
tasks: CR-10/CR-16 → T21, CR-11 → T28, CR-15 → T27. CR-14 was closed by T15. All others are DONE.

### Ambiguities

Resolved items (A-11, A-12, A-13) and the fully-resolved Phase-1 decisions (A-1…A-10) are recorded in
ADR-012, ADR-013, ADR-015 and ADR-017; detail is in `tasks-archive.md` (T1). No ambiguity currently blocks
an open task — T18, T27a and T31 have no open `A-n` dependency.

### Dependency order (open tail)

```text
T26 job robustness ─► T18 (idempotency cleanup)
ADR-020 D3 (all-status uq_payment_idempotency) ─► T18 (voided key stays spent after cleanup)
T16 (void) + T18 ─► T31 (durable void key)
T27a (CI check gate) — independent, Sprint 6 prerequisite
Sprint 6b: T20 (observability), T27 (scans + smoke), T21 (auth) ─► T22, T28
```
