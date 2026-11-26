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

**Snapshot:** `main` @ `974ce81` (PR #29, T37 report pages, merged); T38 in progress on `t38-outbox-events`. **Sprint 7 is COMPLETE**: T35, T32,
T33 and T34 are merged (PRs #22–#25). Backend Phases 1–2 are implemented and merged. The `frontend/` app
shell, login, contract lifecycle and the servicing screens (payment, statement filters, credit apply) are
implemented, as are the aging and outstanding report pages (T37), and `node scripts/demo.mjs` proves the
operator flow end to end against the Compose stack.
**Current sprint: Sprint 8 (IN PROGRESS, 14 pts)**: T36 → T37 → T38 → T28a → T28b. The metric
definitions are decided in ADR-026. T36 is DONE (PR #28, merged); T37 is DONE (PR #29, merged); T38 is IN PROGRESS.

**Verified test counts:** backend full local `./gradlew check --rerun-tasks` (2026-11-26, branch
`t37-report-pages`) = **126 suites / 969 tests / 0 failed** (T34 close 121/939; T36 added the
outstanding report's tests; T37 the aging IT's customer-name case). Frontend `npm test` (2026-11-26, branch
`t37-report-pages`) = **234 tests / 27 files / 0 failed** (T34 close 203; T37 added the formatter, sidebar
and both report pages), plus green `npm run lint`, `npm run typecheck` and `npm run build` (the production
build refuses to fall back to localhost without `NEXT_PUBLIC_API_BASE_URL`). Demo helpers
`node --test "scripts/demo/*.test.mjs"` = **30 tests / 0 failed**; `node scripts/demo.mjs --reset`
exits 0 on a fresh stack, and plain `node scripts/demo.mjs` re-runs against the same database exit 0. CI on `main` is red
for a GitHub billing reason, not a test failure.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `auth`, `shared`.
- Migrations **V1–V24** plus the `demo`-profile repeatable seed `R__demo_seed.sql`; schema is owned by
  Flyway (`ddl-auto=validate`). V24 is the latest: the reporting range indexes on `installment.due_date`
  and `settlement.executed_at` (T36).
- Daily servicing (billing → penalty → aging → consistency check) runs as the ShedLock job with `job_run`
  rows; a second job cleans expired idempotency keys (T18).
- Money paths: schedule engine, payment allocation + idempotency, billing/recognition, penalty accrual
  and waive/reduce, settlement quote/execution, credit application, payment void, write-off, double-entry
  ledger with DB backstops (ADR-008/009/011/014/017/018/019/020/021).
- **19 endpoints** under `/api/v1`: auth login/refresh/logout, contracts create/activate/list/detail/
  installments/statement, payments create/void, aging and outstanding reports (ADR-026), credit
  apply/read, write-off, penalty
  adjustment, settlement quote/execute. Every one has a default-deny role-matrix row (ADR-015); only the
  three auth endpoints are `permitAll` (ADR-022).
- Auth: Argon2id, 15-minute HS256 access token, hashed opaque refresh token with single-use rotation,
  idempotent logout, lockout (5 × `PT15M` → 423), dev-key production guard (ADR-022).
- Browser boundary: exact-origin CORS in the security chain with startup validation, an empty default, no
  credentials, and `403` before authorization for a disallowed origin (T35, ADR-023). The frontend calls
  the API directly — no BFF or proxy.
- Frontend (`frontend/`, T32): Next.js 16.4.6 App Router shell with in-memory tokens, a single-flight
  refresh-and-replay client, a session generation that discards stale refreshes/responses, and cache
  clearing on login/logout; `/login`, a guarded dashboard, the contract book (filter/search/pagination),
  create, detail with Jadwal/Kredit/Ledger tabs, activation, and a minimal statement page. Money and rates
  are exact decimal strings (ADR-024) formatted with BigInt, never a float. Authorization stays server-side;
  the JWT `roles` only drives which buttons/tabs render, and credit/statement are hidden from `MANAJEMEN`
  (the API still answers 403). T33 adds `/contracts/[id]/payments/new` (amount + channel; the server's
  `paid_at`; allocation and excess-credit result), statement `ref_type`/`from`/`to` filters sent to the
  API, and credit apply from the Kredit tab; every write invalidates the `["contract", id]` query prefix.
  T37 adds `/reports/aging` and `/reports/outstanding` (a "Laporan" sidebar group for all three roles):
  the summary row and cards are the API's values, formatted only, and the aging rows carry `customer_name`.
- PII: AES-256-GCM `v1:` envelope for `nik`/`phone`, HMAC `nik_hash`/`phone_lookup` for uniqueness and
  search (ADR-004); rotation is **T28a/T28b** (Sprint 8).
- Observability and CI: `X-Request-Id` + ECS JSON logs (T20); `check`, `frontend`, gitleaks,
  Dependency-Check and Compose smoke as separate CI jobs (T27, T32). No scanner is wired into `check`.
  Compose smoke also runs the demo helpers' tests and `node scripts/demo.mjs` (T34).
- Demo (T34): `scripts/demo.mjs` is a dependency-free Node script that logs in, creates and activates the
  Addendum §18 contracts through the API with dates relative to today, checks the golden schedules, pays
  (PRD scenarios 1, 2, 3 and 6), reads the statement (Σ debit = Σ credit) and the aging report, and checks
  the role split. Only the demo users are seeded.

Full per-task implementation notes for DONE work are in `tasks-archive.md`; the sprint narrative is in
`PROGRESS.md`.

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
| T33   | Frontend G3: payment form + allocation result, statement filters, credit apply; no `paid_at`/note input     | 06_FRONTEND_SPEC §2.6–§2.7, `t33-payment-statement-credit` |
| T34   | G5 demo: `scripts/demo.mjs` end-to-end API demo (relative dates), CI compose-smoke run, README close-out    | Addendum §18 (amended), `t34-demo-script`               |
| T36   | G1 reporting metrics: `GET /reports/outstanding` (outstanding, AR, NPL, collection rate, settlement cash), V24 | ADR-026, `t36-reporting-outstanding`                    |
| T37   | G4 report pages `/reports/aging` and `/reports/outstanding`; `customer_name` on aging rows                     | ADR-026, FE §2.9/§2.10, `t37-report-pages`              |
| T32   | Frontend G2: Next.js 16.4.6 shell, in-memory session + single-flight refresh, contract list/create/detail/activate  | 06_FRONTEND_SPEC §1–§3, ADR-023, `t32-nextjs-shell`     |
| —     | Pre-Sprint hygiene: PII at rest/resource server; V8 SYSTEM; input DoS bounds (ADR-016)                             | ADR-004/005/016, V2–V5, V8                              |

### Sprint summaries (detail in the archive)

- **Sprint 4 — Phase-1 Close (4b/4c/4d):** COMPLETE. T1–T9, T11, T23–T26, T29 DONE; T10 DEFERRED. Detail in
  `tasks-archive.md`.
- **Sprint 5 — Settlement, Credit & Waiver (Epic E):** COMPLETE. T14, T15, T30, T12, T13 DONE (ADR-018,
  ADR-019). Detail in `tasks-archive.md`.
- **Sprint 6 — Void, Consistency & Write-off:** COMPLETE. T16, T17, T19, T18 DONE and merged (ADR-020,
  PR #14); T27a DONE and merged (PR #15); T31 DONE and merged (PR #16 + review fixes PR #17). No
  implementation work remains.
- **Sprint 6b — Phase-2 security boundary + CI hardening:** COMPLETE. T20 (observability baseline, PR #18),
  T21a (auth foundation, PR #19), T21b (rotation/logout/lockout/dev-key guard, PR #20) and T27 (CI & runtime
  gates) DONE. T27's keyed CI acceptance run is deferred while the GitHub account billing lock is resolved;
  the workflow, scanners and smoke test are implemented and locally verified.
- **Sprint 7 — Frontend Core & Demo:** COMPLETE. T35 (PR #22), T32 (PR #23), T33 (PR #24) and T34
  (PR #25) DONE. The operator flow runs in the browser and `node scripts/demo.mjs` replays it against the
  API. Detail in `tasks-archive.md`.

---

## Sprint 8 — Reporting, Outbox & PII Key Rotation (PLANNED)

**Goal (05_SPRINT_PLAN.md Sprint 8):** management reporting and the event foundation are done, which closes
**Phase 3**. The dashboard metrics have written definitions (ADR-026), the aging and outstanding reports run
in the browser, and domain events are written transactionally and drained by a log-based publisher. The
sprint also carries CR-11: the PII key ring and a re-keying pass, so the AES and HMAC keys can rotate
without losing data.

**Scope (14 pts — a deliberate one-point stretch over the 13-point ceiling):**

- T36 — G1: reporting metrics + `GET /reports/outstanding` (3 pts) — DONE
- T37 — G4: frontend aging and outstanding pages + customer name in the aging API (3 pts) — DONE
- T38 — G6: transactional outbox writer, six domain events, log-based publisher (3 pts) — IN PROGRESS
- T28a — PII key ring + `v2` envelope, ADR-025 (2 pts) — TODO
- T28b — PII/HMAC re-keying pass + `RequestFingerprint` key compatibility (3 pts) — TODO

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
  key in the ring behaves exactly as today. Until T28b lands, do not rotate the HMAC key in any environment.

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

Status: IN PROGRESS
Estimate: 3 pts

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

Status: TODO
Estimate: 2 pts

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

Status: TODO
Estimate: 3 pts

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
  - New migration V24 adds a nullable `idempotency_keys.request_hash_kid`. `NULL` means legacy: signed
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
- **Docs:** ADR-025 addendum (retention rule, runner, V24), README operations runbook, Addendum §9.

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

---

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

Sprint 8 is detailed above. Sprint 8b stays a one-line row until its planning; the carry-forward
requirements are scope notes, not estimates to implement from.

| ID   | Story                                              | Sprint | Pts | Depends on                                | Carry-forward requirements                                                                                                                                                                                                 |
| ---- | -------------------------------------------------- | ------ | --- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T39  | FE payments tab, settlement and write-off flows    | 8b     | 2   | T33; new `GET /contracts/{id}/payments`   | Payment-history read model + role-matrix row + OpenAPI; quote/execute modal with countdown and `STALE_SETTLEMENT_QUOTE`; write-off dialog with reason; link statement `ref_id` to the payment/settlement (T33 left it as a short id) |

Sprint 8 = 14 pts (T36, T37, T38, T28a, T28b; a one-point stretch, with T28b the first to slip into 8b).
Sprint 8b = 2 pts (T39), or 5 pts if T28b slips.

> **`iss`/`aud` + RS256 (CR-10) and the T7-D2 active-user check** were part of the old T21 carry-forward.
> The active-user check is now folded into **T21a**. `iss`/`aud` validation and an RS256 migration stay
> deferred until a real external token issuer exists (ADR-005) — not needed while login mints HS256
> tokens in-process; revisit when an issuer is introduced.

### Deferred

| Item                                                                                        | Reason                                                                                                                                                    |
| ------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PRD C-5 update DRAFT contract                                                               | Already deferred in `05_SPRINT_PLAN.md`.                                                                                                                  |
| R-3 cash-in report, R-4 CSV export, P-6 gateway stub, S-5 restructuring                     | P2 / Fase 4 per PRD.                                                                                                                                      |
| Require auth for OpenAPI/Swagger outside dev                                                | ADR-005 made it public by design. T21b kept it public; revisit after T32 (the first real browser client).                                                  |
| Rate limiting; browser security headers beyond the Next.js / Spring Security defaults       | No public deployment yet. CORS itself is T35; the rest is revisited after the browser client exists.                                                       |
| Frontend container / Compose service                                                        | The demo path is `npm run dev` plus the backend Compose stack (T34); revisit when deployment is real.                                                      |
| SonarQube/SonarCloud in CI                                                                  | Decided out of scope for T27 (2026-11-07): needs a running server, an external dependency not worth it for this portfolio repo. The `org.sonarqube` plugin stays local/manual, never wired into `check` or CI. |
| Envelope-rendering `ErrorController` for container-level errors                             | Boot's default JSON leaks no internals. Low value until a browser client exists.                                                                           |
| Reconcile the V12 `assert_payment_has_allocations` backstop vs consistency check B's end-to-end IT | V12 (DEFERRABLE, rejects Σallocations≠amount at COMMIT, "zero included") makes check B's drift unstageable in the DB, so `ConsistencyCheckIT` only exercises check A; check B is covered at the engine level (`ConsistencyCheckEngineTest`). Decide whether check B needs an end-to-end guard given V12 already forecloses its drift, or document it as engine-only by design. Test-only follow-up; no production change implied. |
| Fix TS §7 repo structure (X-9)                                                              | Documentation only.                                                                                                                                        |
| Declare DB role privileges (no `TRUNCATE` / `DISABLE TRIGGER` for the app role)             | Deployment concern. No deployment exists yet.                                                                                                              |
| `outbox_events` retention for `SENT` rows                                                   | T38 drains events but never deletes them. Same shape as T18's keyset delete; revisit once a real broker or a volume concern exists. |
| Historical `as_of` / metric trend snapshots                                                 | ADR-013 A-6 and ADR-026 keep reports at today only; trends need a snapshot store.                                                                         |
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
mapped to tasks. The full CR verdict table is in `tasks-archive.md`. Still-open mapping: CR-10 → deferred
(RS256/`iss`/`aud`, needs a real issuer), **CR-11 → T28a/T28b** (Sprint 8), CR-15 → T27 (DONE). All others
are DONE.

### Ambiguities

No open `A-n`. Sprint 7's browser-client choices are recorded in `06_FRONTEND_SPEC.md` §1.1/§1.4/§4 and
formalized in **ADR-023** (T35, DONE). Sprint 8's open definitions were decided at planning (2026-11-26):

- The NPL numerator, collection-rate period and cohort, and AR versus overdue are in **ADR-026**.
- The T38 event catalogue and the T28b key-retention rule are in their task sections, and are recorded in
  ADR-027 and ADR-025 when those tasks land. The resolved Phase-1 decisions (A-1…A-14) and the X-table are in
`tasks-archive.md` and the cited ADRs.

### Dependency order (open tail)

```text
Sprint 7 (COMPLETE):
  T35 (CORS + ADR-023) ─► T32 (G2 shell, login, contract lifecycle)
      ─► T33 (G3 payment, schedule, statement, credit) ─► T34 (G5 demo)

Sprint 8 (PLANNED, 14 pts; work order T36 → T37 → T38 → T28a → T28b):
  ADR-026 (metrics, Accepted) ─► T36 (G1 metrics + /reports/outstanding) ─► T37 (G4 FE reports)
  T38 (G6 outbox writer + 6 events + publisher, ADR-027)
  T28a (PII key ring + v2 envelope, ADR-025) ─► T28b (re-keying + RequestFingerprint kid)   [CR-11]

Sprint 8b:
  T33 ─► T39 (FE payments tab, settlement, write-off)
```
