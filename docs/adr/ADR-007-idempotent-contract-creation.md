# ADR-007: Idempotent Contract Creation on the Documented idempotency_keys Mechanism

- **Status:** Accepted
- **Date:** 2026-09-18
- **Deciders:** Hans (solo developer)

---

## Context

`02_TECH_SPEC.md` §2.2 makes `Idempotency-Key` mandatory for the minimum set of retryable mutations —
POST payment, settlement and credit application — and §2.5 defines the mechanism: a row in
`idempotency_keys` per `(endpoint, key)` carrying `request_hash`, the stored response and an
`expires_at`. The table has existed since V1 but had no code behind it, and no story before C3
(Sprint 3) was scheduled to use it.

Story B5 introduces `POST /api/v1/contracts`. Two independent problems surfaced during review:

1. **Duplicate creation.** The endpoint is naturally retried (client timeout after a successful
   commit, double click). ADR-006's invariant 18 blocks a second *live* contract for the same
   `(customer, asset)`, but only when the asset has an identity (`serial_no`/`plate_no`) — an asset
   without either can still be financed twice, and the second attempt would return a confusing
   `409 DUPLICATE_CONTRACT` instead of the contract that was actually created.
2. **Where the mechanism belongs.** Building it inside `contract` would force story C3 to copy it for
   payments and settlements; the documented semantics are endpoint-scoped and generic.

`idempotency_keys.key` is `VARCHAR(80)`, uniqueness is `(endpoint, key)`, and §2.5's rules are exact:
same key + same request → return the stored response without re-executing; same key + different
request → `409 CONFLICT`.

---

## Decision

1. **Build the documented mechanism now, as shared infrastructure** (`com.serfira.shared.idempotency`:
   `IdempotencyKey`, `IdempotencyKeyRepository`, `IdempotencyService`, `IdempotentResult`,
   `RequestFingerprint`, `CanonicalRequestJson`), so C3 (payments), E1–E3 (settlements) and credit
   application reuse it instead of re-inventing it.
2. **Claim-then-execute inside the caller's transaction.** The claim is
   `INSERT … ON CONFLICT (endpoint, key) DO NOTHING` (the same pattern as
   `DocumentNumberCounterRepository.insertIfAbsent`), executed with `Propagation.MANDATORY` so the
   claim and the business write commit or roll back together. A racing duplicate blocks on the unique
   index until the first transaction ends, then inserts nothing and reads the committed outcome — no
   `IN_PROGRESS` handshake, and a failed attempt leaves no claim behind for a corrected retry.
3. **`Idempotency-Key` is required on `POST /api/v1/contracts`** (missing/blank → `400
   VALIDATION_ERROR`). Activation is *not* keyed: it is idempotent by state machine (ADR-006 §2), and
   §2.2's mandatory set is a minimum, not an exhaustive list.
4. **The stored response is replayed**, exactly as §2.5 specifies, including its consequence: a
   replay after the contract was activated still shows the DRAFT snapshot.
5. **Only masked projections are stored.** `response_json` holds the API DTOs, where customer PII is
   already masked by `CustomerSummary`, so the retry path adds no plaintext-PII storage.
6. **The request fingerprint is a keyed HMAC-SHA-256, not a plain digest.** §2.5 literally says
   "SHA-256 of canonical JSON"; the canonical payload contains NIK and phone, and a plain unkeyed
   digest of PII is dictionary-attackable. `RequestFingerprint` therefore reuses the project's
   existing keyed primitive (`PiiSecuritySupport.hmacHex`, ADR-004). Equality semantics are
   identical, and the value is not reversible. §2.5 is updated to record this refinement.
7. **Canonicalization is explicit and scale-normalized** (`CanonicalRequestJson`): fixed field order,
   money at scale 2, rates at scale 4, trimmed strings, ISO dates, rendered by hand rather than
   through a serializer, so the fingerprint does not depend on Jackson's configuration. A retry that
   differs only in number scale (`20000000` vs `20000000.00`) is the same request.
8. **Defense in depth**, mirroring `uq_payment_idempotency` (V1 lines 221-223): nullable
   `contract.idempotency_key` plus the partial unique index `uq_contract_idempotency` (V7) guarantees
   at most one contract per key even if the `idempotency_keys` row were bypassed.
9. **Retention** comes from configuration (`IDEMPOTENCY_KEY_RETENTION_DAYS`, seeded 7): `expires_at` =
   claim time + retention. The cleanup job is deliberately **not** implemented in B5 (it belongs with
   the C3 idempotency story).

---

## Alternatives Considered

- **Defer idempotency to C3 and rely on invariant 18.** Rejected: the provenance of a duplicate create
  is accidental, not malicious, and an unidentified asset gets no protection at all. The mechanism is
  small and already documented.
- **Row-level key only (`contract.idempotency_key` unique), payment-style.** Rejected as the *primary*
  mechanism: it cannot express "same key + different payload → 409" without also storing a request
  hash, and replay would depend on reconstructing the response instead of returning the documented
  stored one. Kept as the backstop (decision 8).
- **Store the request payload for comparison instead of a fingerprint.** Rejected: it would persist
  PII in a table that has no need for it.
- **A general "idempotency filter" in the web layer.** Rejected: the claim must live inside the same
  transaction as the business write, which a filter cannot guarantee.
- **Plain SHA-256 as §2.5 literally says.** Rejected for PII-bearing payloads (decision 6).

---

## Consequences

- Story C3 inherits a tested mechanism; adding a keyed endpoint becomes a call to
  `IdempotencyService.execute(...)` plus the header contract.
- **Replay returns a snapshot, not live data.** This follows §2.5 and is asserted in the ITs; clients
  that need fresh state should GET the resource.
- **Canonicalization must track DTO changes**: adding a request field without adding it to
  `ContractCommandService.canonicalize` would let two different requests share a fingerprint. The ITs
  cover the scale-variant and different-payload cases; reviewers must check the canonical field list
  alongside any `CreateContractRequest` change.
- **The HMAC key is part of the fingerprint.** Rotating `serfira.security.pii.hmac-key` makes existing
  rows un-comparable, so an old key can no longer be replayed against a new attempt. Rows expire after
  the retention window, which bounds the exposure; a rotation procedure for `idempotency_keys` is
  noted as a Sprint 6b security item and is not implemented here.
- Cleanup of expired rows is pending the C3 story; until then the table grows with successful
  mutations. Recorded as a known gap rather than an undocumented one.
- `auditContext.actorId()` is written into the claim row, so who created a contract stays attributable
  even when the response is replayed later.
