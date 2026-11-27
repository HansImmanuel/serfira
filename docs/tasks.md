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
Completed Work index, the proposed next stories, deferred work, and open ambiguities.

---

## Current Project State

**Snapshot:** `main` @ `70da91b` (PR #33, T39, merged 2026-11-27). **Every planned sprint is COMPLETE**
(Sprint 0 through Sprint 8b; T10 stays DEFERRED) and no sprint is in progress. Backend Phases 1–3 are
implemented. The browser dashboard covers every operator flow except payment void and penalty waive/reduce,
which exist only as API endpoints. The proposed next stories (T40–T42) are in Future / Deferred Work →
Later sprints.

**Verified test counts** (2026-11-27, the T39 branch exactly as merged into `main` @ `70da91b`): backend
`./gradlew check --rerun-tasks` = **150 suites / 1177 tests / 0 failed**; frontend `npm test` = **302 tests /
31 files / 0 failed**, plus green `npm run lint`, `npm run typecheck` and `npm run build` (the production
build refuses to fall back to localhost without `NEXT_PUBLIC_API_BASE_URL`). Demo helpers
`node --test "scripts/demo/*.test.mjs"` = **30 tests / 0 failed**; `node scripts/demo.mjs --reset` exits 0
on a fresh stack, and plain re-runs against the same database exit 0. Per-task counts are in each task's
implementation note in `tasks-archive.md`. CI on `main` is red for a GitHub billing reason, not a test
failure; the local runs are the verification of record.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `auth`, `shared`.
- Migrations **V1–V25** plus the `demo`-profile repeatable seed `R__demo_seed.sql`; schema is owned by
  Flyway (`ddl-auto=validate`). V25 is the latest: the nullable `idempotency_keys.request_hash_kid` (T28b).
- Daily servicing (billing → penalty → aging → consistency check) runs as the ShedLock job with `job_run`
  rows; a second job cleans expired idempotency keys (T18).
- Money paths: schedule engine, payment allocation + idempotency, billing/recognition, penalty accrual
  and waive/reduce, settlement quote/execution, credit application, payment void, write-off, double-entry
  ledger with DB backstops (ADR-008/009/011/014/017/018/019/020/021).
- **20 endpoints** under `/api/v1` (README → API): auth login/refresh/logout, contracts create/activate/
  list/detail/installments/statement, payment history, payments create/void, credit apply/read, penalty
  adjustment, settlement quote/execute, write-off, aging and outstanding reports. Every one has a
  default-deny role-matrix row (ADR-015); only the three auth endpoints are `permitAll` (ADR-022).
- Auth: Argon2id, 15-minute HS256 access token, hashed opaque refresh token with single-use rotation,
  idempotent logout, lockout (5 × `PT15M` → 423), dev-key production guard (ADR-022).
- Browser boundary: exact-origin CORS in the security chain with startup validation, an empty default, no
  credentials, and `403` before authorization for a disallowed origin (ADR-023). The frontend calls the API
  directly — no BFF or proxy.
- Frontend (`frontend/`, Next.js 16.4.6 App Router): in-memory tokens, a single-flight refresh-and-replay
  client, and a session generation that discards stale refreshes and responses. Screens: `/login`, the
  contract book (filter/search/pagination), create, activation, and contract detail with Jadwal/Pembayaran/
  Kredit/Ledger tabs (`?tab=` / `?payment=` deep links). Writes: payment (`/contracts/[id]/payments/new`),
  credit apply, early settlement (quote, countdown, execute) and write-off, each retry-safe under one
  `Idempotency-Key` per operation (FE §1.4). Reads: the statement page with filters (PAYMENT rows link to
  the payment) and `/reports/aging` and `/reports/outstanding`. Money and rates are exact decimal strings
  (ADR-024), never a float. Authorization stays server-side; the JWT `roles` only drive which buttons and
  tabs render.
- PII: AES-256-GCM envelope for `nik`/`phone`, HMAC `nik_hash`/`phone_lookup` for uniqueness and search
  (ADR-004). The key ring (`v2:<kid>:…`, legacy `v1:` still read) and the opt-in, verified, resumable
  re-keying runner with a per-`kid` retirement verdict are ADR-025 (T28a/T28b).
- Observability and CI: `X-Request-Id` + ECS JSON logs (T20); `check`, `frontend`, gitleaks,
  Dependency-Check and Compose smoke as separate CI jobs (T27, T32). No scanner is wired into `check`.
  Compose smoke also runs the demo helpers' tests and `node scripts/demo.mjs` (T34).
- Demo (T34): `scripts/demo.mjs` is a dependency-free Node script that logs in, creates and activates the
  Addendum §18 contracts through the API with dates relative to today, checks the golden schedules, pays
  (PRD scenarios 1, 2, 3 and 6), reads the statement (Σ debit = Σ credit) and the aging report, and checks
  the role split. Only the demo users are seeded.
- Outbox (T38, ADR-027): six PII-free domain events are written to `outbox_events` in their business
  transaction, in write order; a ShedLock job (`outbox-publisher`, every 5 s) logs each as one ECS line and
  marks it `SENT`; failures retry and end `FAILED`.

**Blockers and critical gaps:** none. Operational notes: the `dependencies` CI job requires the one-time
`NVD_API_KEY` secret; the keyed CI acceptance run is deferred while the GitHub account billing lock is
resolved.

**Specification/implementation discrepancies:** X-1 … X-14 are resolved except **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). The resolved table is in `tasks-archive.md`.

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
| T21b  | Auth stateful half: refresh rotation, idempotent logout, brute-force lockout, dev-key prod guard, X-14/A-14 closed  | ADR-022 D10–D13, `t21b-refresh-lockout`                 |
| T27   | CI & runtime gates: OWASP Dependency-Check (CVSS ≥ 7.0), gitleaks (history incl. merge diffs + tree), Compose smoke | CR-15, `t27-ci-runtime-gates`                           |
| T35   | Browser-client CORS boundary: exact-origin allowlist + startup validation, `CorsFilter` before the matrix, ADR-023 | ADR-023, `t35-cors-adr-023`                             |
| T32   | Frontend G2: Next.js 16.4.6 shell, in-memory session + single-flight refresh, contract list/create/detail/activate  | 06_FRONTEND_SPEC §1–§3, ADR-023, `t32-nextjs-shell`     |
| T33   | Frontend G3: payment form + allocation result, statement filters, credit apply; no `paid_at`/note input     | 06_FRONTEND_SPEC §2.6–§2.7, `t33-payment-statement-credit` |
| T34   | G5 demo: `scripts/demo.mjs` end-to-end API demo (relative dates), CI compose-smoke run, README close-out    | Addendum §18 (amended), `t34-demo-script`               |
| T36   | G1 reporting metrics: `GET /reports/outstanding` (outstanding, AR, NPL, collection rate, settlement cash), V24 | ADR-026, `t36-reporting-outstanding`, PR #28            |
| T37   | G4 report pages `/reports/aging` and `/reports/outstanding`; `customer_name` on aging rows                     | ADR-026, FE §2.9/§2.10, `t37-report-pages`, PR #29      |
| T38   | G6 outbox: six PII-free domain events written in their transaction; ShedLock log publisher, retries, `FAILED` | ADR-027, `t38-outbox-events`, PR #30                    |
| T28a  | CR-11 PII key ring: `v2:<kid>` envelope, candidate lookups, ring validation + guard on every member         | ADR-025, `t28a-pii-key-ring`, PR #31                    |
| T28b  | CR-11 re-keying pass + `request_hash_kid` (V25): verified resumable re-key, kid-aware replay, retirement verdict | ADR-025 D8–D10, `t28b-pii-rekey`, PR #32                |
| T39   | FE payments tab, settlement modal and write-off dialog; `GET /contracts/{id}/payments`                       | plan T39, FE §2.4/§2.7/§2.8, `t39-payments-settlement-writeoff`, PR #33 |
| —     | Pre-Sprint hygiene: PII at rest/resource server; V8 SYSTEM; input DoS bounds (ADR-016)                             | ADR-004/005/016, V2–V5, V8                              |

### Sprint summaries (detail in the archive)

- **Sprint 4 — Phase-1 Close (4b/4c/4d):** COMPLETE. T1–T9, T11, T23–T26, T29 DONE; T10 DEFERRED.
- **Sprint 5 — Settlement, Credit & Waiver (Epic E):** COMPLETE. T14, T15, T30, T12, T13 DONE (ADR-018,
  ADR-019).
- **Sprint 6 — Void, Consistency & Write-off:** COMPLETE. T16, T17, T19, T18 (PR #14), T27a (PR #15) and T31
  (PR #16 + review fixes PR #17) DONE.
- **Sprint 6b — Phase-2 security boundary + CI hardening:** COMPLETE. T20 (PR #18), T21a (PR #19), T21b
  (PR #20) and T27 DONE. T27's keyed CI acceptance run is deferred while the GitHub account billing lock is
  resolved; the workflow, scanners and smoke test are implemented and locally verified.
- **Sprint 7 — Frontend Core & Demo:** COMPLETE. T35 (PR #22), T32 (PR #23), T33 (PR #24) and T34 (PR #25)
  DONE. The operator flow runs in the browser and `node scripts/demo.mjs` replays it against the API.
- **Sprint 8 — Reporting, Outbox & PII Key Rotation:** COMPLETE. T36 (PR #28), T37 (PR #29), T38 (PR #30),
  T28a (PR #31) and T28b (PR #32) DONE: Phase 3's reporting and event foundation, and CR-11.
- **Sprint 8b — Payments, Settlement & Write-off UI:** COMPLETE. T39 (PR #33) DONE. Plan:
  `docs/superpowers/plans/2026-11-27-t39-payments-settlement-writeoff.md`.

---

## Future / Deferred Work

### Later sprints (proposed, detailed at their sprint planning)

No sprint is planned. These are the proposed next stories, one row each; the one picked up is turned into a
full ticket (and a plan) at its sprint planning. Suggested order: T42 before T40, which reuses its shared
alert; T41 is independent.

| Story | Title (estimate)                                     | Carry-forward requirements |
| ----- | ---------------------------------------------------- | -------------------------- |
| T40   | FE payment void and penalty waive/reduce (≈5 pts)    | The last two write endpoints without a screen: `POST /payments/{id}/void` (ADR-020) and `POST /penalty-adjustments` (ADR-019), both ADMIN_OPERASIONAL only (Addendum §3.4). Neither screen is in the FE spec (T39 Decision 12 left void out), so an FE spec section comes first. Likely homes: a void action on POSTED rows of the Pembayaran tab, and a waive/reduce action on an installment in Jadwal (the request names `contract_id` + `installment_id`). Void takes an `Idempotency-Key` and follows the T39 dialog rules (one key per operation, `isOutcomeUnknown`, spent-key restart, mounted whatever the contract status). Penalty adjustment takes **no** key, so how its dialog treats a lost response must be decided at planning against ADR-019. |
| T41   | `refresh_token` retention / cleanup job (≈2 pts)     | Moved from Deferred: its trigger ("schedule it with the reporting work") passed with Sprint 8. Rotation inserts a row per refresh and revoked rows are never deleted (~96 rows per user per day at a 15-minute access token). Same shape as T18: keyset delete on `expires_at`, its own ShedLock lock, one `job_run` row (ADR-022). |
| T42   | T39 follow-ups (≈2 pts)                              | Polish parked at the T39 reviews: one shared spent-key alert (copied in the credit, settlement and write-off dialogs); the write-off counter shows the raw length while validation counts the trimmed one; the settlement countdown interval keeps ticking after expiry and while closed; `instantMs` throws during render on a malformed instant; payment history `aria-controls` points at a collapsed row, the allocation sort interleaves while the schedule is unloaded, and the VOIDED/POSTED badges are English; after a `CREDIT_EXCEEDS_SETTLEMENT` block no re-quote is offered once the quote expires. Also decide whether `apiFetch`'s client-side `SESSION_CHANGED` (401), which discards a response the server may have committed, should count as an unknown outcome on a first attempt in every write form. |

### Deferred

| Item                                                                                        | Reason                                                                                                                                                    |
| ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PRD C-5 update DRAFT contract                                                               | Already deferred in `05_SPRINT_PLAN.md`.                                                                                                                  |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring                     | P2 / Fase 4 per PRD.                                                                                                                                      |
| `iss`/`aud` validation + RS256 (CR-10)                                                      | Needs a real external token issuer (ADR-005); login mints HS256 tokens in-process. Revisit when an issuer is introduced.                                   |
| Require auth for OpenAPI/Swagger outside dev                                                | ADR-005 made it public by design. T21b kept it public; revisit after T32 (the first real browser client).                                                  |
| Rate limiting; browser security headers beyond the Next.js / Spring Security defaults       | No public deployment yet. CORS itself is T35; the rest is revisited after the browser client exists.                                                       |
| Frontend container / Compose service                                                        | The demo path is `npm run dev` plus the backend Compose stack (T34); revisit when deployment is real.                                                      |
| SonarQube/SonarCloud in CI                                                                  | Decided out of scope for T27 (2026-11-07): needs a running server, an external dependency not worth it for this portfolio repo. The `org.sonarqube` plugin stays local/manual, never wired into `check` or CI. |
| Envelope-rendering `ErrorController` for container-level errors                             | Boot's default JSON leaks no internals. Low value until a browser client exists.                                                                           |
| Reconcile the V12 `assert_payment_has_allocations` backstop vs consistency check B's end-to-end IT | V12 (DEFERRABLE, rejects Σallocations≠amount at COMMIT, "zero included") makes check B's drift unstageable in the DB, so `ConsistencyCheckIT` only exercises check A; check B is covered at the engine level (`ConsistencyCheckEngineTest`). Decide whether check B needs an end-to-end guard given V12 already forecloses its drift, or document it as engine-only by design. Test-only follow-up; no production change implied. |
| Fix TS §7 repo structure (X-9)                                                              | Documentation only.                                                                                                                                        |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role)             | Deployment concern. No deployment exists yet.                                                                                                              |
| `outbox_events` retention for `SENT` rows                                                   | T38 drains events but never deletes them. Same shape as T18's keyset delete; revisit once a real broker or a volume concern exists. |
| Outbox retry backoff for a broker sink                                                      | T38 retries a refused event every tick (10 × 5 s ≈ 50 s, then `FAILED`; manual re-drive in ADR-027 D5). Fine for the log sink, which practically cannot fail; a broker outage longer than a minute would fail every event written in it. Add backoff (e.g. a `next_attempt_at`) with the broker. |
| Outbox claim guard for rows an instance cannot read                                         | A row with an `event_type`/`aggregate_type` unknown to an older instance (rolling deploy) fails the entity-mapped claim on every tick and never reaches `FAILED` (ADR-027 D5). Single instance today; needs a per-row guard before a multi-version deployment. |
| Historical `as_of` / metric trend snapshots                                                 | ADR-013 A-6 and ADR-026 keep reports at today only; trends need a snapshot store.                                                                         |

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
mapped to tasks; the full CR verdict table is in `tasks-archive.md`. The only one still open is CR-10
(RS256/`iss`/`aud`), deferred until a real issuer exists.

### Ambiguities

No open `A-n`. The resolved Phase-1 decisions (A-1…A-14) and the X-table are in `tasks-archive.md`; later
choices are recorded in ADR-023 (browser client), ADR-025 (PII key ring), ADR-026 (report metrics) and
ADR-027 (outbox).

### Dependency order (open tail)

```text
All planned sprints are complete. Proposed, not yet planned:
  T39 ─► T42 (T39 follow-ups; one shared spent-key alert) ─► T40 (void + penalty-adjustment dialogs)
  T41 (refresh_token cleanup job) — independent
```
