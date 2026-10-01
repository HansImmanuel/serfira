# ADR-015: JWT Roles Claim and Endpoint Authorization (T7)

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Hans (solo developer)
- **Amends:** ADR-005 decisions 3 and 5

---

## Context

ADR-005 wired the JWT resource server for Sprint 2 with authorization equal to "authenticated":
`anyRequest().authenticated()`, and the endpoint-to-role matrix (Addendum §3.4) deferred to the later auth
story. That left two gaps the 2026-09-30 external review confirmed:

- **No RBAC (CR-10).** Any valid token can create contracts and post payments, although PRD §2 already
  requires a read-only role for Phase 1 and Addendum §3.4 fixes the matrix.
- **A signed token whose `sub` is not a UUID is accepted and writes as `SYSTEM` (CR-01 / X-10).**
  `AuditActorBindingFilter` skipped actor binding but left the request authenticated, so the write path ran
  and the audit columns recorded the seeded job principal.

T7 closes both now, ahead of the full login/refresh/logout story (T21), which still has no token issuer: tokens
are minted externally (tests, dev). This ADR records the decisions; the matrix itself lives in one matcher
table in `ResourceServerSecurityConfiguration`.

Out of scope (T21): `iss`/`aud` validation, a per-request `app_user` lookup, login/refresh/logout, RS256, and
role-dependent PII unmasking.

---

## Decision

1. **D1 — Roles claim.** Authorities come from a `roles` claim, a JSON **array of strings**. T21 will issue
   `["<app_user.role>"]`. Values are case-sensitive and match `ck_app_user_role`. A non-array claim (absent, a
   string, an object) counts as "no roles". Unknown and wrongly cased elements are ignored. The default
   `SCOPE_`/`scp` converter is not used.
2. **D2 — Trust the signed claim.** No `app_user` lookup per request. A deactivated user or a changed role
   takes effect when the short-lived token expires (T21: 15 min, TS §2.6). The FK `created_by → app_user(id)`
   still rejects a write whose `sub` is not a real user.
3. **D3 — One matcher table.** The Addendum §3.4 matrix is a single URL-matcher table in the
   `SecurityFilterChain`, ending in `anyRequest().denyAll()`. It is the only place to audit against §3.4, and
   any endpoint nobody registered is denied by default. (`@PreAuthorize` per method was rejected: an
   unannotated method would be allowed.)
4. **D4 — `SYSTEM` denies the whole token.** A token whose `roles` contain `SYSTEM` gets no authorities at
   all, even alongside another role (so it is 403, not partially allowed). `SYSTEM` exists only for in-process
   jobs (Addendum §3.3, matrix column "—"); such a token should never have been minted.
5. **D5 — Identity rejected at the decoder.** A token with a missing or non-UUID `sub`, or with no `exp`, is
   **401**: the decoder's validators reject it, so it never becomes an `Authentication` (fixes CR-01 / X-10).
   Spring Security 7 still accepts a token without `exp` by default (`JwtTimestampValidator.allowEmptyExpiryClaim`
   defaults to `true`), so the expiry requirement is switched on explicitly. 401 (not 403) because a token that
   names no actor is an authentication failure.

### Enforced matrix (existing endpoints, Addendum §3.4)

| Method + path                             | ADMIN_OPERASIONAL | FINANCE | MANAJEMEN |
| ----------------------------------------- | ----------------- | ------- | --------- |
| `POST /api/v1/contracts`                  | allow             | 403     | 403       |
| `POST /api/v1/contracts/{id}/activate`    | allow             | 403     | 403       |
| `POST /api/v1/payments`                   | allow             | 403     | 403       |
| `GET /api/v1/contracts`                   | allow             | allow   | allow     |
| `GET /api/v1/contracts/{id}`              | allow             | allow   | allow     |
| `GET /api/v1/contracts/{id}/installments` | allow             | allow   | allow     |
| anything else (unlisted method or path)   | 403               | 403     | 403       |

**Status precedence.** No token or an invalid token (bad signature, `alg: none`, expired, no `exp`, missing or
non-UUID `sub`) → 401 `UNAUTHORIZED`. A valid token without a permitted role → 403 `FORBIDDEN`. An
unauthenticated request to an unlisted path is 401, not 403. A denied request writes nothing: no
`idempotency_keys` claim, no business row, no journal.

### Error-dispatch rule

Spring Security authorizes every servlet dispatch, not just the initial request. The matcher table therefore
permits `DispatcherType.ERROR`, so that a container error dispatch to `/error` (for example after a
`StrictHttpFirewall` rejection calls `sendError`) keeps its own status instead of being replaced by 401/403.
This does not make `/error` public: a client request for it is a `REQUEST` dispatch and still falls through to
`denyAll`. The container renders such errors with Boot's default JSON body, not the `{data, error}` envelope;
this is pre-existing and leaks no internals (deferred: an envelope-rendering `ErrorController`).

---

## Alternatives Considered

- **`role` as a single string claim** (mirrors the column). Rejected: every existing IT token would change and
  multi-role is closed off for no gain.
- **Per-request `app_user` lookup** (D2). Rejected for now: immediate revocation, but one query per request and
  a concern that belongs with T21's issuer. The short token lifetime bounds the staleness.
- **`@PreAuthorize` per controller method** (D3). Rejected: an unannotated method is allowed unless extra
  machinery is added; a single deny-by-default table is easier to audit against §3.4.
- **Ignore `SYSTEM` and keep the other roles** (D4). Rejected: a token that should never exist would keep
  working under its remaining role.
- **403 for a non-UUID or expiry-less token** (D5). Rejected: naming no valid actor is an authentication
  failure, so 401 is correct and the request must never reach authorization.

---

## Consequences

- The six existing endpoints enforce §3.4. From here on, every new endpoint ships with its matrix row and its
  403 tests (T8, T9, and all Sprint 5+ write paths).
- `denyAll` turns every unknown path into 403 for an authenticated caller (it was 404 before). This is
  intended; clients must not rely on 404 for unrouted paths.
- HS256 means anyone holding the signing key can mint any role, unchanged from ADR-005 and bounded by key
  custody. RS256 and a real issuer come with T21.
- A revoked user keeps access until the token expires (D2). Tokens are minted externally until T21, so dev/test
  tokens should use short expiries.
- `AuditActorBindingFilter` keeps a defensive fail-closed branch: a `JwtAuthenticationToken` with a non-UUID
  `sub`, if one ever reaches it, is stripped of its authentication (holder and the request-attribute copy an
  ERROR dispatch would restore), so authorization answers 401. The D5 validator makes the branch unreachable in
  normal operation, so its Javadoc is now true.
- `AuditedAssetTestController` (the test-only `/api/v1/__test-audit/assets` probe) is retired: under `denyAll`
  it would be 403, and it bypassed the real write path. Its attribution assertion moved onto
  `POST /api/v1/contracts`.
