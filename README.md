# Serfira — Multifinance Loan Servicing Core

[![CI](https://github.com/HansImmanuel/serfira/actions/workflows/ci.yml/badge.svg)](https://github.com/HansImmanuel/serfira/actions/workflows/ci.yml)

Serfira is the **servicing core of a loan management system** for a multifinance company
(vehicle and consumer-goods financing, Indonesian market). It covers everything that happens
after a loan is approved: installment schedules, payments, allocation, late penalties, aging,
and a double-entry ledger that records every money movement.

It is a portfolio project built around one idea: **money must always balance**. Financial rules
are enforced twice, in pure Java engines and again as PostgreSQL constraints, so a bug in one
layer cannot silently corrupt the books.

**Stack:** Java 21 · Spring Boot 4 · PostgreSQL 16 · Flyway · Testcontainers · ShedLock · Docker

---

## Highlights

- **Double-entry ledger.** Every financial event (disbursement, interest billing, penalty
  accrual, payment) posts a balanced journal entry. Journal rows are immutable; corrections are
  reversal entries, never updates. → [ADR-002](docs/adr/ADR-002-double-entry-ledger.md),
  [ADR-008](docs/adr/ADR-008-ledger-posting-semantics.md)
- **Invariants enforced in the database.** Deferred constraint triggers reject unbalanced
  journals, allocations that don't sum to the payment, and installment over-payment.
  `BEFORE UPDATE OR DELETE` triggers make posted records append-only.
- **Idempotent writes.** `POST /contracts` and `POST /payments` require an `Idempotency-Key`.
  A retry replays the original response; the same key with a different payload returns `409`.
  → [ADR-007](docs/adr/ADR-007-idempotent-contract-creation.md)
- **Concurrency under contention.** The daily job and live payments can touch the same
  installments. Optimistic locking plus bounded retry (3 attempts, 50 and 150 ms pauses) handles
  lock conflicts and unique-violation races; an integration test forces a real race against
  PostgreSQL.
- **Correct even when the batch job is late.** Payments bill due interest and accrue penalties
  lazily in the same transaction, so a payment is allocated correctly whether or not the nightly
  job has run. → [ADR-011](docs/adr/ADR-011-billing-recognition-and-maturity-close.md),
  [ADR-014](docs/adr/ADR-014-lazy-penalty-accrual-in-payment-path.md)
- **Deterministic time.** An injectable clock and a fixed business zone (`Asia/Jakarta`) make
  date-sensitive logic (due dates, grace periods, month-end clamping, leap years) fully testable.
  → [ADR-003](docs/adr/ADR-003-injectable-clock.md)
- **PII protected at rest.** National ID (NIK) and phone numbers are AES-256-GCM encrypted;
  uniqueness and search use HMAC lookup columns. → [ADR-004](docs/adr/ADR-004-at-rest-pii-protection.md);
  keys rotate through a key ring → [ADR-025](docs/adr/ADR-025-pii-key-ring.md)
- **Decisions on record.** 24 [Architecture Decision Records](docs/adr/) with alternatives
  considered and rejected.

## Domain in 60 seconds

| Term             | Meaning                                                                                                                                             |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| Contract         | A financing agreement for one asset and one customer: principal, down payment, tenor, rate                                                          |
| Schedule         | Monthly installments generated at activation. **FLAT** (interest on original principal) or **EFFECTIVE** (annuity, interest on outstanding balance) |
| Allocation       | How a payment is split: **penalty → interest → principal**, oldest due installment first. Overpayment is held as customer credit                    |
| Penalty          | Daily late fee on overdue amounts, after a grace period                                                                                             |
| Aging            | Delinquency buckets: Current, 1–30, 31–60, 61–90, >90 days                                                                                          |
| Early settlement | Paying off the full outstanding early, with a rebate on interest and an admin fee                                                                   |

Example posting rules:

| Event                              | Debit                | Credit                                                     |
| ---------------------------------- | -------------------- | ---------------------------------------------------------- |
| Contract activation (disbursement) | Principal receivable | Cash                                                       |
| Interest billed on due date        | Interest receivable  | Interest income                                            |
| Daily penalty accrual              | Penalty receivable   | Penalty income                                             |
| Payment received                   | Cash                 | Penalty / interest / principal receivable, customer credit |

Full rules: [Tech Spec §3](docs/02_TECH_SPEC.md).

## Architecture

A **modular monolith**: one Spring Boot deployable, one PostgreSQL database, with bounded
contexts separated by package and communicating only through application-layer ports.
No module reads another module's tables, with one documented exception: the JWT decoder in
`shared/security` performs an indexed `app_user` lookup to reject inactive users (ADR-022).
→ [ADR-001](docs/adr/ADR-001-modular-monolith.md)

```mermaid
flowchart LR
    subgraph API["REST API (JWT)"]
        CAPI["Contracts API"]
        PAPI["Payments API"]
    end

    JOB["Daily servicing job<br/>01:00 Asia/Jakarta · ShedLock"]

    CAPI --> contract
    PAPI --> payment
    JOB --> penalty

    payment -- "InstallmentReceivablePort<br/>InstallmentBillingPort" --> contract
    payment -- PenaltyAccrualPort --> penalty
    penalty -- "InstallmentPenaltyPort<br/>InstallmentAgingPort" --> contract

    contract --> ledger
    payment --> ledger
    penalty --> ledger

    contract & payment & penalty & ledger --> DB[(PostgreSQL 16<br/>constraints + immutability triggers)]
```

| Module       | Responsibility                                                        | Status |
| ------------ | --------------------------------------------------------------------- | ------ |
| `contract`   | Contracts, customers, assets, activation, schedule engine             | Built  |
| `payment`    | Payments, allocation engine, retry on contention                      | Built  |
| `penalty`    | Penalty accrual + waiver, consistency-check, daily job                | Built  |
| `ledger`     | Double-entry journal, chart of accounts, posting                      | Built  |
| `settlement` | Early settlement quote and execution                                  | Built  |
| `reporting`  | Read-only reports (aging, outstanding)                                | Built  |
| `auth`       | App users, Argon2id login, token rotation/logout/lockout              | Built  |
| `shared`     | Clock, audit, error envelope, idempotency, document numbers, security | Built  |

Every module follows `com.serfira.<module>.{api, application, domain, infrastructure}`.
Dependencies point one way: `ledger` depends on nothing, and `contract` does not know about
`payment` or `penalty`. The daily job runs billing → penalty → aging → consistency-check.

## Getting started

Prerequisites: Docker. JDK 21 to run the backend tests; Node ≥ 22 (`frontend/.nvmrc` pins 24.16.0) for
the demo script and the frontend.

```bash
# Run PostgreSQL and the app
docker compose up --build
```

- API: http://localhost:8080
- OpenAPI UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health

Flyway applies all migrations on a clean database. `docker-compose.yml` ships
**local-development-only** defaults for the database password, PII keys, and JWT secret; override
them through environment variables or a `.env` file. PostgreSQL is published on `127.0.0.1` only.

Compose starts with the `demo` Spring profile, which adds a demo-only Flyway location and seeds the
three login users from Addendum §18.3 (`admin`, `finance`, `manager`; credentials are documented
only there). Override `SPRING_PROFILES_ACTIVE=prod` (or an empty value) to start without the demo
seed. A database first created with `demo` must be reset before switching to a non-demo profile
because the repeatable `R__demo_seed.sql` would then no longer be resolved.

### Demo

One command brings the stack up and proves the main operator flow against the real API:

```bash
node scripts/demo.mjs --up        # docker compose up --build -d, then the demo
node scripts/demo.mjs             # against a stack that is already running (safe to re-run)
node scripts/demo.mjs --reset     # docker compose down -v first: DELETES the local database volume
```

Options: `--base-url <url>` (default `$SERFIRA_API_BASE_URL` or `http://localhost:8080`) and
`--skip-roles`. `--up` and `--reset` only manage the local Compose stack, so they refuse a base URL that is
not `localhost`, `127.0.0.1` or `[::1]` before running any Compose command. To run the demo against an
already-running remote stack, pass `--base-url` without them. The script needs no npm install. It exits 0 and ends with
`DEMO OK — contract A MF-…, contract B MF-…`; any failure exits 1 and prints the request, the HTTP status
and the response body.

| Step | What it proves |
| --- | --- |
| Log in as `admin` | Seeded demo user, Argon2id login, bearer token (ADR-022) |
| Contract B: EFFECTIVE, Rp 150 M over 36 months, started one month ago | Schedule matches the golden values (installment 1 = 4,769,959.90); an on-time payment pays interest then principal with no penalty (PRD scenario 1); the same `Idempotency-Key` replays the same payment and creates no customer credit (scenario 6) |
| Contract A: FLAT, Rp 16 M over 12 months, started 70 days ago | Penalty accrues lazily and is paid first, then interest, then principal (scenario 2); the remainder leaves installment 2 `PARTIALLY_PAID` (scenario 3) |
| Statement and aging | Ledger-literal statement with activation, billing, penalty and payment rows, Σ debit = Σ credit; aging totals |
| `finance` and `manager` | Statement 200 / payment 403 for finance; aging 200 / statement 403 for manager (ADR-015) |

Demo users (Addendum §18.3, `demo` profile only, never real accounts): `admin` / `Admin123!`
(`ADMIN_OPERASIONAL`), `finance` / `Finance123!` (`FINANCE`), `manager` / `Manager123!` (`MANAJEMEN`).

Customers and contracts are created through the API, never by SQL (ADR-004). The two demo customers are
reused across runs, and every run creates two new contracts with a per-run asset serial, so re-running
never hits `DUPLICATE_CONTRACT`. Dates are relative to today in Asia/Jakarta, because a payment always
takes the server's clock.

To follow along in the browser, start the frontend (`cd frontend && npm ci && npm run dev`), open
http://localhost:3000, log in as `admin`, and search for the contract number the script prints. **Laporan →
Aging / Outstanding** shows both reports to all three demo users. See [frontend/README.md](frontend/README.md).

### Running tests

```bash
cd backend
./gradlew test        # Windows: gradlew.bat test
```

One command runs both unit tests and `*IT` integration tests (Testcontainers starts a real
PostgreSQL 16, so Docker must be running). CI runs the same task on every push and pull request.

### Security and CI gates

CI runs five gate jobs — plus a notice job that only appears on fork-originated PRs — so a scanner or boot
failure is never mistaken for a test failure:

| Job                       | What it does                                                                                          |
| ------------------------- | ----------------------------------------------------------------------------------------------------- |
| `test`                    | `./gradlew check` — unit tests + Testcontainers integration tests                                      |
| `frontend`                | `npm ci` → lint → typecheck → unit/component tests → production build (Node from `frontend/.nvmrc`)    |
| `secrets`                 | pinned gitleaks CLI: full commit history (`gitleaks git`) and working tree (`gitleaks dir`), both with `.gitleaks.toml` |
| `dependencies`            | OWASP Dependency-Check on the real dependency tree; fails on High or Critical (CVSS ≥ 7.0)             |
| `dependencies-fork-notice` | fork PRs only: states that the keyed dependency gate was **skipped**, never that it passed             |
| `compose-smoke`           | `docker compose up --build` → `/actuator/health` UP → `/v3/api-docs` contract check → `docker compose down` |

None of the scanners is wired into `check`: `./gradlew check` stays the fast local gate. Run them
explicitly when you touch dependencies or anything that could carry a credential:

```bash
# dependency-vulnerability scan — runs inside backend/ because that is where Gradle lives
cd backend
./gradlew dependencyCheckAnalyze --no-parallel --no-configuration-cache

# secret scans — from the repository root, so `.gitleaks.toml` is the config in force
cd ..
gitleaks git . --config .gitleaks.toml --log-opts="--all --full-history --diff-filter=tuxdb -m"   # history, merge diffs included
gitleaks dir . --config .gitleaks.toml    # working tree
```

The vulnerability scan downloads the NVD feed, and Dependency-Check cannot refresh that feed without a
valid `NVD_API_KEY` (free from <https://nvd.nist.gov/developers/request-an-api-key>). Two layers behave
differently:

- **CI policy:** the `dependencies` job fails before Gradle runs when the `NVD_API_KEY` repository secret is
  missing. That guard checks presence, not validity.
- **Scanner behaviour:** Dependency-Check fails when it actually requests NVD data with a missing or invalid
  key. It skips the update while its local NVD data is younger than `nvd.api.check.validforhours` (default
  4 hours), so a recently refreshed cache can let a local run proceed without exercising a bad key — a clean
  local run is not proof that the key works.

Export the key for local scans and store it as the repository secret of the same name in CI.
Fork-originated PRs receive no repository secrets, so the `dependencies` job is skipped for them
and `dependencies-fork-notice` reports that explicitly; run the scan (or land the work through a
same-repository branch) before merging such a PR. Suppressions live in
`backend/dependency-check-suppressions.xml`; every entry needs a written reason, and
`failBuildOnUnusedSuppressionRule` fails the scan once a suppression stops matching anything.

### Calling the API

Every endpoint except health, OpenAPI and the three token endpoints (`POST /api/v1/auth/login`,
`/refresh`, `/logout`) requires a bearer JWT (HS256). Obtain one from `POST /api/v1/auth/login` with a
seeded user's credentials. The access token is valid for 15 minutes
and carries a canonical UUID `sub` (the id of an **active** `app_user`, recorded as `created_by` on
writes), an `exp`, and a `roles` string array (for example `["ADMIN_OPERASIONAL"]`). A non-UUID,
`SYSTEM`, unknown or inactive subject, or a missing `exp`, is rejected with 401; a token without an
allowed role is 403. Login returns an access + refresh token pair in the body; rotate the refresh
token with `POST /api/v1/auth/refresh` (single-use — the presented token stops working) and revoke it
with `POST /api/v1/auth/logout` (idempotent). Five consecutive failed logins lock the account for
15 minutes and answer 423 `ACCOUNT_LOCKED`.

| Method | Endpoint                              | Purpose                                                            |
| ------ | ------------------------------------- | ------------------------------------------------------------------ |
| `POST` | `/api/v1/auth/login`                  | Exchange username/password for access + refresh tokens             |
| `POST` | `/api/v1/auth/refresh`                | Rotate a refresh token into a new access + refresh pair            |
| `POST` | `/api/v1/auth/logout`                 | Revoke a refresh token (idempotent)                                |
| `POST` | `/api/v1/contracts`                   | Create a DRAFT contract (`Idempotency-Key` required)               |
| `POST` | `/api/v1/contracts/{id}/activate`     | Activate and generate the schedule (idempotent)                    |
| `GET`  | `/api/v1/contracts`                   | List contracts (paged, filter by status, search)                   |
| `GET`  | `/api/v1/contracts/{id}`              | Contract detail with outstanding balance                           |
| `GET`  | `/api/v1/contracts/{id}/installments` | Installment schedule                                               |
| `GET`  | `/api/v1/contracts/{id}/statement`    | Ledger statement for the contract                                  |
| `GET`  | `/api/v1/contracts/{id}/payments`     | Payment history with allocations and void details                  |
| `POST` | `/api/v1/payments`                    | Receive, allocate, and post a payment (`Idempotency-Key` required) |
| `POST` | `/api/v1/payments/{id}/void`          | Void a posted payment by reversal (`Idempotency-Key` required)     |
| `POST` | `/api/v1/contracts/{id}/credit/apply` | Apply available customer credit (`Idempotency-Key` required)      |
| `GET`  | `/api/v1/contracts/{id}/credit`       | Customer credit balance and application history                    |
| `POST` | `/api/v1/penalty-adjustments`         | Waive or reduce accrued penalty                                    |
| `POST` | `/api/v1/settlements/quote`           | Price an early-settlement quote (immutable, TTL)                   |
| `POST` | `/api/v1/settlements`                 | Execute a quoted settlement (`Idempotency-Key` required)           |
| `POST` | `/api/v1/contracts/{id}/write-off`    | Write off an active contract (`Idempotency-Key` required)          |
| `GET`  | `/api/v1/reports/aging`               | Portfolio and per-contract aging buckets                           |
| `GET`  | `/api/v1/reports/outstanding`         | ADR-026 metrics: outstanding, AR, NPL, collection rate             |

Every mutation above is restricted to `ADMIN_OPERASIONAL`; read endpoints follow the role matrix
(see Security below). Login, refresh and logout are open to all three business roles and are the only
endpoints reachable without a token besides health and OpenAPI — refresh/logout take the refresh token
itself as their credential.

## Security

- **Default-deny with a role matrix.** Only health, info, OpenAPI, and the three token endpoints are
  public. Every endpoint
  enforces the role matrix from one matcher table ending in `denyAll`; roles come from the JWT `roles`
  claim. → [ADR-005](docs/adr/ADR-005-resource-server-before-auth-stories.md),
  [ADR-015](docs/adr/ADR-015-jwt-roles-claim-and-endpoint-authorization.md)
- **Real users and Argon2id login.** `POST /api/v1/auth/login` verifies an Argon2id hash and issues a
  15-minute HS256 access token plus a refresh token whose SHA-256 hash is stored (`refresh_token`).
  Credential failures are a generic 401; a saturated Argon2 verification semaphore returns a retryable
  503 `AUTHENTICATION_CAPACITY_EXCEEDED`. Refresh tokens rotate on use and a replay is rejected with
  401; five failed logins lock an account for 15 minutes and answer 423 `ACCOUNT_LOCKED`.
  → [ADR-022](docs/adr/ADR-022-auth-foundation.md)
- **Attributable writes.** A UUID JWT subject is bound to the audit context and recorded as
  `created_by`/`updated_by`. A token whose subject is not a UUID, is unknown or belongs to an inactive
  user, or that carries no `exp`, is rejected with 401 (ADR-015, ADR-022). Scheduled jobs run as a
  seeded `SYSTEM` principal that can never log in (inactive, unusable password hash, enforced by a
  check constraint).
- **Fail-fast secrets.** The app refuses to start without its PII encryption key, HMAC key, and
  JWT secret, and refuses to start a `prod`/`production` deployment that still uses the public
  `docker-compose.yml` dev values:

  ```
  SERFIRA_SECURITY_PII_ENCRYPTION_KEY=<base64 32-byte key>
  SERFIRA_SECURITY_PII_HMAC_KEY=<base64 >=32-byte key>
  SERFIRA_SECURITY_JWT_SECRET_BASE64=<base64 >=32-byte key>
  ```

  The PII pair is entry `k1` of a key ring ([ADR-025](docs/adr/ADR-025-pii-key-ring.md)). A rotation
  adds keys and then switches the active one:

  ```
  SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY=<base64 32-byte key>
  SERFIRA_SECURITY_PII_KEYS_<KID>_HMACKEY=<base64 >=32-byte key>
  SERFIRA_SECURITY_PII_ACTIVEKID=<kid>     # default k1 while the flat pair is set; otherwise required
  SERFIRA_SECURITY_PII_LEGACYKID=<kid>     # reads pre-ring v1 rows; default k1 while the flat pair is
                                           # set, otherwise none: set it if any v1 rows remain
  SERFIRA_SECURITY_PII_WRITEENVELOPE=v2    # default v1; see the upgrade note below
  ```

  `<KID>` is 1–16 lowercase letters or digits (uppercase in the variable name). **Do not switch the
  active key while a T28a instance still runs**: it records no key id with its idempotency claims, so a
  replay across the switch would get `409`. Switch only when every instance runs T28b or later.

  **Upgrading to T28a is two-phase** ([ADR-025 D7](docs/adr/ADR-025-pii-key-ring.md)). Deploy it as is:
  it reads both envelope formats but still writes `v1`, so an older instance running alongside it, or a
  rollback, still reads every row. Set `SERFIRA_SECURITY_PII_WRITEENVELOPE=v2` only after no pre-T28a
  instance runs and you no longer need to roll back below T28a. After that, a rollback below T28a cannot
  read the `v2` rows. Key rotation requires `v2`.

  **Rotating the PII keys** ([ADR-025 D6, D8–D10](docs/adr/ADR-025-pii-key-ring.md)). With
  `SERFIRA_SECURITY_PII_WRITEENVELOPE=v2` set and no T28a instance running:

  1. Add the new key (`SERFIRA_SECURITY_PII_KEYS_<KID>_*`) on every instance and deploy, still on the old
     `SERFIRA_SECURITY_PII_ACTIVEKID`.
  2. Set `SERFIRA_SECURITY_PII_ACTIVEKID` to the new `<kid>` on every instance together and deploy. Idempotent
     replays keep working across the switch, because each claim records the key it was signed with.
  3. Run the re-keying pass. It is off unless enabled, and runs once at startup:

     ```
     SERFIRA_MAINTENANCE_PIIREKEY_ENABLED=true
     SERFIRA_MAINTENANCE_PIIREKEY_MODE=DRY_RUN     # DRY_RUN (default) or EXECUTE
     SERFIRA_MAINTENANCE_PIIREKEY_BATCHSIZE=500    # page size, default 500
     SERFIRA_MAINTENANCE_PIIREKEY_LOCKATMOSTFOR=PT10M  # ShedLock bound for the pass, default PT10M
     ```

     Restart once with `DRY_RUN` and read the report. Then restart with `MODE=EXECUTE`. It moves every
     customer to the active key, verifies each row, and can be rerun after an abort. Then remove
     `SERFIRA_MAINTENANCE_PIIREKEY_ENABLED` (or set it to anything but `true`) so later restarts do not run it.
     The application serves requests during the pass. `EXECUTE` refuses to run with
     `SERFIRA_SECURITY_PII_WRITEENVELOPE=v1`. It aborts with zero writes on an unreadable row or a
     customer that would duplicate another under the active key
     (`pii-rekey pre-flight aborted with zero writes: …`). Every run, `DRY_RUN` included, first logs one
     `pii-rekey census scannedRows=… rowsToRekey=… unknownKidRows=… undecryptableRows=… duplicateNikRows=…
     duplicatePhoneRows=… executeWouldRun=…` line; `executeWouldRun=false` means `EXECUTE` would refuse or abort,
     so fix that first. With several instances, only the one that wins the `pii-rekey` lock runs the pass and logs
     these lines; the others log nothing for it.
  4. Read the report in the application log: one `pii-rekey key kid=<kid> verdict=… envelopeRows=…
     unexpiredFingerprints=…` line per key, then `pii-rekey summary activeKid=… rowsOffActiveHashes=…
     unreadableRows=… unlabelledFingerprints=…`. Retire the old key only once its verdict is `RETIRABLE`. A key
     stays `NOT_RETIRABLE` while any customer row or any unexpired idempotency claim still uses it, so an old key
     usually becomes retirable only after the idempotency retention window (7 days) has passed since the switch.
     Without a legacy key, any unlabelled claim (`unlabelledFingerprints` above 0) keeps every non-active key
     `NOT_RETIRABLE`. To re-read the verdict after step 3 removed the runner, set
     `SERFIRA_MAINTENANCE_PIIREKEY_ENABLED=true` and `SERFIRA_MAINTENANCE_PIIREKEY_MODE=DRY_RUN`, restart, read
     the lines, then remove `SERFIRA_MAINTENANCE_PIIREKEY_ENABLED` again. `MISSING_FROM_RING` means a key was
     removed too early. Logs contain counts and key ids only.
  5. Retire the old key (here `k1`, the flat pair) in one deploy on every instance. Unset (or blank)
     `SERFIRA_SECURITY_PII_ENCRYPTION_KEY` and `SERFIRA_SECURITY_PII_HMAC_KEY`; with no flat pair, the ring is
     just the `SERFIRA_SECURITY_PII_KEYS_<KID>_*` entries. In Compose these two variables carry a dev default
     (`${…:-…}`), so an empty value is replaced by it: delete the two lines instead. If you set
     `SERFIRA_SECURITY_PII_LEGACYKID=k1` explicitly, unset it in the same deploy, or startup refuses because
     the legacy kid is not in the ring (with no flat pair the legacy kid defaults to none). Keep
     `SERFIRA_SECURITY_PII_ACTIVEKID` (now required) and `SERFIRA_SECURITY_PII_WRITEENVELOPE=v2` set. Do not roll
     back below T28b after the active-key switch: a T28a build compares replays under the active key only, so a
     retry of a claim recorded under the old key would get `409`.

- **No plaintext PII in SQL, logs, or URLs.** API responses return customer data masked.
- **Browser boundary (CORS).** A browser client on another origin must be allowlisted explicitly:
  `SERFIRA_SECURITY_CORS_ALLOWED_ORIGINS` takes comma-separated exact origins (lowercase, no trailing
  slash, no default port, browser-canonical IPv6 such as `[::1]`); empty means no origin is allowed.
  Compose applies its `http://localhost:3000` default only when the variable is unset, so an explicitly
  empty value still denies every browser origin. `./gradlew bootRun` outside Compose needs the variable
  set to serve a browser too.
  Credentials are never allowed (tokens live in memory, never a cookie), and a disallowed origin is
  rejected before authorization. CORS is not authorization: the role matrix still decides.
  → [ADR-023](docs/adr/ADR-023-browser-client-boundary.md)

## Status and roadmap

| Phase        | Scope                                                                                                                      | Status                                 |
| ------------ | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| 1 — MVP      | Contracts, FLAT/EFFECTIVE schedules, payments and allocation, ledger, penalty and aging job, RBAC, aging report, statement | Done                                   |
| 2            | Early settlement, customer credit, payment void/reversal, penalty waiver, consistency check, write-off, login              | Built                                  |
| 3            | Next.js dashboard (login, contract lifecycle, payment, payment history, statement, credit, early settlement, write-off, aging and outstanding reports), demo script, reporting metrics (ADR-026), outbox events and log-based publisher (ADR-027) | Built; CSV export deferred (PRD P2)    |
| 4 (optional) | Restructuring, payment gateway stub, maker-checker, microservice split demo                                                | Optional                               |

Out of scope by design: loan origination (credit scoring, credit bureau checks, e-KYC), funding and
treasury, field collection, real payment gateway integration, and multi-currency.

Payment void and penalty waive/reduce are API-only for now; screens for them are the first proposed next
story. The proposed next stories and the deferred work are in [docs/tasks.md](docs/tasks.md), and the
sprint-by-sprint log is in [docs/PROGRESS.md](docs/PROGRESS.md).

## Documentation

The specifications are the source of truth and are written in Bahasa Indonesia.

| Document                                                       | Contents                                                     |
| -------------------------------------------------------------- | ------------------------------------------------------------ |
| [PRD](docs/01_PRD.md)                                          | Business rules, requirements, acceptance scenarios           |
| [Tech Spec](docs/02_TECH_SPEC.md)                              | Architecture, dependency rules, ledger design, posting rules |
| [Domain Model](docs/03_DOMAIN_MODEL.md)                        | Entities, states, invariants                                 |
| [Gaps Addendum](docs/04_GAPS_ADDENDUM.md)                      | Resolved gaps: auth, audit, configuration                    |
| [Sprint Plan](docs/05_SPRINT_PLAN.md) · [Tasks](docs/tasks.md) | Delivery plan and backlog                                    |
| [Frontend Spec](docs/06_FRONTEND_SPEC.md)                      | Next.js dashboard structure, screens, conventions            |
| [ADRs](docs/adr/)                                              | Architecture decisions                                       |

## Repository layout

```
backend/    Spring Boot application (modules above), Flyway migrations, tests
docs/       Specifications, ADRs, progress log
frontend/   Next.js 16 dashboard (see frontend/README.md)
scripts/    End-to-end demo script (node scripts/demo.mjs) and its helpers
```
