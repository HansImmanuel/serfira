# ADR-025: PII Key Ring and the `v2` Envelope (T28a)

- **Status:** Accepted; implemented by T28a (D1–D7) and T28b (D8–D10: fingerprint key ids, re-keying pass,
  retention rule).
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
| `serfira.security.pii.write-envelope` | `SERFIRA_SECURITY_PII_WRITEENVELOPE` | `v1` (default) or `v2`: the envelope new values are written in (D7). |

The existing flat pair therefore still configures exactly today's behaviour: a ring of one key, `k1`, which is
both active and legacy and, by default, still writes `v1:` (D7). Compose, the test profile and existing deployments need no change. Defining `keys.k1.*`
while the flat pair is set is a startup error, because `k1` would be defined twice.

### D2 — `kid` syntax

A `kid` is 1–16 lowercase letters or digits (`^[a-z0-9]{1,16}$`). Spring's environment-variable binding uses
`_` to separate path segments and lowercases map keys. So `-`, `_` and uppercase cannot round-trip through
`SERFIRA_SECURITY_PII_KEYS_<KID>_…`, and `:` would break the envelope.

### D3 — The `v2` envelope

- With `write-envelope=v2`, writes produce `v2:<kid>:<base64(iv12 ‖ ciphertext|tag128)>` under the active key.
  Until then they produce `v1:<base64(…)>`, which every earlier version reads (D7).
- Reads accept:
  - `v2` under any ring key;
  - `v1:<base64(…)>` under the legacy key.
- These read failures all throw `IllegalArgumentException`; none returns null or garbage:
  - an unknown `kid`;
  - a `v1` envelope with no legacy key;
  - a malformed envelope: no second `:`, an invalid `kid`, bad base64, or a body of 12 bytes or fewer;
  - a failed GCM tag.
- An error message never repeats any part of the envelope, not even its `kid`, and never the plaintext. A
  16-digit NIK matches the `kid` syntax, so a corrupted value could carry one in that position.
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
  does the D9 pre-flight.

### D5 — Startup validation

The ring refuses to start when:
- the active `kid` is missing from the ring;
- a legacy `kid` is set and missing from the ring;
- a `kid` appears twice;
- a `kid` breaks D2;
- an encryption key is not exactly 32 bytes, or an HMAC key is shorter than 32 bytes;
- two different `kid`s share an encryption key or an HMAC key;
- an entry has only one of its two keys, or is set to a value of its own instead of its two keys;
- a value is not base64.

Every message names the property and its environment variable, never the value. `PiiKey` validates in its
canonical constructor too, so no code path can build an entry that skips these checks. The dev-key production
guard (ADR-022 D13) checks every ring member, not only the flat pair, and compares each key with **all** the
public Compose values: the dev HMAC key is a valid AES key and the dev JWT secret a valid HMAC key, so a swapped
or misplaced dev value is just as public.

### D6 — Rotation runbook

0. **Finish the D7 transition first.** `write-envelope` must be `v2` before step 2 (startup refuses `v1` writes
   unless both the active and the legacy key are `k1`). Keep `legacy-kid` at `k1` throughout.
1. **Add** the new entry (`keys.<new>.*`) on every instance and deploy, still on the old `active-kid`. From
   now on every instance can read the new key before any instance writes with it.
2. **Switch** `active-kid` to the new `kid` and deploy. New rows are `v2:<new>:…` with new-key hashes, and old
   rows still read and match. Switch every instance together, or pause customer creation during the switch:
   a window with mixed active keys can insert a cross-`kid` duplicate (D4).
3. **Re-key** with the D9 pass: run it as `DRY_RUN`, then as `EXECUTE`, then disable it. It moves every row
   to the active key.
4. **Retire** the old entry only when the D10 verdict says `RETIRABLE`, never on time alone. To re-read the
   verdict after step 3, re-enable the runner as `DRY_RUN`, restart, read the report, and disable it again.
   Then drop the entry in one deploy on every instance. For `k1`, the flat pair, unset or blank
   `serfira.security.pii.encryption-key` and `.hmac-key` (`SERFIRA_SECURITY_PII_ENCRYPTION_KEY` / `_HMAC_KEY`);
   an entry under `keys.<kid>.*` is removed with its two variables. If `legacy-kid=k1` was set explicitly, unset
   it in the same deploy, or startup refuses because the legacy kid is not in the ring. `active-kid` stays set
   (it is required once no flat pair exists) and so does `write-envelope=v2`.

> **Do not perform step 2 while a T28a instance still runs.** Switching the active key also rotates the HMAC
> key. A T28a instance records no key id with its idempotency claims (D8), so it would claim with a `NULL` kid
> while signing under the new key, and a replay of that claim would be recomputed under the legacy key and get
> `409`. Switch only once every instance runs T28b or later. For the same reason, do not roll back below T28b
> after the switch: a T28a build compares replays under the active key only, so a retry of a claim recorded under
> the old key would get `409`.

### D7 — Version compatibility: `v2` writes are switched on separately

The application before T28a reads only `v1:`. If this version wrote `v2` as soon as it was deployed, any older
instance would fail to decrypt those rows. That happens in two cases:
- an old and a new instance overlap during a deployment;
- the deployment is rolled back to a pre-T28a version.

ADR-001 fixes a single deployment unit but says nothing about overlap or rollback, so neither may be assumed
away. The rollout therefore has two phases:

1. **Phase 1 — read `v2`, write `v1`.** Deploy this version with `write-envelope` unset, which defaults to
   `v1`. It reads `v1` and `v2`, and writes only `v1` under `k1`, which every earlier version reads.
   Overlapping old instances and rollback are both safe.
2. **Phase 2 — write `v2`.** Set `write-envelope=v2` and deploy, but only once both of these hold:
   - no pre-T28a instance runs;
   - rolling back below this version is no longer an option.

   From then on new rows are `v2:<active>:…`.

Rules that enforce it:
- `v1` writes are refused at startup unless the active key and the legacy key are both `k1`. A `v1` envelope has
  no `kid`, and every `v1:` row on disk was sealed under `k1`, the only key a pre-T28a version knows. Requiring
  only "active = legacy" is not enough: `active-kid=k2` with `legacy-kid=k2` would strand the existing `k1` rows
  and write new `v1:` rows that no older instance can read and that nothing can tell apart from the `k1` rows. A
  rotation (D6 step 2) therefore cannot begin before phase 2. With no legacy key at all, the refusal says
  `legacy-kid` is missing rather than suggesting `v2`, since `v2` would leave the existing `v1:` rows unreadable.
- Setting `write-envelope` back to `v1` does not convert `v2` rows that already exist. After phase 2, a rollback
  below T28a makes those rows unreadable. Phase 2 is the point of no return, and it is a deliberate operator
  step.
- The D9 re-keying pass writes `v2` under the active key, so it also runs only after phase 2.

---

## Consequences

- PII keys can rotate without downtime and without making a row unreadable or unfindable. A ring of one key
  behaves exactly as before, on disk included, until an operator turns on `v2` writes (D7).
- Every lookup computes one HMAC per ring key. A ring normally holds two keys at most, and only during a
  rotation.
- `v1:` rows remain readable for as long as the legacy key is configured. The D9 pass re-encrypts them.
- An operator who removes a key before the D10 verdict makes rows unreadable. Because of D3, the failure is loud.

## Alternatives Considered

- **Bind the `kid` as GCM AAD.** Relabelling already fails the tag under D5's distinct-material rule, so it
  adds nothing. Rejected.
- **Separate rings for encryption and HMAC.** The backlog fixes that rotation moves both keys together. Two
  rings would double the configuration and the failure modes. Rejected.
- **Store the `kid` in its own column.** It needs a migration and can drift from the ciphertext, while the
  envelope already carries the `kid`. Rejected.
- **Re-encrypt in SQL on rotation.** ADR-004 forbids encryption in SQL, and keys never enter the database.
  Rejected; the D9 pass re-keys in the application.

---

## Addendum (T28b): fingerprint key ids, the re-keying pass and the retention rule

### D8 — Idempotency fingerprints record their key id

`idempotency_keys.request_hash` is an HMAC computed under the key that was active when the request was claimed
(ADR-007). A rotation changes the active key, so a replay of an older claim has to be recomputed under the key
that claim used.

- **Column.** Migration `V25__idempotency_request_hash_kid.sql` adds `request_hash_kid VARCHAR(16) NULL`, with a
  check that a non-null value matches the `kid` syntax (D2). There is no backfill: `NULL` means a claim recorded
  before key ids existed, which was hashed under the legacy key. (The task text said V24, but V24 is the
  reporting indexes.)
- **Claim.** A claim stores the fingerprint and the active `kid` together.
- **Replay rule.** A replay recomputes the fingerprint of the new request under the row's recorded `kid`, or
  under the legacy key when the column is `NULL`, and compares it with the stored hash. Across a rotation, the
  same body therefore replays and a different body still gets `409`.
- **Expiry first.** An expired claim is rejected before its `kid` is resolved or any hash is compared
  (ADR-017), so a claim past its retention window never needs its key again.
- **A missing key fails loudly.** A recorded `kid` that is not in the ring, or a `NULL` claim with no legacy key
  configured, throws `IllegalStateException` instead of answering `409`. It means a key was retired too early.
  The message names the `kid` or the `legacy-kid` property, never a hash.
- **Switch the active key only once no T28a instance runs.** A T28a instance does not write
  `request_hash_kid`, so its claims carry `NULL`, yet it signs them under the key that is active on that
  instance. After a switch, a replay of such a claim would be recomputed under the legacy key and wrongly
  conflict. This is the D6 step 2 condition. When only T28b or later instances run, every claim made after the
  switch carries its `kid`.

### D9 — The re-keying pass

A maintenance runner moves every customer's envelopes and lookup hashes to the active key. It lives in the
`contract` module, reads fingerprint usage through `shared.idempotency`, and exists only when
`serfira.maintenance.pii-rekey.enabled=true`. Otherwise no bean is created and startup is unchanged.

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `serfira.maintenance.pii-rekey.enabled` | `SERFIRA_MAINTENANCE_PIIREKEY_ENABLED` | absent | `true` creates the runner; anything else creates no bean. |
| `serfira.maintenance.pii-rekey.mode` | `SERFIRA_MAINTENANCE_PIIREKEY_MODE` | `DRY_RUN` | `DRY_RUN` or `EXECUTE`; an unknown value fails startup. |
| `serfira.maintenance.pii-rekey.batch-size` | `SERFIRA_MAINTENANCE_PIIREKEY_BATCHSIZE` | `500` | Page size, at least 1. |
| `serfira.maintenance.pii-rekey.lock-at-most-for` | `SERFIRA_MAINTENANCE_PIIREKEY_LOCKATMOSTFOR` | `PT10M` | ShedLock bound; the keep-alive lock provider extends it while the pass runs. |

- **How it runs.** Once at startup, under its own ShedLock lock (`pii-rekey`), as the `SYSTEM` actor
  (`AuditContext.SYSTEM_USER_ID`), with one `job_run` row. A stale `RUNNING` row left by a crash is abandoned
  first. The runner is a synchronous `ApplicationRunner`. By the time runners run, the embedded server already
  accepts requests, so the application serves during the pass. A failure is logged and recorded in `job_run`;
  it never stops the application. The cost is that a readiness probe, if one is enabled, reports ready only
  after the pass.
- **Pre-flight.** A read-only census pages through every customer, decrypts both envelopes and computes the
  active-key hashes, keeping two sets of lookup hashes, so memory grows with the number of customers. For `EXECUTE`, it aborts with **zero writes** when any row has an unknown `kid` or fails to
  decrypt, or when two customers would share an active `nik_hash` or `phone_lookup`. `EXECUTE` is also refused
  while `write-envelope=v1`, because the pass writes `v2` (D7). That refusal comes before any scan or write.
- **A row is on target** when both envelopes start with `v2:<activeKid>:` and both lookup columns equal the
  active-key hash of the decrypted value. Everything else is re-keyed, `v1:` rows included while `k1` is still
  active, so the legacy designation can be dropped later.
- **Execute.** Pages by `id` from the zero UUID, one transaction per page. A row off target is re-encrypted and
  re-hashed in the application (ADR-004: never in SQL) and written by native SQL on the raw columns, because the
  entity holds only plaintext and JPA dirty checking would never fire. Only `nik`, `nik_hash`, `phone`,
  `phone_lookup`, `updated_at` and `updated_by` change. The runner is the only writer of an existing customer
  row, and the create flow's never-mutate rule is untouched.
- **Per-row verification.** In the same transaction, each rewritten row is read back, decrypted and compared
  with the expected plaintext and both hashes. A mismatch rolls back that page and aborts the run.
- **Resume.** A rerun after an abort skips rows already on target. The cursor advances by the rows *scanned*,
  not the rows rewritten, so pages that are already done are passed over and the loop terminates. A second
  complete run changes zero rows.
- **`job_run` counters.**

  | Run | `status` | `records_processed` | `records_failed` |
  |---|---|---|---|
  | `DRY_RUN` | `COMPLETED` | 0 | 0 |
  | `EXECUTE` | `COMPLETED` | rows re-keyed | 0 |
  | `EXECUTE` refused while `write-envelope=v1` | `FAILED` | 0 | 0 |
  | Pre-flight abort | `FAILED` | 0 | blocking findings |
  | Any later abort | `FAILED` | rows committed so far | 1 |

  Blocking findings are the sum of the unknown-`kid`, undecryptable, duplicate-NIK and duplicate-phone counts,
  so a row that duplicates on both NIK and phone counts twice.
- **Logs carry counts, kids and customer ids only** (Addendum §9). The startup runner logs an abort at ERROR as
  `pii-rekey <MODE> run aborted (<ExceptionClass>): <message>` for the pass's own abort exceptions, and as
  `pii-rekey <MODE> run aborted (<fully qualified class>)` for anything else, with no message and no stack
  trace. A database error can quote a lookup hash (a unique violation prints its `Key (nik_hash)=(...)` detail),
  so its message never reaches the log. A successful run logs
  `pii-rekey <MODE> run completed: <n> rows re-keyed`.

### D10 — Retention rule and retirement verdict

An old HMAC key is retained until every record signed with it has expired. An old encryption key is retired once
no customer row carries its `kid`. Concretely:

- **Customer rows:** none remain under the old key once a verified pass completes.
- **Idempotency claims:** none is still replayable. A claim is **unexpired** when
  `expires_at IS NULL OR expires_at > now`. That mirrors `IdempotencyService`, which compares a claim whenever
  it has no expiry or its expiry is in the future. A claim from before expiry was recorded (`expires_at`
  `NULL`) is therefore always counted, so a key is never called retirable while a replay can still need it.
  `now` is the application's injected clock.

Every run, dry or real, ends with a report. Per ring `kid` it states one verdict:

- the active key is `ACTIVE`;
- any other key is `RETIRABLE` only when all of these are zero:
  - customer rows with an envelope under it (a `v1:` envelope counts under the legacy key);
  - customer rows with any hash off the active key (a global count: conservative, and exact for a ring of two);
  - unexpired fingerprints recorded under it (`NULL` counts under the legacy key);
  - unreadable rows (an unknown `kid` or a failed decrypt);
- otherwise it is `NOT_RETIRABLE`;
- an unexpired fingerprint whose `kid` is not in the ring is reported as `MISSING_FROM_RING`, because its
  replays will fail (D8).

The report is written to the log as one line per key and a summary:

```
pii-rekey key kid=<kid> verdict=<VERDICT> envelopeRows=<n> unexpiredFingerprints=<n>
pii-rekey summary activeKid=<kid> rowsOffActiveHashes=<n> unreadableRows=<n> unlabelledFingerprints=<n>
```

`unlabelledFingerprints` counts unexpired claims with a `NULL` kid. With no legacy key they cannot be attributed
to any key, so while it is above zero every non-active key is `NOT_RETIRABLE`: dropping `k1` would remove the
option of fixing those claims by setting `legacy-kid=k1`.

Every run, `DRY_RUN` included, first logs one census line once the census has run, before the `EXECUTE`
pre-flight gate, so it also appears on an abort:

```
pii-rekey census scannedRows=<n> rowsToRekey=<n> unknownKidRows=<n> undecryptableRows=<n> duplicateNikRows=<n> duplicatePhoneRows=<n> executeWouldRun=<true|false>
```

`executeWouldRun=false` means `EXECUTE` would refuse or abort: a blocking row, or `write-envelope=v1`. A `DRY_RUN`
therefore shows the duplicate counts and what an `EXECUTE` would still have to move. An aborted `EXECUTE` also
reports `pii-rekey pre-flight aborted with zero writes: <n> unknown-kid, <n> undecryptable, <n> duplicate NIK, <n>
duplicate phone rows` through its exception.

An unexpired claim keeps its key `NOT_RETIRABLE` even after a clean pass, until its `expires_at` passes. The
retention window is the `IDEMPOTENCY_KEY_RETENTION_DAYS` system parameter (7 days). Run a `DRY_RUN` again after
that time. The verdict is the gate, and retiring a key on time alone makes rows unreadable.

### Consequences of the addendum

- A replay across a rotation is a replay, not a `409`. If a key was retired while a claim still needs it, the
  replay fails loudly.
- The pass rewrites PII in place. The pre-flight abort and the per-row verification are the safety net, so run
  `DRY_RUN` in every environment first.
- The pass holds its ShedLock lock for as long as it takes. The application serves meanwhile, and a customer
  created during the pass is already on the active key.
