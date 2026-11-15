# ADR-022: Auth Foundation — App Users, Login, and JWT Identity Binding (T21a)

- **Status:** Accepted
- **Date:** 2026-11-15
- **Deciders:** Hans (solo developer)
- **Amends:** ADR-015 decision D2 (the "no per-request `app_user` lookup" rule)
- **Relates to:** ADR-005 (HS256 resource server), ADR-004 (key posture), ADR-016
  (input-bounds posture), ADR-015 (roles claim and endpoint matrix), `04_GAPS_ADDENDUM.md`
  §3.1–§3.4 and §18.3–§18.4. T21b owns refresh rotation, logout, lockout and the dev-key guard.

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
