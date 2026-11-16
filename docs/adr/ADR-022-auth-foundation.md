# ADR-022: Auth Foundation — App Users, Login, and JWT Identity Binding (T21a, addendum T21b)

- **Status:** Accepted
- **Date:** 2026-11-15 (addendum 2026-11-16)
- **Deciders:** Hans (solo developer)
- **Amends:** ADR-015 decision D2 (the "no per-request `app_user` lookup" rule)
- **Relates to:** ADR-005 (HS256 resource server), ADR-004 (key posture), ADR-016
  (input-bounds posture), ADR-015 (roles claim and endpoint matrix), `04_GAPS_ADDENDUM.md`
  §3.1–§3.4 and §18.3–§18.4. D1–D9 are T21a; the addendum adds D10–D13 for T21b (rotation,
  logout, lockout, dev-key guard).

---

## Context

Sprint 6b begins with T21a: the first half of story F2. Until now tokens were minted outside the
application (tests and a manually signed dev token), so `created_by`/`updated_by` carried a raw
`sub` claim that only the `created_by → app_user(id)` foreign key validated. ADR-015 D2 explicitly
trusted the signed claim and did no per-request user lookup; it deferred immediate revocation to
T21.

T21a has to provide the identity source the existing role matrix authorizes against:

- real users in `app_user` with Argon2id password hashes;
- `POST /api/v1/auth/login` that mints the first token;
- a token whose `sub` is an **active** `app_user.id`, closing the T7-D2 deferred active-user check;
- a matrix row for the new endpoint, preserving default-deny.

The schema already has `app_user` and `refresh_token` (V1, Addendum §3.1), so no schema migration
is needed. Only the demo login users are missing: V1 seeds the non-interactive `SYSTEM` principal,
and the demo users are seeded by the profile-gated repeatable migration `R__demo_seed.sql`
(Addendum §18.4).

---

## Decision

### D1 — New `auth` module; existing tables

A new module `com.serfira.auth` follows the standard `{api, application, domain, infrastructure}`
layout. It owns `AppUser`, `RefreshToken`, their repositories, `AuthService`, `AuthController` and
`AuthConfiguration`. No new table, column or constraint is introduced. `shared/security` keeps the
filter chain and reads `app_user` through SQL rather than depending on `auth`, so the module
dependency direction stays one-way.

### D2 — Argon2id only, via Spring Security and BouncyCastle

Passwords are verified with Spring Security's `Argon2PasswordEncoder` using the
`defaultsForSpringSecurity_v5_8()` parameters: memory 16 384 KiB, iterations 2, parallelism 1,
16-byte salt, 32-byte hash. The encoded value is 97 characters, within `VARCHAR(100)`.

`org.bouncycastle:bcprov-jdk18on:1.80` is added explicitly because the encoder delegates the Argon2
KDF to BouncyCastle's lightweight API. Only `bcprov` is needed; `bcpkix`/`bcutil` are certificate
helpers and are not added. The version is pinned so the dependency is auditable (T27 will scan it).
The seeded `SYSTEM` row keeps V8's non-Argon2 `{disabled}` value, so it can never authenticate.

The stored value must start with the exact prefix **`$argon2id$`**; an encoding carrying a bare
`$argon2$` prefix (or the Argon2i/Argon2d variants) is rejected before verification, so a legacy or
weaker Argon2 encoding can never authenticate. This was tightened in the PR #19 review fixes
(`070e7ad`).

### D3 — Login contract and failure semantics

`POST /api/v1/auth/login` accepts `{username, password}` and returns
`{access_token, refresh_token}` in the standard envelope (SNAKE_CASE wire names, ADR-006).

- Success is **200**. Every failure — unknown username, wrong password, inactive account,
  non-interactive role — is **401 `UNAUTHORIZED`** with the same `"Bad credentials"` message.
  This avoids account/username enumeration.
- An unknown username still pays one Argon2 verification against a startup-generated dummy hash,
  so the timing of the unknown-user path is comparable to a known-user failure.
- `@NotBlank` plus `@Size(max = 60)` on the username (the column width) and
  `@Size(max = 200)` on the password bound the input before the KDF (ADR-016 posture).
- On success `failed_login_attempts` is reset to 0, `last_login_at`, `updated_at` and `updated_by`
  are set, and the role is read from `app_user.role` — never from the request.
- Plaintext passwords and password/token hashes are never logged or returned.

### D4 — Access token: HS256, 15 minutes, `sub` = `app_user.id`

The access token reuses ADR-005's HS256 decoder and `serfira.security.jwt.secret-base64` key. It
carries `sub` (the `app_user.id` UUID), `roles` (a one-element string array, ADR-015 D1), `iat` and
`exp` (15 minutes). `iss`/`aud` validation and RS256 remain deferred (ADR-005) because no external
issuer exists. Tokens are minted in-process with Nimbus, the same library the decoder uses.

### D5 — Refresh token: opaque, hashed at rest, 7 days

T21a issues an opaque 128-bit random refresh token (UUID text). Only its SHA-256 hex digest is stored
in `refresh_token.token_hash` with a 7-day `expires_at`; `created_by`/`updated_by` are the
authenticating user. The raw value is returned once and never persisted or logged. T21b owns
rotation, `revoked_at`, `replaced_by_id`, replay detection and logout; T21a only defines the row.

SHA-256 (not Argon2) is appropriate here because the token is a full-entropy random value, not a
low-entropy human password; T21b compares the digest when the token is presented.

### D6 — Active-user check at the decoder (closes T7-D2)

The JWT decoder's validator list now includes an active-user check after the existing `exp` and
canonical-UUID rules: a token is accepted only if `sub` identifies an `app_user` with
`is_active = TRUE`. Unknown, inactive, missing or non-UUID subjects are **401** and never become an
`Authentication`. `SYSTEM` remains non-http: the canonical id is rejected by ADR-015 D5 and the row
is inactive (V8).

This deliberately **amends ADR-015 D2**. That decision avoided a per-request lookup and accepted
role/deactivation staleness until token expiry. The trade-off is now one indexed primary-key lookup
(`SELECT EXISTS …`) per authenticated request, in exchange for immediate deactivation enforcement.
Role changes still take effect only on the next token (≤ 15 minutes), because authorities continue to
come from the signed `roles` claim.

### D7 — Matrix row and attribution

`POST /api/v1/auth/login` has an explicit row in the single matcher table, before `denyAll`, and is
`permitAll`: it mints the first token, so it cannot require one, and all three business roles may use
it (Addendum §3.4). Refresh/logout rows arrive with T21b.

`AuditActorBindingFilter` is unchanged: it binds the validated `sub` to `AuditContext`, so
`created_by`/`updated_by` on a post-login write are the real `app_user.id`. Identity is never taken
from a request body.

### D8 — Demo seed is profile-gated and repeatable

`R__demo_seed.sql` is a **repeatable** Flyway migration (`R__` prefix, no version) that lives in
`db/demo`, not `db/migration`. `application-demo.properties` adds `classpath:db/demo` to
`spring.flyway.locations`, so only the `demo` profile applies it. It seeds `admin`/`finance`/`manager`
(Addendum §18.3) with Argon2id hashes and fixed ids `…0010`/`…0011`/`…0012`. `docker-compose.yml`
defaults `SPRING_PROFILES_ACTIVE` to `demo` (honouring an explicitly empty value via
`${SPRING_PROFILES_ACTIVE-demo}`) so the documented `docker compose up` demo path has login users;
the default and production profiles never see the file. Demo credentials remain demo-only and are
documented in Addendum §18.3, not in the SQL file.

Repeatable rather than versioned (it was drafted as `V99`): the seed is demo data, not schema, so it
must re-apply when its checksum changes without consuming a schema version slot. Re-application is
safe because `INSERT … ON CONFLICT (username) DO NOTHING` skips existing rows, but that same guard
means **editing a seeded password does not update an existing demo row** — a database that already
has the demo users needs a manual `UPDATE` or a reset to pick up a changed hash.

### D9 — Argon2 verification capacity guard

Password verification is CPU- and memory-expensive (each Argon2id check allocates 16 MiB), so an
unauthenticated caller could otherwise exhaust the app by posting many parallel logins. `AuthService`
now wraps `passwordEncoder.matches` in a `Semaphore` sized by
`serfira.auth.argon2.max-concurrent-verifications` (default **2**, validated `>= 1`). A caller that
cannot acquire a permit **fails fast** with `AuthenticationCapacityExceededException` →
**503 `AUTHENTICATION_CAPACITY_EXCEEDED`** rather than queueing unbounded KDF work.

This is deliberate load shedding, not a business failure: it bounds worst-case memory to
`permits × 16 MiB` per instance. The trade-off is that legitimate logins beyond the permit count get
a retryable 503 instead of waiting; sized for the portfolio's single-instance deployment, the
property can be raised when the runtime has more headroom. It was added in the PR #19 review fixes
(`070e7ad`) and is orthogonal to the T21b brute-force lockout, which counts failures per account.

---

## Alternatives Considered

- **Store the refresh token in plaintext.** Rejected: a database leak would hand over live
  sessions. Hashing is cheap and the token is not needed for lookup by value.
- **Argon2 the refresh token.** Rejected: the token already has full 128-bit entropy; an expensive
  KDF adds latency without meaningfully raising the guessing cost.
- **Keep ADR-015 D2 (no user lookup) and rely on token expiry.** Rejected for T21a: the acceptance
  criterion requires an unknown/inactive `sub` to be 401, and immediate deactivation is the point of
  closing T7-D2.
- **Move the active-user validator into `auth` and inject it into `shared/security`.** Rejected:
  it would make `shared` depend on `auth`, inverting the module direction. The validator needs only
  `app_user`, so a small SQL check in `shared/security` is the cleaner seam.
- **Use a `DelegatingPasswordEncoder` with a `{noop}` fallback.** Rejected: it would make V8's
  `{disabled}` sentinel and any legacy hash usable. Argon2-only is the documented rule.
- **Put the demo seed on the default migration path and guard with SQL.** Rejected: Flyway would
  apply it to every environment, and the Addendum explicitly requires a separate, profile-gated
  location.

---

## Consequences

- Login and active-user binding are live; `created_by`/`updated_by` now come from a validated,
  active `app_user`. Login failures are a generic 401, except when the Argon2 capacity guard is
  saturated, which returns a retryable 503 `AUTHENTICATION_CAPACITY_EXCEEDED` (D9).
- Every authenticated request adds one `app_user` primary-key lookup. The table is tiny and the
  lookup is indexed; this is acceptable for the sprint's scale and can be cached later if needed.
- Deactivating a user takes effect on the next request. Changing a user's role takes effect only on
  the next issued token (≤ 15 minutes) because authorities remain claim-based (ADR-015 D2).
- `EndpointRoleMatrixIT` gains the `/auth/login` row; `ErrorDispatchSecurityIT` now seeds an active
  actor because its bearer token must authenticate. `AuthLoginIT` covers the seven T21a cases.
- T21b must build rotation/logout/lockout on the `RefreshToken` entity and repository introduced
  here, and add the CR-16 production dev-key guard. The lockout threshold/window remains the one open
  auth decision (task proposes 5 attempts / 15 minutes), to be fixed in the T21b ADR note.
- The demo profile and the default profile have different Flyway locations. A database created with
  `demo` must not be switched to `prod` without a reset: `R__demo_seed.sql` would then no longer be
  resolved (it lives only in `db/demo`), and demo users would remain in the database.

---

## Addendum — T21b: Rotation, Logout, Lockout, Dev-Key Guard (2026-11-16)

T21b implements the stateful half of F2. It adds no schema (every column it needs —
`refresh_token.revoked_at`/`replaced_by_id`, `app_user.failed_login_attempts`/`locked_until` — is in
V1) and no module dependency: rotation and logout live in the existing `auth` application service,
and the guard lives in `shared/security` because it reads only configuration.

### D10 — Refresh-token transport is the response body (settles X-14)

`POST /auth/login` and `POST /auth/refresh` both return `{access_token, refresh_token}` in the
standard envelope; `POST /auth/refresh` and `POST /auth/logout` take `{refresh_token}` in the body.
No cookie is set, so there is no CSRF token, no `SameSite` policy and no credentialed CORS on the
filter chain this phase.

The alternative — an `HttpOnly` refresh cookie — is better against XSS (JavaScript cannot read the
token) but only pays off with a browser client, which does not exist yet (`frontend/` is still
empty). Adopting it now would add CSRF handling and credentialed CORS to the filter chain for a
client that cannot use them, and it would still not protect against XSS in a future frontend that
holds the access token in memory anyway. The frontend spec is updated to store both tokens in memory
and to keep a single in-flight refresh promise, because single-use rotation makes a second parallel
refresh fail; revisit the cookie at G2 with a real browser client, when CSRF can be designed once.

`02_TECH_SPEC.md` §2.6 and `06_FRONTEND_SPEC.md` §1.1/§1.4 are aligned to the body transport, so
the documents no longer contradict the implementation.

### D11 — Single-use rotation, race-safe, reject-only on replay

A refresh exchanges the presented token for a new pair and retires the old row: `revoked_at` is set
and `replaced_by_id` points at the replacement. The replacement row is inserted first (its id is
needed for the link) and the presented row is then retired with a **conditional** update
(`UPDATE … WHERE id = ? AND revoked_at IS NULL`). Exactly one of two concurrent refreshes can affect
that row; the loser sees 0 rows and treats the token as replay, and its transaction — including the
replacement it had already inserted — rolls back. A read-modify-write on the entity would let both
callers mint a pair, so the transition is deliberately not an entity setter.

Every unusable token — unknown, expired, already rotated, logged out, or owned by an `app_user` that
is now inactive or non-interactive — answers the same **401 `UNAUTHORIZED`** and mints nothing. The
user is re-read on refresh rather than trusted from the token row, so deactivating an account also
stops its refresh loop, not just its next access token.

A replayed token is **rejected only**; the token family is not revoked. Cascade revocation would
punish the legitimate client whenever a token is replayed once (a double-clicked refresh, a
restored tab), and it does not help the actual attack it is meant for — a stolen token — because
the attacker's and the victim's tokens are indistinguishable at that point. The cost is accepted:
a stolen refresh token stays usable until it is rotated or logs out, bounded by the 7-day sliding
expiry. Revisit with the frontend, where a single in-flight refresh promise removes the benign
double-submit case.

The 7-day expiry is **sliding**: each rotation issues a fresh 7-day window and there is no absolute
session cap. That suits an operational back-office app that is used daily; a cap can be added later
without a schema change if the deployment needs one.

Logout revokes the presented token and is **idempotent**: unknown, expired and already-revoked
tokens are all 200 with `data: null`. An error would (a) let a caller probe whether a token exists
and (b) break the client that retries after a dropped response. Logout retires only the presented
token, so closing one device does not log out another.

### D12 — Brute-force lockout: 5 failures / 15 minutes, 423 `ACCOUNT_LOCKED`

A failed password increments `app_user.failed_login_attempts`; reaching
`serfira.auth.lockout.max-attempts` (default **5**) sets `locked_until = failure time +
serfira.auth.lockout.window` (default **`PT15M`**). A successful login clears both the counter and
the lock. The window is measured from the failure that crosses the threshold, so a slow trickle of
attempts cannot keep an account locked indefinitely; a lock that has elapsed needs no cleanup
because it is inert. Both numbers are configuration so a deployment can tune them; the defaults are
the task's proposal.

A locked account answers **423 `LOCKED` `ACCOUNT_LOCKED`** with the whole minutes remaining, before
any password comparison — so it costs no Argon2 verification and the answer is identical whether or
not the supplied password was correct. This is a deliberate exception to D3's uniform 401: the
frontend needs to tell the user "try again in X minutes" rather than "bad credentials". It does
disclose that a username exists, which D3 otherwise avoids; that is accepted because usernames are
assigned by an administrator rather than held secret, the probing rate is bounded by the D9 Argon2
semaphore, and the alternative (a generic 401) generates support tickets from users who think their
password is wrong.

**Failure bookkeeping crosses a transaction boundary.** `AuthService.login` is `@Transactional` and
throws on failure, so an increment inside it would be rolled back and lockout would never fire — with
a green test suite unless the test asserts the *persisted* counter. `LoginAttemptRecorder` is a
separate `@Service` with `@Transactional(propagation = REQUIRES_NEW)`; the separate bean guarantees
the call passes through a proxy, so the propagation actually applies. `AuthLockoutIT` asserts the
counter in the database after each 401, which is the regression test for this trap.

### D13 — Refuse to start production on the docker-compose dev keys (CR-16)

`docker-compose.yml` ships fixed, public key material so local development needs no setup. Those
values are in the repository, so anyone who has read the file can mint a valid JWT or decrypt PII at
rest; a deployment that kept them would be silently and totally compromised.
`DevKeyProductionGuardConfiguration` fails startup when a `prod` or `production` profile is active
and any of `serfira.security.jwt.secret-base64`, `serfira.security.pii.encryption-key` or
`serfira.security.pii.hmac-key` still equals its compose default.

Details that matter:

- The message names the offending **property and environment variable**, never the key value — a
  startup log is exactly where a leaked secret survives.
- It only trips for production profiles. Dev, test and demo keep working out of the box, and the
  test properties may keep the public PII keys.
- Missing or blank keys are not this guard's business; `JwtDecoder`, `PiiSecurityConfiguration` and
  `AuthService` already fail fast with a clearer message.
- It does not attempt entropy or strength validation: length is enforced where the keys are bound,
  and comparing against the known-public values is the one check that catches the real mistake.
- `production` is accepted alongside `prod` so a deployment cannot slip through on a spelling
  difference.

---

## Addendum Consequences

- Refresh rotation, logout, lockout and the dev-key guard are live; `EndpointRoleMatrixIT` gains the
  `/auth/refresh` and `/auth/logout` rows (as `permitAll` cells, "not 401/403" for all three roles),
  and `AuthRefreshIT`, `AuthLogoutIT`, `AuthLockoutIT` and `DevKeyProductionGuardTest` cover the
  scenarios above.
- Rotation inserts a row per refresh and revoked rows are never deleted (~96 rows per user per day
  at a 15-minute access token). Retention is a deferred task in `docs/tasks.md`, not a leak fix.
- A locked account needs a manual reset if the lock was caused by mistake (or in a demo database
  with the seeded users): clear `locked_until` / `failed_login_attempts`.
- The lockout threshold and the transport decision are recorded here, closing the last open T21b
  items and ambiguity X-14. `AppUser` moved to project entity style in the same task, closing A-14.
