# Serfira tasks

Canonical implementation backlog. From Sprint 4b onward this file supersedes the per-sprint allocation in
`05_SPRINT_PLAN.md`. The story IDs used there (A1…G6) are kept as cross-references. Business rules stay in
`01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → ADRs. This file points to
those documents and does not restate them.

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`.
Estimates use the sprint plan's points (1 pt ≈ 2–3 h; target velocity 8–13 pts per sprint).

Completed-task detail (full implementation notes for DONE work, the resolved X-table, the CR-01…CR-16
review table, and historical verification snapshots) is archived in `tasks-archive.md`. This file keeps the
live backlog: the current state snapshot, the compact Completed Work index, open tasks, and open
ambiguities.

---

## Current Project State

**Snapshot:** `main` @ `0b282c0` plus the T7 implementation (ADR-015: RBAC matrix, roles-claim converter,
fail-closed JWT identity, `support/TestJwts`, retired audit probe), the T8 implementation (new read-only
`reporting` module with the aging report), the T9 implementation (contract statement read through a new
`ledger` statement port, V11 index), the T24 implementation (ADR-017: idempotency keys are single-use
forever, 409 `IDEMPOTENCY_KEY_EXPIRED`, the payment classifier no longer retries `uq_payment_idempotency`,
`BACKOFF_MILLIS` tidied), T25 (V12 accounting backstops), T26 (V13 `ABANDONED` + daily job robustness),
T29 (statement query `cast` fix), and the T11 implementation (Phase-1 test hardening: child-table
immutability tests, specific-exception assertions, `Phase1ExitScenariosIT`, `PiiLoggingIT`) — all on branch
`t11-phase1-exit-verification` @ `a8fa1a6`, pending merge.

**Latest verification — 2026-10-05 (T11), Docker available so the Testcontainers `*IT` suites ran:**

- `.\gradlew check`: pass.
- Forced full `.\gradlew test --rerun-tasks` (all tasks executed, not cached): **80 suites / 633 tests /
  0 failures / 0 errors / 0 skipped** (621 before T11 + 12 new tests: 4 child-table immutability, the 7
  `Phase1ExitScenariosIT` scenarios, and `PiiLoggingIT`).
- Diff is test-only: `AccountingInvariantsIT`, `Phase1ExitScenariosIT` (new), `PiiLoggingIT` (new),
  `ContractIdempotencyIT`, `PaymentIdempotencyIT`. No production code, migration, or unrelated file changed.

Older per-task verification snapshots (T8, T9, T24, T25, T29) live in `tasks-archive.md`.

**Implemented (verified in source):**

- Modules `contract`, `payment`, `penalty`, `ledger`, `reporting`, `shared`. `settlement` does not exist;
  `frontend/` is empty.
- Migrations V1–V13. Baseline + accounting triggers (V3), state coherence (V4), settlement immutability (V5),
  contract-create safety (V6/V7), SYSTEM hardening (V8), ShedLock + penalty-accrual integrity (V9),
  `job_run.business_date` (V10), the `journal_line (contract_id, entry_date)` statement index (V11, T9), the
  parent-side accounting backstops + `uq_journal_entry_event` + `system_parameter` append-only (V12, T25),
  and the `ck_job_run_status` widen for `ABANDONED` (V13, T26).
- Daily servicing (T3, hardened by T26): one renewable ShedLock for cron + backfill; every ACTIVE contract
  processed atomically billing → penalty → aging with five-attempt backoff retry, failure isolation, SYSTEM
  audit, three `job_run` rows; crashed `RUNNING` rows closed `ABANDONED`; keyset paging by
  `serfira.jobs.daily-servicing.batch-size` (default 500).
- Payment path: lazy billing + accrual inside the idempotent transaction (T4, ADR-014); write-path conflict
  retry → 409 `CONCURRENT_MODIFICATION` (T5); single-use-forever idempotency keys → 409
  `IDEMPOTENCY_KEY_EXPIRED` past retention (T24, ADR-017).
- Reads: aging report `GET /api/v1/reports/aging` (T8, read-only `reporting` module) and contract statement
  `GET /api/v1/contracts/{id}/statement` (T9, ledger-literal via `ledger.application.ContractStatementPort`;
  query fixed for PostgreSQL in T29).
- DB accounting backstops (T25, V12): parent-side deferred ≥2-balanced-lines / Σ-allocations checks, scoped
  `uq_journal_entry_event`, `system_parameter` append-only.
- Endpoints (8): `POST /api/v1/contracts`, `POST /api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`,
  `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`,
  `GET /api/v1/contracts/{id}/statement`, `POST /api/v1/payments`, `GET /api/v1/reports/aging`.
- Security: JWT HS256 resource server, default-deny, Addendum §3.4 role matrix enforced on all eight
  endpoints (T7, ADR-015). `sub` must be a UUID with `exp`. No login/refresh/logout, no `iss`/`aud`
  validation yet (ADR-005; T21).

Full per-task implementation notes for all DONE work are archived in `tasks-archive.md`.

**Current sprint:** Sprint 4 ("Penalty, Aging & Phase-1 Close") is complete. C4/D1 and re-planned T1–T6 are
DONE (Sprint 4b), Sprint 4c (T23, T7, T8, T9) is complete, and **Sprint 4d is complete**: T24 (idempotency
key lifecycle), T25 (DB accounting backstops), T26 (daily job robustness), T29 (fix the pre-existing
statement query, X-13) and T11 (Phase-1 test hardening + exit verification) are all DONE. T10 stays
DEFERRED (T1.a kept ADR-012 decision 2; no code change required). Next: Sprint 5 (T12–T15 settlement,
credit, waivers).

**Blockers and critical gaps:** none open. The two that remained after T6 (the unenforced Addendum §3.4
matrix, and a non-UUID `sub` writing as `SYSTEM`) were closed by T7 (ADR-015).

**Specification/implementation discrepancies:** X-1 … X-13 are all resolved except **X-3** (observability:
`X-Request-Id`, JSON logs, `idempotency_keys.request_id` — open, owned by T20) and **X-9** (TS §7 repo/
package names — doc-only, DEFERRED). The resolved X-table with per-item detail is in `tasks-archive.md`.

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
| T25   | DB accounting backstops: V12 parent-side deferred checks, scoped `uq_journal_entry_event`, `system_parameter` append-only                            | DONE   | ADR-008 d5 note (CR-05/06/12)  |
| T26   | Daily job robustness: `ABANDONED` status + V13, stale-row/loop-escape finalization, keyset batching, retry backoff                                   | DONE   | ADR-013 A-9 note T26 (CR-07/08/09) |
| T29   | Fix contract statement query on PostgreSQL: `cast(...)`-typed nullable binds in `findContractStatement`/`countQuery`, clearing `42P18`                | DONE   | X-13 (bug task)                |
| T11   | Phase-1 test hardening + exit verification: child-table immutability tests, specific-exception assertions, PRD §7 scenarios over HTTP, PII log guard | DONE   | commit `a8fa1a6`               |
| —     | Phase A hygiene: V8 SYSTEM hardening, idempotency retention takeover (semantics revisited in T24), open-in-view off                                  | DONE   | `cdce254`                      |

---

## Sprint 4 — Phase-1 Close (4b/4c/4d): COMPLETE

All of Sprint 4's re-planned work is DONE and indexed in the Completed Work table above. Full per-task
specs and implementation notes are archived in `tasks-archive.md`. Summary of the three sub-sprints:

- **4b — Penalty correctness & daily job (T1–T6, DONE).** Phase-1 decisions (ADR-013); V9 ShedLock +
  penalty-accrual integrity; the daily billing → penalty → aging job; lazy accrual in the payment path
  (ADR-014); payment write-path conflict retry; the aging step. Exit met: PRD scenario 2 end-to-end with no
  manual accrual, a same-date rerun writes nothing, `job_run` rows per step.
- **4c — Stack alignment, access control & Phase-1 reads (T23, T7, T8, T9, DONE).** Boot 4 dependency
  alignment (ShedLock 7.10.1, springdoc 3.1.1); RBAC + fail-closed JWT identity (ADR-015); the aging report;
  the contract statement. Exit met: Addendum §3.4 matrix enforced, `/v3/api-docs` covered by a test.
- **4d — Integrity hardening & exit (T24, T25, T26, T29, T11, DONE; T10 DEFERRED).** Idempotency key
  lifecycle (ADR-017); DB accounting backstops (V12); daily-job robustness (V13); the statement-query
  PostgreSQL fix; Phase-1 test hardening + exit verification. Phase-1 exit criteria proven (PRD §7).

### T10 — Component-exact penalty base (conditional, DEFERRED)

Status: DEFERRED — T1.a / ADR-013 kept ADR-012 decision 2 unchanged, so no code change is required unless a
**new** business decision explicitly demands component-exact penalty precision (base from unpaid principal +
recognized interest only, excluding penalty already paid, per a literal TS §4.3 reading; and/or a per-date
historical base). If that decision is ever taken, this task reopens at ~3 pts: carry the component split (and
business dates, if a historical base is chosen) across the `contract` port, change `InstallmentBalance.penaltyBase`,
and prove the next-day charge after a penalty-only payment equals `rate × unpaid (principal + recognized
interest)` (worked check: 1,573.33, not 1,570.19). Depends on T1.a, T4. ADR-012 alternatives 5/6 list the
rejected options T1.a would have to supersede.

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
| T18 | F4 Idempotency key cleanup job                                     | 6      | TODO   | Reuses the T3/T26 job infrastructure. Deleting a row must not re-open the key: the T24/A-13 rule and the business-row backstops decide what a later reuse returns. Note (ADR-017 consequences, PR #5 bot review): after the row is deleted the exact 409 depends on which truthful guard fires first (`DUPLICATE_CONTRACT` for a same-request contract retry, `CONTRACT_STATE_INVALID` for a payment against a now-inactive contract), not necessarily `IDEMPOTENCY_KEY_EXPIRED`. Decide here whether a pre-business-logic permanent-key probe is wanted to make the code uniform.                                                                                  |
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

### Review history (archived)

The 2026-09-29 code review and the 2026-09-30 external review (16 findings, CR-01…CR-16) are both resolved
or mapped to tasks. The full CR verdict table and the review corrections are in `tasks-archive.md`. The CR
findings that remain **open** map to still-TODO tasks: CR-10/CR-16 → T21, CR-11 → T28, CR-14 → T15,
CR-15 → T27. All others are DONE (T7, T23, T24, T25, T26).

<!-- The CR-01…CR-16 verdict table and the 2026-09-29 correction notes moved to tasks-archive.md on 2026-10-05. -->

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
