# ADR-005: Resource-Server Security Before the Sprint 2 Write Paths
- **Status:** Accepted
- **Date:** 2026-09-15
- **Deciders:** Hans (solo developer)

---

## Context

Sprint 2 (B5 contract API, C1/C2/C3 ledger and payment stories) introduces the first HTTP
write paths of the system. As of the pre-Sprint 2 review (CRIT-1) no `SecurityFilterChain`
exists, so the first controller would either be unreachable (Spring Security default
auto-config with an unknown random password) or, once a permissive filter is added,
reachable by anyone — while every audit column would record the seeded `SYSTEM` user as the
actor. `docs/05_SPRINT_PLAN.md` defers the full auth story (login/refresh/logout,
brute-force lockout, RBAC role matrix) to Sprint 6b (F2/F3), but the review established
that **attribution and default-deny enforcement are prerequisites of Sprint 2**, not
features of Sprint 6b.

Addendum §3.3 fixes the non-negotiables: access tokens are short-lived JWTs,
`created_by`/`updated_by` of every user-facing write come from the JWT principal's
`app_user.id`, jobs run under the seeded non-interactive `SYSTEM` principal, and RBAC is
default-deny.

---

## Decision

1. **Wire the JWT resource server now** (`ResourceServerSecurityConfiguration`):
   default-deny (`anyRequest().authenticated()`), stateless sessions, CSRF disabled (no
   ambient browser credential; authentication is per-request via the `Authorization`
   header). Public paths are deliberately minimal and read-only: actuator
   `health`/`info` and the OpenAPI/Swagger surface.
2. **HS256 with a symmetric signing key** (`serfira.security.jwt.secret-base64`, base64,
   ≥ 32 bytes, env `SERFIRA_SECURITY_JWT_SECRET_BASE64`). Missing/short key fails fast at
   startup, mirroring the PII key posture (ADR-004).
3. **`AuditActorBindingFilter`** (after `BearerTokenAuthenticationFilter`) binds the JWT
   `sub` claim to `AuditContext` for the request and restores SYSTEM afterwards, so
   `Auditable` `@PrePersist` fills `created_by` with the authenticated principal from the
   first financial write on. A `sub` that is not a UUID is never fabricated into an actor
   (fail-closed, stays SYSTEM).
4. **401/403 return the standard `{data, error}` envelope** through dedicated
   `AuthenticationEntryPoint`/`AccessDeniedHandler`, registered at both the
   `exceptionHandling` and the `oauth2ResourceServer` level (the resource-server configurer
   otherwise installs its own entry point for bearer requests). `ErrorCode.UNAUTHORIZED`
   added for this purpose.
5. **RBAC role matchers and login/refresh/logout remain Sprint 6b (F2/F3)**; until then
   authorization equals "authenticated".

---

## Alternatives Considered

- **Defer all security to Sprint 6b (original plan).** Rejected: the first money-moving
  endpoint would ship with either no enforcement or a fabricated audit actor — both
  unfixable after the fact (immutable posted rows, non-attributable history).
- **HTTP Basic / form login for Sprint 2.** Rejected: not the documented architecture
  (TECH SPEC §2.6 JWT), invites the temporary-permissive-chain failure mode.
- **Full F2 login story now.** Rejected: login, refresh rotation, lockout, and the RBAC
  matrix are sized stories in their own right; the resource-server half of the JWT story
  is small and unblocks all Sprint 2 DoD items.

---

## Consequences

- Every Sprint 2 controller is born behind default-deny with correct attribution; ITs
  mint HS256 tokens with the test key to exercise authenticated writes.
- Tests and dev (`docker-compose`) use documented dev-only keys; production must inject
  its own `SERFIRA_SECURITY_JWT_SECRET_BASE64`. Symmetric HS256 means the same key signs
  and verifies — Sprint 6b (F2) may migrate to RS256 with issuer/audience validation when
  a real token issuer exists; the decoder is a single bean, isolated for that change.
- Swagger/OpenAPI is public by design; if that proves undesirable, the PUBLIC_PATHS list
  is the single place to tighten.
