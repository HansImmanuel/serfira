# T34 — G5 Demo Script, Seed Data and README Close-out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One command takes a clean checkout to a proven create → activate → pay → statement sequence against the real Compose backend, repeatably, with READMEs that match it. This closes Sprint 7.

**Architecture:** A dependency-free Node script, `scripts/demo.mjs`, calls the real `/api/v1` API over `fetch`. It has no SQL and no new seed rows: the three demo users from `R__demo_seed.sql` are the only seeded data, and both demo contracts go through the API (ADR-004). Pure helpers (Jakarta date math, exact-decimal money, the API client) live in `scripts/demo/lib.mjs` and are unit-tested with `node --test`. The scenario run itself is the integration test. CI's `compose-smoke` job runs both against the stack it already starts.

**Tech Stack:** Node 24.16.0 (`frontend/.nvmrc`; needs ≥ 22 for global `fetch`, `crypto.randomUUID`, `node:test`), ES modules, no npm dependencies. Docker Compose (existing).

**Spec:** `docs/tasks.md` § T34; `docs/04_GAPS_ADDENDUM.md` §18 (demo data, users, seed), §15; `docs/01_PRD.md` §5 scenarios 1–4 and 6; ADR-004, ADR-007, ADR-009, ADR-015, ADR-022, ADR-023, ADR-024. Backend contracts: `CreateContractRequest`, `ActivateContractRequest`, `PaymentRequest`/`PaymentResponse`, `InstallmentResponse`, `ContractStatementRow`, `AgingReportResponse`, `TokenPairResponse`, `PageResponse`, `ApiResponse`.

**Branch:** `t34-demo-script` from `main` @ `9036501`.

## Decisions taken at planning (2026-11-25)

1. **Demo contracts use relative dates (user decision).** Addendum §18.1/§18.2 describe contracts that are "pre-seeded" with historical payments (installment 1 paid on time in February, installment 2 paid five days late, and so on). The API cannot create that state, for three reasons. `paid_at` is always the server clock (TS §2.0). ADR-004 forbids raw-SQL PII. Activation rejects a start date more than one year back (`MAX_BACKDATED_ACTIVATION_YEARS = 1`), so the fixed `2026-01-31` stops working after January 2027. The script therefore keeps §18's terms (customer, asset, price, down payment, tenor, scheme, rate) and backdates `planned_start_date` relative to today (Asia/Jakarta), then pays today. Task 4 amends §18 and records this as a `Decision` paragraph on T34. It is not an open `A-n`.
2. **Node, not bash/PowerShell.** `tasks.md` suggests `scripts/demo.sh` (or `.ps1`). A bash script needs `jq` to read JSON safely, and `jq` is not available in the Windows dev environment. Node is already a prerequisite for the frontend and runs the same way on Windows, macOS and the Ubuntu CI runner. Task 4 records this choice in the T34 Decision paragraph.
3. **Re-runnable without a reset.** The same customers are reused on every run: NIK plus phone match, so `findOrCreateCustomer` returns the existing row. The asset serial is unique per run (`DEMO-A-<runTag>`) and the plate is left out. Invariant 18 (409 `DUPLICATE_CONTRACT`) would reject a second live contract for the same customer and asset, and §18's plate would make the asset resolver reuse the first run's asset. Each run therefore creates two new contracts on purpose. Every write step gets a fresh `crypto.randomUUID()` idempotency key.
4. **The reset is opt-in.** §18.4 says the script starts with `docker compose down -v`. That deletes the local volume, so the script does it only with `--reset`. `--up` only starts the stack. With no flag, the script expects a stack that is already running. CI uses the no-flag mode because the job has already started the stack.

## Global Constraints

- No SQL, no new `R__demo_seed.sql` rows, no plaintext PII anywhere except the request body (ADR-004). Demo passwords come from Addendum §18.3: `admin`/`Admin123!`, `finance`/`Finance123!`, `manager`/`Manager123!`. Gitleaks must stay green. These are documented demo credentials, so add an allowlist entry in `.gitleaks.toml` only if gitleaks flags them.
- Money on the wire is an exact decimal string (ADR-024). Compare money as `BigInt` cents via `toCents`; never `Number`/`parseFloat` a money value.
- Wire JSON is snake_case and wrapped as `{ data, error }`. Only `lib.mjs`'s client unwraps the envelope.
- "Today" is the Asia/Jakarta business date (ADR-003), computed with `Intl.DateTimeFormat` `timeZone: "Asia/Jakarta"`. Never use the machine's local zone.
- Every failure exits non-zero and prints the method, path, HTTP status and raw response body. No step is retried silently.
- No frontend container or Compose service (T34 Implementation Notes; Deferred table).
- `./gradlew check` and frontend `npm test` stay green. No backend or frontend source changes are expected.
- Commit messages: `feat(t34): …` / `ci(t34): …` / `docs(t34): …`, with no AI co-author or attribution trailer (the repo owner is the sole author).

## Review Focus

1. **Running on the 29th–31st of a month.** `minusMonths` must clamp to the month's last day (2027-03-31 minus 1 month → 2027-02-28), so Contract B's installment 1 still falls due on or before today and within the 3-day grace period, and therefore carries no penalty. Test in Task 1.
2. **The script machine is not in Asia/Jakarta.** Between 17:00 and 24:00 UTC the UTC date is already one day behind Jakarta. `jakartaToday(new Date("2026-11-25T18:30:00Z"))` must return `"2026-11-26"`. Test in Task 1.
3. **A second run against the same database.** It must not fail with 409 `DUPLICATE_CONTRACT` or `CONFLICT` (NIK/phone). Covered by the fixed NIK + phone pairs and the per-run serial (Task 2), and proven by running the script twice in Task 2 Step 4.
4. **Backend not up yet, or down.** `--up`/CI start the script while Spring is still migrating. Health polling must tolerate connection refused until a 5-minute deadline, then fail with a clear message. Test in Task 1 (`waitForHealth` with a fake fetch that rejects twice, then returns UP).
5. **An error envelope on a 2xx, or a non-JSON body (proxy HTML, empty 502).** The client must still throw a `DemoError` that carries the raw text, never `undefined.data`. Test in Task 1.

---

### Task 1: Demo helpers — Jakarta dates, exact money, API client

**Files:**
- Create: `scripts/demo/lib.mjs`
- Create: `scripts/demo/lib.test.mjs`

**Interfaces:**
- Produces (`scripts/demo/lib.mjs`):
  - `jakartaToday(now: Date = new Date()): string` returns `YYYY-MM-DD` in Asia/Jakarta.
  - `minusDays(isoDate: string, days: number): string`
  - `minusMonths(isoDate: string, months: number): string` clamps to the last day of the target month.
  - `toCents(amount: string | null | undefined): bigint` accepts `"1573333.33"`, `"240000"` and `"0.5"`; null/undefined → `0n`. Throws on anything that is not `^-?\d+(\.\d{1,2})?$`.
  - `sumMoney(amounts: string[]): string` returns a scale-2 string, e.g. `"2000000.00"`.
  - `runTag(now: Date = new Date()): string` returns `YYYYMMDDHHmmss` in UTC; it suffixes asset serials.
  - `class DemoError extends Error { method; path; status; body }`
  - `createApiClient({ baseUrl: string, fetchImpl?: typeof fetch }): { request(method, path, { token?, body?, idempotencyKey?, expectStatus? = 200 }): Promise<any> }`. It sends `Content-Type: application/json`, plus `Authorization: Bearer <token>` and `Idempotency-Key` when given. It returns `json.data` when the status equals `expectStatus` and `json.error` is null. If `expectStatus` is not 2xx, it returns `{ status, error }`. Anything else throws a `DemoError`.
  - `waitForHealth({ baseUrl, fetchImpl?, timeoutMs = 300_000, intervalMs = 5_000, sleep? }): Promise<void>` polls `GET /actuator/health` until `status === "UP"` and throws a `DemoError` with the last error at the deadline.

- [ ] **Step 1: Write the failing tests** in `scripts/demo/lib.test.mjs` (`node:test` + `node:assert/strict`):

```js
test("jakartaToday crosses midnight ahead of UTC", () => {
  assert.equal(jakartaToday(new Date("2026-11-25T18:30:00Z")), "2026-11-26");
  assert.equal(jakartaToday(new Date("2026-11-25T16:59:59Z")), "2026-11-25");
});
test("minusMonths clamps to month end", () => {
  assert.equal(minusMonths("2027-03-31", 1), "2027-02-28");
  assert.equal(minusMonths("2028-03-31", 1), "2028-02-29");
  assert.equal(minusMonths("2026-01-15", 1), "2025-12-15");
});
test("minusDays crosses month and year", () => {
  assert.equal(minusDays("2026-11-25", 70), "2026-09-16");
  assert.equal(minusDays("2026-01-05", 10), "2025-12-26");
});
test("toCents is exact and strict", () => {
  assert.equal(toCents("1573333.33"), 157333333n);
  assert.equal(toCents("240000"), 24000000n);
  assert.equal(toCents("0.5"), 50n);
  assert.equal(toCents(null), 0n);
  assert.throws(() => toCents("1e6"));
  assert.throws(() => toCents("1.234"));
});
test("sumMoney adds without float drift", () => {
  assert.equal(sumMoney(["0.10", "0.20"]), "0.30");
  assert.equal(sumMoney(["1125000.00", "3644959.90"]), "4769959.90");
});
// createApiClient, with a fake fetchImpl that records calls:
//  - sends Authorization and Idempotency-Key headers when given, and JSON.stringify(body)
//  - 200 + {data:{x:1},error:null} → resolves {x:1}
//  - expectStatus 403 + 403 {data:null,error:{code:"FORBIDDEN"}} → resolves {status:403,error:{code:"FORBIDDEN"}}
//  - 409 when 200 expected → rejects DemoError {status:409, body contains the raw text}
//  - 200 + {data:null,error:{code:"X"}} → rejects DemoError
//  - 502 + "<html>bad gateway</html>" → rejects DemoError whose body is that text (no JSON.parse crash)
// waitForHealth:
//  - fetch rejects twice, then {status:"UP"} → resolves; sleep called twice
//  - always rejects → rejects DemoError after timeoutMs (inject sleep + a fake clock)
```

- [ ] **Step 2: Run to verify they fail**

Run: `node --test scripts/demo/`
Expected: FAIL with `Cannot find module …/lib.mjs`.

- [ ] **Step 3: Implement `scripts/demo/lib.mjs`** to the signatures above. Use `Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Jakarta" })` for `jakartaToday`. Do the date arithmetic on UTC `Date` values built from the ISO parts, never the local zone. `waitForHealth` takes an injectable `now()` for the deadline so the test needs no real waiting.

- [ ] **Step 4: Run to verify they pass**

Run: `node --test scripts/demo/`
Expected: all tests pass, 0 fail.

- [ ] **Step 5: Commit**

```bash
git add scripts/demo/lib.mjs scripts/demo/lib.test.mjs
git commit -m "feat(t34): add demo script helpers for Jakarta dates, exact money and the API client"
```

---

### Task 2: The demo scenario script

**Files:**
- Create: `scripts/demo.mjs`

**Interfaces:**
- Consumes: everything that Task 1's `lib.mjs` produces.
- Produces: CLI `node scripts/demo.mjs [--up] [--reset] [--base-url <url>] [--skip-roles]`. `--base-url` defaults to `SERFIRA_API_BASE_URL`, then `http://localhost:8080`. The script exits 0 only when every assertion holds. Its last stdout line is `DEMO OK — contract A <contract_no>, contract B <contract_no>` (Task 3's CI step and Task 4's README quote it).

Run order, with the exact values the script asserts. `today = jakartaToday()` and `tag = runTag()`:

0. **Stack.** `--reset` runs `docker compose down -v` and then behaves like `--up`. `--up` runs `docker compose up --build -d` (`spawnSync`, `stdio: "inherit"`, repo root as `cwd`, abort on a non-zero exit). Then `waitForHealth`.
1. **Login** as `admin`. `POST /api/v1/auth/login` with `{username, password}` gives `access_token`.
2. **Contract B (EFFECTIVE), PRD scenario 1: on-time full payment.** Customer `Siti Rahayu`, NIK `3275015506850002`, phone `081200000002`. Asset `CAR` `Toyota` `Avanza`, `serial_no: "DEMO-B-<tag>"`, no plate. Terms: `asset_price "200000000.00"`, `down_payment "50000000.00"`, `tenor_months 36`, `EFFECTIVE`, `interest_rate "0.0075"`, `planned_start_date = minusMonths(today, 1)`.
   - `POST /contracts` (new key) → 201, `status === "DRAFT"`, `principal` equals `"150000000.00"` by `toCents`.
   - `POST /contracts/{id}/activate` with body `{}` → 200, `status === "ACTIVE"`.
   - `GET /contracts/{id}/installments` → 36 rows. Installment 1 has principal `3644959.90` and interest `1125000.00`. Installment 36 has principal `4734451.45` and interest `35508.39`. Σ principal is `150000000.00` and Σ interest is `21718556.34` (§18.2 golden values).
   - `POST /payments` (new key) with `{contract_id, amount: "4769959.90", channel: "BANK_TRANSFER"}` → 201. The allocations are exactly `[INTEREST 1125000.00, PRINCIPAL 3644959.90]` on installment 1. There is no `PENALTY` line and `excess_amount` is zero.
   - **PRD scenario 6 (retry).** Repeat the identical POST with the **same** key → same `id` and `payment_no`.
   - `GET installments` → installment 1 `status === "PAID"`.
3. **Contract A (FLAT), PRD scenarios 2 and 3: late payment with penalty first, then a partial payment.** Customer `Budi Santoso`, NIK `3171012501900001`, phone `081200000001`. Asset `MOTORCYCLE` `Honda` `Beat`, `serial_no: "DEMO-A-<tag>"`. Terms: `"20000000.00"` / `"4000000.00"`, tenor 12, `FLAT`, `"0.0150"`, `planned_start_date = minusDays(today, 70)`. This puts installment 1 about 40 days late and installment 2 about 10 days late, both past the 3-day grace.
   - Create → activate, as in step 2.
   - Installments: 12 rows. Installment 1 has principal `1333333.33` and interest `240000.00`. Installment 12 has principal `1333333.37` (it absorbs the residual). Σ principal is `16000000.00` (§18.1 golden values).
   - `POST /payments` `{amount: "2000000.00", channel: "CASH"}` → 201. `allocations[0]` is `PENALTY` on installment 1, and the sum of the allocations equals `"2000000.00"` by `toCents`. At the default 0.1 %/day penalty, installment 1 needs about 1.63 M, so the rest lands on installment 2.
   - `GET installments` straight after the payment → installment 1 `PAID`, installment 2 `PARTIALLY_PAID` with `outstanding > 0`, installment 3 `PENDING`.
4. **Statement.** `GET /contracts/{A}/statement?size=100` → `ref_type` values include `CONTRACT_ACTIVATION`, `BILLING`, `PENALTY_ACCRUAL` and `PAYMENT`. Print a compact table of entry date, ref type, account, debit and credit.
5. **Aging.** `GET /reports/aging` → 200, and `portfolio.total_outstanding > 0`.
6. **Role split** (skipped with `--skip-roles`). Log in as `finance`: statement → 200, `POST /payments` → 403 (`expectStatus: 403`). Log in as `manager`: aging → 200, statement → 403. The 403 checks send a valid body and a fresh key, so authorization is the only thing that fails.
7. Print the contract numbers, and the hint "open http://localhost:3000, log in as admin, search <contract_no>". Then print the final `DEMO OK — …` line.

- [ ] **Step 1: Write `scripts/demo.mjs`** with steps 0–7 as small named async functions (`login`, `createAndActivate`, `assertSchedule`, `pay`, `installments`, …) so a failure names the step. Log one line per step: `✓ B: payment 4769959.90 → installment 1 PAID`. Parse flags by hand from `process.argv`; do not add a dependency.
- [ ] **Step 2: Run it against a fresh stack**

Run: `node scripts/demo.mjs --reset`
Expected: exit code 0, and the last line starts with `DEMO OK — contract A`.

- [ ] **Step 3: Prove a failure is loud.** Run `node scripts/demo.mjs --base-url http://localhost:9` (nothing listens there).
Expected: a non-zero exit after the health deadline, with a message that names `/actuator/health`. Pass `SERFIRA_DEMO_HEALTH_TIMEOUT_MS=10000` to keep this short; add that env override to `waitForHealth`'s call site.
- [ ] **Step 4: Prove re-runnability.** Run `node scripts/demo.mjs` twice more against the same, un-reset stack.
Expected: both runs exit 0 with new contract numbers, and there is no 409.
- [ ] **Step 5: Follow along in the browser.** With `cd frontend && npm run dev`, log in as `admin`, open Contract A by its number, and check that the Jadwal tab, the payment allocation and the statement match the script's output.
- [ ] **Step 6: Commit**

```bash
git add scripts/demo.mjs
git commit -m "feat(t34): add end-to-end demo script for create, activate, pay and statement"
```

---

### Task 3: Run the demo in CI's compose-smoke job

**Files:**
- Modify: `.github/workflows/ci.yml` (`compose-smoke` job, after "Assert the OpenAPI contract is served", before "Tear down")

**Interfaces:**
- Consumes: `node --test scripts/demo/` (Task 1) and `node scripts/demo.mjs` (Task 2, no flags; the stack is already up).

- [ ] **Step 1: Add the steps.** Add `actions/setup-node@v4` with `node-version-file: frontend/.nvmrc`. Add "Demo helpers unit tests": `node --test scripts/demo/`. Add "Run the end-to-end demo script": `node scripts/demo.mjs`, which on failure runs `docker compose logs app` and exits 1, the same way the health step does.
- [ ] **Step 2: Validate the workflow locally.** Run `docker compose config -q` and lint the YAML (`npx --yes yaml-lint .github/workflows/ci.yml`, or `actionlint` if it is installed).
Expected: no errors. CI is currently red for a GitHub billing reason; note that in the PR instead of treating it as a failure.
- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(t34): run the demo helpers' tests and the demo script in compose-smoke"
```

---

### Task 4: READMEs, Addendum §18 and closing T34

**Files:**
- Modify: `README.md`: add a "Demo" subsection under Getting started (prerequisites: Docker and Node ≥ 22; `node scripts/demo.mjs --up`, `--reset`, `--skip-roles`; what it proves, mapped to PRD scenarios 1, 2, 3 and 6; the three demo users and their roles, citing §18.3; follow along in the browser). Update the Status row for phase 3 (the dashboard core is built; reports and outbox are pending). Add `scripts/` to Repository layout. Change "22 Architecture Decision Records" to the actual count (`ls docs/adr | wc -l`).
- Modify: `frontend/README.md`: add a "Running the demo" section (backend via Compose, `npm run dev`, then `node scripts/demo.mjs`). Add "Known limitations": tokens live in memory, so a reload means logging in again; there is no payments-history tab, settlement or write-off yet (T39); there are no reports yet (T36/T37); and there is no frontend container.
- Modify: `docs/04_GAPS_ADDENDUM.md` §18: §18.1/§18.2 keep their terms and golden values. Replace the "pre-seeded" state with the relative-date run state (Decision 1), and give the script as `scripts/demo.mjs`. Amend §18.4's reset snippet to `node scripts/demo.mjs --reset`.
- Modify: `docs/tasks.md`: add the T34 `Decision (2026-11-25)` paragraph (Decisions 1–3), then close T34 by the issue-tracker conventions (`Status: DONE`, an Implementation note with the commands and the demo output tail, a Completed Work row, Current Project State, and Sprint 7 marked COMPLETE).
- Modify: `docs/PROGRESS.md`: tick T34 and close Sprint 7.

- [ ] **Step 1: Write the doc changes above.** Every command in the READMEs must be copied from a command that ran in Task 2.
- [ ] **Step 2: Verify the READMEs against reality.** On a fresh clone (`git clone . ../serfira-t34-check`), follow the root README's Demo section word for word.
Expected: `DEMO OK`. Then delete the clone.
- [ ] **Step 3: Run the full gates**

Run: `cd backend && ./gradlew check`, then `cd frontend && npm test && npm run lint && npm run typecheck`, then `node --test scripts/demo/`
Expected: backend 932 tests and 0 failed (unchanged); frontend 178 tests and 0 failed (unchanged); demo helpers 0 failed. Record the counts in the T34 Implementation note.
- [ ] **Step 4: Commit and open the PR**

```bash
git add README.md frontend/README.md docs/04_GAPS_ADDENDUM.md docs/tasks.md docs/PROGRESS.md
git commit -m "docs(t34): document the demo, amend Addendum §18 and close T34 and Sprint 7"
git push -u serfira t34-demo-script
gh pr create --base main --title "T34: G5 demo script, README close-out (closes Sprint 7)"
```
