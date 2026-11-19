# ADR-023: Browser Client Boundary — Direct API Calls, CORS, and In-Memory Tokens (T35)

- **Status:** Accepted
- **Date:** 2026-11-19
- **Deciders:** Hans (solo developer)
- **Relates to:** ADR-015 (JWT roles claim, default-deny matrix), ADR-022 D6/D7/D10 (active-user check,
  public token endpoints, refresh token in the body), ADR-005 (HS256 resource server),
  `06_FRONTEND_SPEC.md` §1.1/§1.4/§4, `02_TECH_SPEC.md` §2.6.

---

## Context

Sprint 7 adds the first browser client. Until now every caller was a server-to-server client that sends a
bearer token on every request; the API had no CORS configuration at all, and `06_FRONTEND_SPEC.md` left
"route handlers versus direct calls plus CORS" open. Two questions had to be settled before frontend code
exists:

1. **The path** — does the browser call the API directly, or through a Next.js BFF/proxy?
2. **The boundary** — what exactly does the browser get to do, and which controls back it?

The token model is already decided: 15-minute HS256 access token, opaque refresh token returned in the
body and stored hashed (ADR-022 D3/D10), no cookies. The role matrix (ADR-015 D3) is the only
authorization authority and ends in `denyAll()`.

---

## Decision

### D1 — Direct browser-to-API calls, no BFF or route handler

The frontend calls `NEXT_PUBLIC_API_BASE_URL` directly and CORS is configured on the backend. No
`app/api/` proxy, no server-side token store, no second error-translation layer. One auth boundary, one
`ApiResponse` envelope, and no intermediate that could silently diverge from the API contract.

### D2 — Tokens in memory, no cookies; CSRF stays off

Both tokens live in JavaScript memory for the tab's lifetime (FE §1.4). There is no cookie, so there is no
ambient credential: CSRF stays disabled (it was already justified on the same grounds) and CORS stays
credential-free. A reload forces a new login, which is documented behaviour rather than a defect. Moving
the refresh token into an `HttpOnly` cookie would introduce both CSRF and credentialed CORS and must be a
new ADR superseding this one, not an implementation detail.

### D3 — Roles come from an unverified JWT decode; no `/me` endpoint

The UI decodes the access token payload to read `roles` for navigation and to hide write actions. It does
not verify the signature and never treats the claim as authorization: a forged role only reveals buttons,
and every call still goes through the matcher table. `sub` is an `app_user` UUID, not a display name, so
the UI keeps the username entered at login in memory for its header label. There is no `GET /me`; adding
one would be a new endpoint with its own matrix row, not part of this phase.

### D4 — CORS is an exact-origin allowlist, default deny

| Setting | Value |
| --- | --- |
| Origins | `serfira.security.cors.allowed-origins`, comma-separated, **empty = none** |
| Methods | `GET`, `POST` (the API exposes no other method) |
| Allowed headers | `Authorization`, `Content-Type`, `Idempotency-Key`, `X-Request-Id` |
| Exposed headers | `X-Request-Id` |
| Credentials | `false` — no `Access-Control-Allow-Credentials` |
| Preflight cache | 1800 s |

Origins are compared literally, the way a browser sends them. `Content-Type: application/json` is not a
CORS-safelisted header, so it must be listed; `Accept` is safelisted and is not. The allowlist applies to
`/**`; every path outside the public infrastructure routes is still denied by the matrix, so a
path-scoped allowlist would add no security and one more place to forget.

### D5 — Fail-fast validation of the allowlist

`AllowedOrigins` parses the property once at startup and rejects: the wildcard `*`, blank entries,
missing or non-`http(s)` schemes, paths, queries, fragments, user info, out-of-range ports, explicit
default ports (`http://localhost:80`), and non-canonical case. A browser never sends any of those, and a
configured entry that can never match is indistinguishable from a CORS outage. `allowCredentials=false`
makes `*` legal in Spring, so the wildcard ban has to be ours.

### D6 — A disallowed origin is rejected before authorization

The `CorsFilter` sits before authentication and authorization, so an untokened preflight succeeds and is
answered by the filter. A non-preflight request from a disallowed origin is rejected with 403 and a fixed
`Invalid CORS request` body, no `Access-Control-Allow-*` headers, and never reaches the matcher table or a
controller. A 401 from an allowed origin keeps its CORS headers, so the browser can read the error
envelope: otherwise a wrong password looks like a CORS bug.

### D7 — Security headers: platform defaults, no new dependency

Spring Security's defaults stay (frame options, `nosniff`, `Cache-Control` on errors, HSTS only over
HTTPS). Page-level headers belong to Next.js. No header library is added to the backend, and no custom
filter is written for this phase.

### D8 — CORS is not a security control; rate limiting stays deferred

CORS is a browser-enforced policy that any non-browser client ignores. It does not authorize, does not
throttle, and does not protect the API. Authorization is the matrix; login brute force is the lockout
(ADR-022 D12). Rate limiting and a public-deployment header policy (CSP, and stricter HSTS) are revisited
when a deployment exists, not in Sprint 7.

### D9 — Frontend stack pin: Next.js 16.4.6, re-checked before use

`06_FRONTEND_SPEC.md` now pins **Next.js 16.4.6** (React 19, Node ≥ 20.9.0) instead of the previously
assumed 14. On 2026-11-19, `next@latest` was 16.4.6 and an OSV.dev query with `version: 16.4.6` returned
no advisory at all (any severity); the HIGH/CRITICAL Next.js advisories on record are fixed at or below
that release. The version is pinned exactly, the OSV check is repeated when T32 scaffolds the app, and any
future major upgrade repeats it and records the result here. A new HIGH/CRITICAL advisory means moving to
the patched release (or the documented backport line), not staying on the pinned one.

---

## Consequences

### Positive

- One boundary to reason about: the browser is a client of the documented API, with no proxy layer.
- The allowlist is small, exact, and fails loudly at startup instead of producing mystery CORS errors.
- No cookie means no CSRF handling and no credential-bearing CORS, which keeps the resource server
  stateless.
- The 401/403 envelopes stay readable from the browser, so the UI can show real backend messages.
- CORS changes are visible in one configuration bean and covered by `CorsIT` / `CorsDeniedByDefaultIT`.

### Negative

- A reload logs the operator out until the deferred cookie option is revisited.
- Every deployment with a browser client must set the allowlist; forgetting it denies all browser calls
  (by design, but it is a new operational step).
- A non-browser client that sends an `Origin` header and is not allowlisted now gets a bare 403 from the
  CORS filter instead of reaching the endpoint. This is a behaviour change, acceptable because such a
  client should not send `Origin` at all.

### Risks

- A future careless entry (`*`, or a pattern like `https://*.example.com`) would silently widen the
  boundary. Mitigation: D5 fails startup and the unit test pins the rejected forms.
- Roles decoded in the browser are still UX data; a reviewer could mistake them for a control. Mitigation:
  D3 and the steering rule say the opposite explicitly.
- The CVE landscape moves. Mitigation: D9's exact pin plus the re-check rule.

---

## Alternatives considered

1. **Next.js BFF / `app/api/` proxy.** Rejected: it duplicates the envelope and error mapping, needs its
   own token store, and makes the browser boundary look safer than it is (the proxy becomes an
   unauthenticated relay unless carefully written).
2. **Cookie-based tokens with CSRF protection.** Deferred, not rejected on merit: it survives reloads and
   is the usual choice for a deployed product, but it adds CSRF, credentialed CORS and cookie attributes
   for a portfolio demo whose authentication is already implemented and tested (ADR-022 D10).
3. **Wildcard or pattern origins.** Rejected: `*` with credentials off is legal to Spring and too broad
   for a system holding financial data; patterns add matching rules nobody asked for.
4. **CORS via Spring MVC (`WebMvcConfigurer`).** Rejected: MVC CORS runs after the security filters, so a
   preflight would be rejected by `denyAll()` before it is answered. CORS belongs in the security chain
   with a `CorsConfigurationSource` bean.
5. **A `/me` endpoint for roles.** Rejected for this phase: `roles` is already in the JWT, and a new
   endpoint needs a matrix row and tests for no gain.

---

## References

- Spring Security reference, CORS integration: <https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html>
- OWASP: CORS is not an access control mechanism (Cross-Origin Resource Sharing).
- `docs/06_FRONTEND_SPEC.md` §1.1, §1.4, §4; `docs/02_TECH_SPEC.md` §2.6.
- Implementation: `ResourceServerSecurityConfiguration`, `AllowedOrigins`, `CorsIT`,
  `CorsDeniedByDefaultIT`.
