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

**Snapshot:** `main` @ `3a66c98` (PR #23, T32 Next.js shell, merged). **Sprint 6b is COMPLETE**; **Sprint 7
is in progress** — T35 and T32 DONE and merged, T33 DONE on branch `t33-payment-statement-credit` (PR
pending); T34 still TODO. Backend Phases 1–2 are implemented and merged; the `frontend/` app shell, login,
contract lifecycle and the servicing screens (payment, statement filters, credit apply) are implemented.

**Verified test counts:** backend full local `./gradlew check` (2026-11-25, branch `t32-nextjs-shell`, after
`main` was merged in) = **121 suites / 932 tests / 0 failed** (T35 baseline 120/926, plus
`BigDecimalStringModuleTest`, plus the PR #22 CORS review tests); unchanged by T33 (no backend file
touched). Frontend `npm test` (2026-11-25, branch `t33-payment-statement-credit`) =
**178 tests / 23 files / 0 failed** (T32 baseline 108 / 17), plus green `npm run lint`, `npm run typecheck` and `npm run build` (the
production build refuses to fall back to localhost without `NEXT_PUBLIC_API_BASE_URL`). CI on `main` is red
for a GitHub billing reason, not a test failure.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `settlement`, `auth`, `shared`.
- Migrations **V1–V23** plus the `demo`-profile repeatable seed `R__demo_seed.sql`; schema is owned by
  Flyway (`ddl-auto=validate`). V22/V23 are the latest: durable void idempotency and void-excluded caps.
- Daily servicing (billing → penalty → aging → consistency check) runs as the ShedLock job with `job_run`
  rows; a second job cleans expired idempotency keys (T18).
- Money paths: schedule engine, payment allocation + idempotency, billing/recognition, penalty accrual
  and waive/reduce, settlement quote/execution, credit application, payment void, write-off, double-entry
  ledger with DB backstops (ADR-008/009/011/014/017/018/019/020/021).
- **18 endpoints** under `/api/v1`: auth login/refresh/logout, contracts create/activate/list/detail/
  installments/statement, payments create/void, aging report, credit apply/read, write-off, penalty
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
- PII: AES-256-GCM `v1:` envelope for `nik`/`phone`, HMAC `nik_hash`/`phone_lookup` for uniqueness and
  search (ADR-004); rotation is **T28a/T28b** (Sprint 8).
- Observability and CI: `X-Request-Id` + ECS JSON logs (T20); `check`, `frontend`, gitleaks,
  Dependency-Check and Compose smoke as separate CI jobs (T27, T32). No scanner is wired into `check`.

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

---

## Sprint 7 — Frontend Core & Demo (active)

**Goal (05_SPRINT_PLAN.md Sprint 7):** the main operator flow runs in a browser — login → create contract
→ activate → record payment → read statement — and the demo is repeatable.

**Scope (13 pts):**

- T35 — Browser-client CORS boundary + ADR-023 (1 pt) — **DONE**
- T32 — G2: app shell, login, contract list/detail/create/activate (5 pts) — **DONE**
- T33 — G3: payment form, installment table, statement, credit tab (5 pts) — **DONE**
- T34 — G5: demo script, seed data, README close-out (2 pts)

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
gated by role only and `canStartNew` gates just a new application. Verified: lint, typecheck, `npm test` = **200 tests / 23
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

Status: TODO
Estimate: 2 pts

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

---

## Future / Deferred Work

### Later sprints (planned, detailed at their sprint planning)

Sprint 7 is detailed above. Sprint 8 and 8b stay one-line rows until their planning; the carry-forward
requirements are scope notes, not estimates to implement from.

| ID   | Story                                              | Sprint | Pts | Depends on                                | Carry-forward requirements                                                                                                                                                                                                 |
| ---- | -------------------------------------------------- | ------ | --- | ----------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T28a | PII key ring + versioned envelope (CR-11)          | 8      | 2   | T21b (DONE)                               | `v2:<kid>:<b64>` envelope, ring for decrypt, active key id, HMAC candidate matching, unknown-kid failure, DevKey guard across the ring, ADR-024                                                                              |
| T28b | PII/HMAC re-keying pass (CR-11)                    | 8      | 3   | T28a                                      | Keyset-paged re-encrypt + rehash of `customer` PII, pre-flight duplicate abort, per-row verification, property-gated maintenance runner; `idempotency_keys.request_hash` handled by ring retention (rows expire ≤ 7 days)    |
| T36  | G1 dashboard metrics                               | 8      | 3   | ADR-013 A-4/A-5 (DPD, DONE)               | Write the AR / NPL / collection-rate definitions before code; read-only `reporting` port; summary endpoint                                                                                                                  |
| T37  | G4 FE reports (aging, outstanding)                 | 8      | 3   | T36                                       | Aging page with `as_of` locked to today; outstanding cards/table fed by T36                                                                                                                                                 |
| T38  | G6 outbox publisher                                | 8      | 2   | none                                      | `outbox_events` already exists in V1; log-based publisher job, no Kafka                                                                                                                                                     |
| T39  | FE payments tab, settlement and write-off flows    | 8b     | 2   | T33; new `GET /contracts/{id}/payments`   | Payment-history read model + role-matrix row + OpenAPI; quote/execute modal with countdown and `STALE_SETTLEMENT_QUOTE`; write-off dialog with reason; link statement `ref_id` to the payment/settlement (T33 left it as a short id) |

Sprint 8 load = 13 pts (T28a, T28b, T36, T37, T38); Sprint 8b = 2 pts (T39). T37 needs T36 first.

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
formalized in **ADR-023** (T35, DONE). The resolved Phase-1 decisions (A-1…A-14) and the X-table are in
`tasks-archive.md` and the cited ADRs.

### Dependency order (open tail)

```text
Sprint 7 (active):
  T35 (CORS + ADR-023) ─► T32 (G2 shell, login, contract lifecycle)
      ─► T33 (G3 payment, schedule, statement, credit) ─► T34 (G5 demo)

Sprint 8:
  T28a (PII key ring + v2 envelope) ─► T28b (re-keying pass)      [CR-11]
  T36 (G1 metrics) ─► T37 (G4 FE reports)
  T38 (G6 outbox publisher)

Sprint 8b:
  T33 ─► T39 (FE payments tab, settlement, write-off)
```
