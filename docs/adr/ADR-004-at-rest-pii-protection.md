# ADR-004: At-Rest PII Protection (NIK & Phone)

- **Status:** Accepted
- **Date:** 2026-09-14
- **Deciders:** Hans (solo developer)

---

## Context

`Customer.nik` (national ID) and `Customer.phone` are sensitive identifiers. The V1 schema stores them as
plaintext `VARCHAR` columns; DOMAIN_MODEL §1.1 and ADDENDUM §9 already *planned* application-level at-rest
encryption ("Sprint 2+", optional for MVP). Before the first write path to `Customer` (sprint B5) creates
real rows, we must decide how to store these fields and how to search/dedupe by them without exposing
plaintext at rest.

Search/equality on ciphertext is impossible in a useful way: randomized encryption produces different
bytes for equal plaintext, so uniqueness must come from a *deterministic, keyed* companion value. A plain
(unkeyed) hash was rejected because NIKs have low effective entropy (region/birthdate/sequence structure)
and are enumerable.

---

## Decision

1. **Encrypt `nik` and `phone` at rest** with **AES-256-GCM** via an application-level JPA
   `AttributeConverter` (`AesGcmStringAttributeConverter` + static `PiiSecuritySupport` bridge, the same
   pattern as `AuditSupport`). Format: `v1:<base64(12-byte IV ‖ ciphertext|128-bit tag)>`; random IV per
   write. Columns widen from `VARCHAR(16)/VARCHAR(20)` to `TEXT` (migration `V2__encrypt_customer_pii.sql`).
2. **Lookup/uniqueness only through HMAC-SHA-256 lookup columns** on the *normalized* value
   (`PiiHasher`):
   - `nik_hash CHAR(64)` — already documented, **unchanged**. Unique.
   - `phone_lookup CHAR(64)` — **new**. Unique: one phone per customer is now a business rule.
   - Normalization: NIK → digits only; phone → digits only with Indonesian trunk (`0…` → `62`) so
     `0812-3456-789` ≡ `+628123456789`.
3. **`phone` becomes `NOT NULL`.** It is the primary collection contact and the frontend customer form
   already marked it required; the nullable default was a docs inconsistency, now resolved (ADDENDUM §9).
4. **Search by raw identifier** goes through `CustomerSearchService` only: the raw value is normalized and
   HMAC'd in memory, the query uses only the lookup value, and the raw value never reaches SQL/logs/URLs.
   The HTTP endpoint is deliberately **not exposed** until RBAC exists (default-deny, addendum §3.4).
5. **Key management:** `SERFIRA_PII_ENCRYPTION_KEY` (32 bytes) and `SERFIRA_PII_HMAC_KEY` (≥32 bytes),
   base64, app-env only — **never in the database**. Startup fails fast when missing. Dev/portfolio values
   live only in `docker-compose.yml` and `src/test/resources/application.properties`.

---

## Consequences

**Positive**
- The DB contains ciphertext for NIK/phone; nothing at rest is usable for identity theft from a leak.
- Uniqueness/dedup and search remain fully functional through the keyed lookup columns.
- AES-GCM is authenticated — tampered ciphertext is rejected instead of silently corrupted.
- Versioned envelope (`v1:`) allows future algorithm/key rotation without schema change.
- Satisfies TECH SPEC §6.5 PII test: ciphertext never determines uniqueness; `nik_hash` unique; raw
  values absent from logs/DB.

**Negative / Tradeoff**
- Entities hold plaintext in memory during the JPA lifecycle (standard "encryption at rest" model) —
  heap dumps/swap could expose values; acceptable for the portfolio scope.
- Key hygiene is now an operational requirement: a lost key means the data is unreadable; a leaked key
  defeats the scheme.
- Phone sharing (household/corporate/reassigned numbers) will **reject** a new customer until the number
  is free — deliberate business rule chosen by the domain owner.

**Considerations for later sprints**
- `V99__demo_seed.sql` must create customers **through the application** (product code generates
  ciphertext/lookups), not via raw SQL with plaintext values.
- Any future migration that touches existing `nik`/`phone` rows must use the application services (or run
  a backfill job); never encrypt in SQL.
- The internal search endpoint gets added to the addendum §3.4 RBAC matrix when auth lands (Sprint 6b).

---

## Alternatives Considered

| Option | Reason rejected |
|---|---|
| Keep plaintext `VARCHAR` | No confidentiality for a national identifier; contradicts the documented plan |
| Store only a hash of NIK/phone | Unkeyed hashes of low-entropy identifiers are enumerable; loses legitimate read-back for ops; `nik_hash` already covers dedup |
| DB-level encryption (pgcrypto) | Key management inside the DB / SQL; less portable and harder to test; docs explicitly prefer app-level |
| Encrypt `phone` only via masking in API | Masking protects read-side roles but not the storage layer; the converter is reusable so cost is trivial |