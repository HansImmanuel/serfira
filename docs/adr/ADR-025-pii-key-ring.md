# ADR-025: PII Key Ring and the `v2` Envelope (T28a)

- **Status:** Accepted; implemented by T28a. T28b (re-keying pass, `RequestFingerprint` key ids, retention
  rule) adds an addendum.
- **Date:** 2026-11-26
- **Deciders:** Hans (solo developer)
- **Relates to:** ADR-004 (at-rest PII), `04_GAPS_ADDENDUM.md` §9, ADR-007 / ADR-017 (idempotency
  `request_hash`), ADR-022 D13 (dev-key production guard), review finding CR-11.

---

## Context

ADR-004 seals `customer.nik` and `customer.phone` with AES-256-GCM in a `v1:` envelope, and finds customers by
HMAC-SHA-256 lookup columns (`nik_hash`, `phone_lookup`). Both keys came from one pair of properties. Changing
either key made every existing row unreadable (wrong AES key) or unfindable (wrong HMAC key), so the keys could
never rotate (CR-11).

Rotation needs three things the single pair cannot give:
- old ciphertext must still decrypt after a new key becomes active;
- old lookup hashes must still match;
- the configuration has to say which key is current, without breaking the deployments that set only the pair.

---

## Decision

### D1 — Ring configuration

A ring entry has a key id (`kid`), an AES-256 encryption key and an HMAC key. Rotation moves both keys together,
so they live in one entry. Configuration is additive:

| Property | Environment variable | Meaning |
|---|---|---|
| `serfira.security.pii.encryption-key` / `hmac-key` | `SERFIRA_SECURITY_PII_ENCRYPTION_KEY` / `_HMAC_KEY` | Unchanged. When set, this pair is ring entry **`k1`**. |
| `serfira.security.pii.keys.<kid>.encryption-key` / `.hmac-key` | `SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY` / `_HMACKEY` | Further entries. |
| `serfira.security.pii.active-kid` | `SERFIRA_SECURITY_PII_ACTIVEKID` | The key for new writes and hashes. Defaults to `k1` when the flat pair is set; otherwise it is required. |
| `serfira.security.pii.legacy-kid` | `SERFIRA_SECURITY_PII_LEGACYKID` | The key that reads `v1:` envelopes. Defaults to `k1` when the flat pair is set; otherwise there is none. |

The existing flat pair therefore still configures exactly today's behaviour: a ring of one key, `k1`, which is
both active and legacy. Compose, the test profile and existing deployments need no change. Defining `keys.k1.*`
while the flat pair is set is a startup error, because `k1` would be defined twice.

### D2 — `kid` syntax

A `kid` is 1–16 lowercase letters or digits (`^[a-z0-9]{1,16}$`). Spring's environment-variable binding uses
`_` to separate path segments and lowercases map keys. So `-`, `_` and uppercase cannot round-trip through
`SERFIRA_SECURITY_PII_KEYS_<KID>_…`, and `:` would break the envelope.

### D3 — The `v2` envelope

- Writes produce `v2:<kid>:<base64(iv12 ‖ ciphertext|tag128)>` under the active key.
- Reads accept:
  - `v2` under any ring key;
  - `v1:<base64(…)>` under the legacy key.
- These read failures all throw `IllegalArgumentException`; none returns null or garbage:
  - an unknown `kid`;
  - a `v1` envelope with no legacy key;
  - a malformed envelope: no second `:`, an invalid `kid`, bad base64, or a body of 12 bytes or fewer;
  - a failed GCM tag.
- An error message never repeats the envelope or the plaintext. A parsed, valid `kid` may appear.
- The `kid` is not bound as AAD. A body relabelled with another ring `kid` still fails its tag, because no two
  ring keys share material (D5).
- No migration was needed: `nik` and `phone` have been `TEXT` since V2.

### D4 — HMAC: active for writers, candidates for matching

- `PiiSecuritySupport.hmacHex` keeps meaning "the active key". New `nik_hash` / `phone_lookup` values and
  `RequestFingerprint` use it.
- Matching uses **candidates**: the value's hash under every ring key, the active key first, then the other
  `kid`s in ascending order (`PiiHasher.nikCandidates` / `phoneCandidates`).
- Both lookup sites match any candidate:
  - `CustomerSearchService`;
  - `ContractCommandService.resolveCustomer`, including its phone comparison. A customer hashed under the old
    key is reused, not reported as "registered with a different phone".
- Two matching rows mean one person stored under two key ids. The lookup fails with the row count only, instead
  of picking one.
- Concurrent creates both hash under the active key, so the unique indexes on `nik_hash` / `phone_lookup` still
  catch a race, as long as every instance has the same active key. During a rolling switch (D6 step 2), an
  old instance writes `k1` hashes while a new one writes `k2` hashes. Two concurrent creates of the same new
  person, one on each, can then insert a cross-`kid` duplicate. Lookups refuse that duplicate loudly, and so
  does T28b's pre-flight.

### D5 — Startup validation

The ring refuses to start when:
- the active `kid` is missing from the ring;
- a legacy `kid` is set and missing from the ring;
- a `kid` appears twice;
- a `kid` breaks D2;
- an encryption key is not exactly 32 bytes, or an HMAC key is shorter than 32 bytes;
- two different `kid`s share an encryption key or an HMAC key;
- an entry has only one of its two keys;
- a value is not base64.

Every message names the property and its environment variable, never the value. The dev-key production guard
(ADR-022 D13) checks every ring member against the public Compose keys, not only the flat pair.

### D6 — Rotation runbook

1. **Add** the new entry (`keys.<new>.*`) on every instance and deploy, still on the old `active-kid`. From
   now on every instance can read the new key before any instance writes with it.
2. **Switch** `active-kid` to the new `kid` and deploy. New rows are `v2:<new>:…` with new-key hashes, and old
   rows still read and match. Switch every instance together, or pause customer creation during the switch:
   a window with mixed active keys can insert a cross-`kid` duplicate (D4).
3. **Re-key** with T28b's pass, which moves every row to the active key.
4. **Retire** the old entry only by T28b's retention rule, never on time alone.

> **Do not perform step 2 until T28b ships.** Switching the active key also rotates the HMAC key.
> `idempotency_keys.request_hash` is an HMAC computed under the key that was active when the request was
> claimed (ADR-007). Until T28b records and honours that key id, an idempotent replay across the switch would
> compare against the new key and get `409`.

---

## Consequences

- PII keys can rotate without downtime and without making a row unreadable or unfindable. A ring of one key
  behaves exactly as before, except that new writes are `v2:k1:`.
- Every lookup computes one HMAC per ring key. A ring normally holds two keys at most, and only during a
  rotation.
- `v1:` rows remain readable for as long as the legacy key is configured. T28b re-encrypts them.
- An operator who removes a key before T28b's verdict makes rows unreadable. Because of D3, the failure is loud.

## Alternatives Considered

- **Bind the `kid` as GCM AAD.** Relabelling already fails the tag under D5's distinct-material rule, so it
  adds nothing. Rejected.
- **Separate rings for encryption and HMAC.** The backlog fixes that rotation moves both keys together. Two
  rings would double the configuration and the failure modes. Rejected.
- **Store the `kid` in its own column.** It needs a migration and can drift from the ciphertext, while the
  envelope already carries the `kid`. Rejected.
- **Re-encrypt in SQL on rotation.** ADR-004 forbids encryption in SQL, and keys never enter the database.
  Rejected; T28b re-keys in the application.
