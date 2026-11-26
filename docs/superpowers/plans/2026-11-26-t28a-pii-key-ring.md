# T28a — PII Key Ring and `v2` Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the PII encryption and HMAC keys rotate (CR-11) without making existing customer rows unreadable or unfindable. A ring of one key must behave exactly as today, except that new writes are `v2`.

**Architecture:**
- A validated, immutable `PiiKeyRing` replaces the two static keys in `PiiSecuritySupport`. Writes seal `v2:<kid>:…` under the active key. Reads accept `v2` under any ring key, and `v1:` under the legacy key.
- HMAC writers keep using the active key. The two lookup sites match a list of **candidates**, one per ring key with the active key first, through new repository methods.
- Configuration is additive: the existing flat pair is ring entry `k1`, and `serfira.security.pii.keys.<kid>.*` adds entries. ADR-025 records the scheme.

**Tech Stack:** Java 21, Spring Boot 4 (`Binder`, `ApplicationContextRunner`, `SpringApplicationBuilder`), JCA AES/GCM and HmacSHA256, Spring Data JPA, PostgreSQL 16 with Testcontainers.

**Spec:** `docs/tasks.md` § "T28a — PII key ring and versioned `v2` envelope (CR-11)". Its business rules come from:
- ADR-004 (at-rest PII);
- `04_GAPS_ADDENDUM.md` §9;
- ADR-022 D13 (dev-key guard).

## Decisions (made here; ADR-025 records them)

1. **Properties:**

   | Property | Meaning |
   |---|---|
   | `serfira.security.pii.encryption-key` / `hmac-key` | Unchanged. When set, this pair is ring entry **`k1`**. |
   | `serfira.security.pii.keys.<kid>.encryption-key` / `.hmac-key` | Further entries. Environment-variable form: `SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY` / `_HMACKEY`. |
   | `serfira.security.pii.active-kid` | The active key. Defaults to `k1` when the flat pair is set; otherwise it is required. |
   | `serfira.security.pii.legacy-kid` | The key that reads `v1:` envelopes. Defaults to `k1` when the flat pair is set; otherwise there is none, and a `v1:` read fails. |

   If `keys.k1.*` is configured while the flat pair is also set, startup fails: `k1` would be defined twice.

2. **`kid` syntax is `^[a-z0-9]{1,16}$`.** In Spring's environment-variable binding, `_` separates path segments and map keys come out lowercased. So `-`, `_` and uppercase cannot round-trip through an environment variable, and `:` would break the envelope.

3. **Envelope:** `v2:<kid>:<base64(iv12 ‖ ciphertext|tag128)>`.
   - There is no AAD. A body relabelled with another ring `kid` still fails the tag, because no two ring keys share material (Decision 6).
   - Every envelope failure is an `IllegalArgumentException` whose message never contains the envelope or the plaintext. The `kid` may appear, because it is validated syntax.

4. **Candidate order:** the active key first, then the other `kid`s in ascending order. There is one hash per `kid`.

5. **Lookups:**
   - `CustomerRepository` gains `findByNikHashIn` / `findByPhoneLookupIn`.
   - It also gains default methods `findByNikCandidates` / `findByPhoneCandidates`, each returning `Optional<Customer>`.
   - If a lookup matches more than one row, it throws `IllegalStateException`, with the count only. That means one person exists under two `kid`s, which is corruption that T28b's pre-flight also refuses.
   - `findByNikHash` / `findByPhoneLookup` stay, because the existing tests use them. Production callers stop using them.

6. **Startup validation**, in `PiiKeyRing`:
   - the active `kid` and the legacy `kid` (if set) are in the ring;
   - every encryption key is exactly 32 bytes, and every HMAC key is at least 32 bytes;
   - for any two distinct `kid`s, neither the encryption keys nor the HMAC keys are equal;
   - every `kid` matches the syntax in Decision 2, and no `kid` appears twice.

7. **Unchanged:**
   - No migration: `customer.nik` and `customer.phone` are `TEXT` since V2.
   - `RequestFingerprint` stays on the active key (`hmacHex`); T28b makes it `kid`-aware.

## Global Constraints

- ADR-004: AES-256-GCM with a random 12-byte IV and a 128-bit tag, HMAC-SHA-256 on normalized values, and keys never in the database or in SQL.
- Addendum §9: no PII, key or key fragment in logs, exception messages, URLs or SQL, and fail fast on a missing key.
- Compose, `src/test/resources/application.properties` and the README's existing variables keep working without edits.
- The create flow never mutates an existing customer row.
- `PiiSecuritySupport` stays the static bridge.
- Any test that rebinds `PiiSecuritySupport` restores the test-profile ring in `@AfterEach` / `@AfterAll`, by calling `PiiSecuritySupport.configure(base64("0123456789abcdef0123456789abcdef"), base64("abcdef0123456789abcdef0123456789"))`, i.e. the decoded `application.properties` values. Never leave it `reset()`: cached Spring test contexts share the static.
- Commits carry no Co-Authored-By or "Generated with" lines.

## Review Focus

1. **Environment-variable ring entries** (`SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY`, `SERFIRA_SECURITY_PII_ACTIVEKID`) must bind exactly like the properties form. This is how a real deployment configures the ring. Pinned by Task 2 `ringEntriesBindFromEnvironmentVariables`.
2. **A body relabelled with another ring `kid`** must fail the tag and never decrypt to garbage. Pinned by Task 1 `aRelabelledEnvelopeFailsTheTag`.
3. **The same customer under two `kid`s** must fail the lookup loudly, not return an arbitrary row. Pinned by Task 3 `aCustomerMatchedUnderTwoKidsFailsLoudly`.
4. **Malformed `v2` envelopes and their messages:**
   - inputs: an empty or uppercase `kid`, a missing second `:`, bad base64, a body of 12 bytes or fewer;
   - each fails as malformed;
   - no exception message echoes the input.

   Pinned by Task 1 `malformedEnvelopesFailWithoutEchoingTheInput`.
5. **A ring entry with only one of its two keys, or the flat `k1` also defined under `keys.k1`,** must fail startup and name the property, never the value. Pinned by Task 2 `aRingEntryWithoutItsHmacKeyFailsNamingTheProperty` and `theFlatPairCannotAlsoBeDefinedAsKeysK1`.

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/serfira/shared/security/PiiKey.java` (new) | One validated ring entry: `kid`, AES key, HMAC key |
| `…/shared/security/PiiKeyRing.java` (new) | An immutable, validated ring: active, legacy, lookup by `kid`, candidate order |
| `…/shared/security/PiiSecuritySupport.java` | Static bridge, now over a ring: `v2` writes, `v1`/`v2` reads, `hmacHex` (active), `hmacCandidates` |
| `…/shared/security/PiiHasher.java` | Adds `nikCandidates` / `phoneCandidates` |
| `…/shared/security/PiiKeyRingProperties.java` (new) | Reads the raw configured entries from the `Environment`, and turns them into a `PiiKeyRing` |
| `…/shared/security/PiiSecurityConfiguration.java` | Binds the ring from `PiiKeyRingProperties.load` |
| `…/shared/security/DevKeyProductionGuardConfiguration.java` | Guards every ring member |
| `…/contract/infrastructure/CustomerRepository.java` | `…In` finders and the candidate default methods |
| `…/contract/application/CustomerSearchService.java`, `ContractCommandService.java` | Candidate-aware lookups |
| `backend/src/test/java/com/serfira/shared/security/LegacyV1Envelope.java` (new, test) | Seals a `v1:` envelope for tests. No production code writes `v1` any more. |
| `docs/adr/ADR-025-pii-key-ring.md` (new), plus ADR-004, Addendum §9, GLOSSARY, README, `docs/tasks.md`, `docs/PROGRESS.md` | Docs and close-out |

---

**Before Task 1:**
- Branch `t28a-pii-key-ring` (already created from `main` @ `a6df34d`).
- Set T28a `Status: IN PROGRESS` in `docs/tasks.md`, in both the task header and the Sprint 8 scope list.
- This edit is committed with Task 1.

### Task 1: Key ring, `v2` envelope and HMAC candidates

**Files:**
- Create: `shared/security/PiiKey.java`, `shared/security/PiiKeyRing.java`, test `shared/security/LegacyV1Envelope.java`, `PiiKeyRingTest.java`, `PiiSecuritySupportKeyRingTest.java`
- Modify: `shared/security/PiiSecuritySupport.java`, `PiiHasher.java`, `AesGcmStringAttributeConverter.java` (Javadoc `v1` → `v2`)
- Modify tests: `AesGcmStringAttributeConverterTest.java`, `contract/CustomerPiiIT.java` (prefix assertions only; see the ruling in Step 6)

**Interfaces:**
- Produces:
  - `public record PiiKey(String kid, SecretKeySpec encryptionKey, SecretKeySpec hmacKey)`
    - `static PiiKey of(String kid, byte[] encryptionKeyBytes, byte[] hmacKeyBytes)` validates the `kid` syntax and the key lengths, and throws `IllegalStateException` naming the `kid`.
    - `public static final Pattern KID_PATTERN = Pattern.compile("[a-z0-9]{1,16}")`.
  - `public final class PiiKeyRing`
    - Constructor `PiiKeyRing(List<PiiKey> keys, String activeKid, String legacyKid /* nullable */)`. It applies Decision 6 and throws `IllegalStateException`.
    - `static PiiKeyRing single(byte[] encryptionKey, byte[] hmacKey)`: the `kid` is `k1`, and it is both active and legacy.
    - `PiiKey active()`, `Optional<PiiKey> find(String kid)`, `Optional<PiiKey> legacy()`, `List<PiiKey> candidateOrder()`.
  - `PiiSecuritySupport`:
    - `static synchronized void configure(PiiKeyRing ring)`.
    - `configure(byte[], byte[])` is kept and delegates to `configure(PiiKeyRing.single(...))`.
    - `encrypt` writes `v2:<activeKid>:…`, and `decrypt` reads `v1` and `v2`.
    - `hmacHex(String)` uses the active key (unchanged meaning).
    - `static List<String> hmacCandidates(String normalizedValue)` follows Decision 4.
  - `PiiHasher`: `static List<String> nikCandidates(String rawNik)`, `static List<String> phoneCandidates(String rawPhone)`. Each normalizes exactly as `hashNik` / `hashPhone` do.
  - Test `LegacyV1Envelope.seal(byte[] aesKey, String plaintext) → String`: `"v1:" + base64(iv ‖ AES/GCM(plaintext))`, the old write path.

- [ ] **Step 1: Write `PiiKeyRingTest`** (pure unit; 32-byte keys built as `"<char>".repeat(32).getBytes(UTF_8)`)
  - `aRingOfOneIsActiveAndLegacy`: `single(a, b)` → `active().kid()` and `legacy().orElseThrow().kid()` are `"k1"`; `candidateOrder()` has size 1.
  - `candidateOrderIsActiveFirstThenByKid`: keys `k1, k3, k2`, active `k2` → kids `[k2, k1, k3]`.
  - `theActiveKidMustBeInTheRing` / `theLegacyKidMustBeInTheRingWhenSet` → `IllegalStateException`, message contains the missing `kid`.
  - `aDuplicateKidIsRejected`.
  - `twoKidsMayNotShareAnEncryptionKey` / `twoKidsMayNotShareAnHmacKey` → `IllegalStateException`, message contains both `kid`s.
  - `kidSyntax`, parameterized:
    - invalid: `""`, `"K1"`, `"k:1"`, `"key-2"`, `"k_2"`, `"a".repeat(17)`;
    - valid: `"k1"`, `"2026a"`, `"a".repeat(16)`.
  - `wrongKeyLengthsAreRejectedNamingTheKid`: a 31-byte and a 33-byte encryption key, and a 31-byte HMAC key, each → `IllegalStateException` whose message contains the `kid` and `"32 bytes"`.

- [ ] **Step 2: Write `PiiSecuritySupportKeyRingTest`**
  - Setup: ring `{k1, k2}`, active `k2`, legacy `k1`.
  - `@AfterEach` restores the test-profile ring (see Global Constraints).
  - Tests:
    - `newWritesAreV2UnderTheActiveKid`: `encrypt("3171012501900001")` starts with `"v2:k2:"` and `decrypt` round-trips.
    - `aV2EnvelopeUnderANonActiveKidDecrypts`: encrypt under a ring with `k1` active, reconfigure to `k2` active, then `decrypt` the earlier value.
    - `aV1EnvelopeDecryptsUnderTheLegacyKid`: `LegacyV1Envelope.seal(k1Aes, "08123456789")` → decrypts to the plaintext.
    - `aV1EnvelopeWithoutALegacyKeyFails`: legacy `null` → `IllegalArgumentException`.
    - `anUnknownKidFails`: a `v2:k9:` envelope with a well-formed body → `IllegalArgumentException`, message contains `k9`.
    - `malformedEnvelopesFailWithoutEchoingTheInput`, parameterized:
      - inputs: `"v2:"`, `"v2:k1"`, `"v2::QUFBQQ=="`, `"v2:K1:QUFBQQ=="`, `"v2:k1:not*base64"`, `"v2:k1:" + base64(12 zero bytes)`, `"v3:k1:QUFBQQ=="`, `"3171012501900001"`;
      - each → `IllegalArgumentException`;
      - for every input longer than three characters, `getMessage()` does not contain `input.substring(3)`. This is why the missing-colon case `"v2:k1"` must not name its `kid`: only a `kid` that has been parsed and validated may appear.
    - `aRelabelledEnvelopeFailsTheTag`: take `encrypt(x)` (`v2:k2:<b>`) and rewrite it to `v2:k1:<b>` → `IllegalArgumentException`.
    - `aTamperedV2EnvelopeFailsTheTag`: flip one ciphertext byte → `IllegalArgumentException`.
    - `hmacHexIsTheActiveKeyAndFirstCandidate`: `hmacCandidates(v)` has size 2, its first element equals `hmacHex(v)`, and the two elements differ.
    - `aRingOfOneHashesExactlyAsBefore`: with `configure(enc, hmac)`, `hmacHex("3171012501900001")` equals an HMAC-SHA-256 hex computed in the test directly with `javax.crypto.Mac`.
- Also add `PiiHasherTest.candidatesNormalizeLikeTheHash`: `nikCandidates("3171-0125-0190-0001").get(0)` equals `hashNik("3171012501900001")`, and the same for phone with `"+62 812-3456-789"` / `"08123456789"`.

- [ ] **Step 3: Run and watch them fail**

  Run: `cd backend && ./gradlew test --tests "*PiiKeyRingTest" --tests "*PiiSecuritySupportKeyRingTest" --tests "*PiiHasherTest"`

  Expected: compilation failure (`PiiKeyRing`, `PiiKey`, `hmacCandidates` and `nikCandidates` don't exist yet).

- [ ] **Step 4: Implement** `PiiKey`, `PiiKeyRing`, the `PiiSecuritySupport` changes and the `PiiHasher` candidates against the Interfaces above.
  - `PiiSecuritySupport` holds one `volatile PiiKeyRing`. `requireKeys()` keeps its message.
  - `decrypt` dispatches on the prefix:
    - `v1:` → the legacy key;
    - `v2:` → split the remainder on the **first** `:`, check `KID_PATTERN`, then look the `kid` up in the ring;
    - anything else → malformed.
  - Wrap the base64 decoder's `IllegalArgumentException` in your own message.

- [ ] **Step 5: Run Step 3's command.** Expected: PASS.

- [ ] **Step 6: Update the prefix assertions.**
  - **Ruling to ledger:** the spec says existing PII tests pass "unchanged", and it also says new writes are `v2`. Where the two conflict, only the envelope-prefix assertions change.
  - In `AesGcmStringAttributeConverterTest`, `"v1:"` becomes `"v2:k1:"`, in the `startsWith` assertion and in the tamper test's `substring` and re-prefix.
  - In `CustomerPiiIT` (lines 64–65 and 123–124), `startsWith("v1:")` becomes `startsWith("v2:k1:")`.
  - No other assertion changes.

- [ ] **Step 7: Run the PII suites.**

  Run: `./gradlew test --tests "*AesGcmStringAttributeConverterTest" --tests "*CustomerPiiIT" --tests "*RequestFingerprintTest" --tests "*PiiHasherTest"`

  Expected: PASS.

- [ ] **Step 8: Commit:** `feat(t28a): PII key ring with v2 envelope and HMAC candidates`. Include the `tasks.md` claim.

### Task 2: Ring configuration, startup validation and the dev-key guard

**Files:**
- Create: `shared/security/PiiKeyRingProperties.java`, test `PiiKeyRingConfigurationTest.java`
- Modify: `PiiSecurityConfiguration.java` (drop the `@Value` parameters; take `Environment`), `DevKeyProductionGuardConfiguration.java`, `DevKeyProductionGuardTest.java`

**Interfaces:**
- Consumes: `PiiKey.of`, `new PiiKeyRing(...)`, `PiiSecuritySupport.configure(PiiKeyRing)` (Task 1).
- Produces: `final class PiiKeyRingProperties` (package-private), containing:
  - `record ConfiguredKey(String kid, String encryptionProperty, String encryptionEnvironmentVariable, String encryptionValue, String hmacProperty, String hmacEnvironmentVariable, String hmacValue)`.
  - `static List<ConfiguredKey> configuredKeys(Environment environment)`:
    - Returns the raw values and never throws on a value.
    - The flat pair comes first as `k1`, when either half is non-blank.
    - Then `keys.*`, bound with `Binder.get(environment).bind("serfira.security.pii.keys", Bindable.mapOf(String.class, KeyProperties.class))`, where `KeyProperties(String encryptionKey, String hmacKey)`, ordered by `kid`.
    - Environment-variable names are `SERFIRA_SECURITY_PII_KEYS_<KID upper>_ENCRYPTIONKEY` / `_HMACKEY`. The flat names are unchanged.
  - `static PiiKeyRing load(Environment environment)`, which checks in this order:
    1. No entries at all → the existing `MISSING_ENCRYPTION_KEY_MESSAGE` / `MISSING_HMAC_KEY_MESSAGE` (moved here verbatim).
    2. A half-configured entry → `"'<property>' is required (base64 …); set the <ENV> environment variable"`.
    3. `keys.k1` while the flat pair is set → a duplicate-`kid` error.
    4. A base64 decode failure → a message naming the property, never the value.
    5. The active and legacy defaults from Decision 1.
    6. `new PiiKeyRing(...)`.

- [ ] **Step 1: Write `PiiKeyRingConfigurationTest`**
  - Setup:
    - `ApplicationContextRunner` with `PiiSecurityConfiguration`;
    - `@AfterEach` restores the test-profile ring;
    - assert through `context.getStartupFailure()` messages, or `PiiSecuritySupport.encrypt("x")` prefixes on success.
  - K2 keys: 32 bytes each, distinct from K1.
  - Tests:
    - `theFlatPairAloneIsARingOfOneUnderK1` → encrypts as `v2:k1:`.
    - `noKeysAtAllFailsWithTheExistingMessage` → the message contains `'serfira.security.pii.encryption-key' is required`.
    - `aRotatedRingUsesTheActiveKid`: flat pair + `keys.k2.*` + `active-kid=k2` → `v2:k2:`, and a value sealed under the K1-only ring still decrypts.
    - `aRingWithoutTheFlatPairNeedsAnActiveKid`: only `keys.k2.*` → fails, naming `serfira.security.pii.active-kid`.
    - `anUnknownActiveKidFails` / `anUnknownLegacyKidFails`.
    - `aRingEntryWithoutItsHmacKeyFailsNamingTheProperty`: the message contains `serfira.security.pii.keys.k2.hmac-key` and `SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY`.
    - `theFlatPairCannotAlsoBeDefinedAsKeysK1`.
    - `anInvalidKidFails` (`keys.key-2.*`).
    - `duplicateKeyMaterialFails` (`keys.k2.*` = the flat values).
    - `aWrongLengthRingKeyFails`.
    - `malformedBase64FailsNamingThePropertyNotTheValue`: the value `"not*base64"` does not appear in the message.
    - `ringEntriesBindFromEnvironmentVariables`:
      - in an initializer, replace the `systemEnvironment` property source with a `SystemEnvironmentPropertySource` named `StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME`;
      - it holds `SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY`, `SERFIRA_SECURITY_PII_KEYS_K2_HMACKEY` and `SERFIRA_SECURITY_PII_ACTIVEKID=k2`;
      - the flat pair comes from properties;
      - expected: encrypts as `v2:k2:`.
- Add to `DevKeyProductionGuardTest` (prod profile, flat pair = the `PRODUCTION_*` keys):
  - `aDevEncryptionKeyAsARingMemberRefusesToStart`: `keys.k2.encryption-key` = `DEV_PII_ENCRYPTION`, with a production HMAC key → the message contains `serfira.security.pii.keys.k2.encryption-key (set SERFIRA_SECURITY_PII_KEYS_K2_ENCRYPTIONKEY)` and not the dev value.
  - `aDevHmacKeyAsARingMemberRefusesToStart`: the same check for the HMAC key.
  - `aProductionRingWithoutDevKeysStarts`.

- [ ] **Step 2: Run and watch them fail.**

  Run: `./gradlew test --tests "*PiiKeyRingConfigurationTest" --tests "*DevKeyProductionGuardTest"`

  Expected: compilation failure, then the ring cases fail.

- [ ] **Step 3: Implement.**
  - `PiiSecurityConfiguration.piiKeyMaterial(Environment)` calls `PiiSecuritySupport.configure(PiiKeyRingProperties.load(environment))`. The `PiiKeyMaterial` marker is unchanged.
  - The guard builds its `GuardedKey` list from `PiiKeyRingProperties.configuredKeys(environment)`: two entries per configured key, plus the JWT entry as today. `GuardedKey` itself is unchanged.
  - Update both classes' Javadoc for the ring.

- [ ] **Step 4: Run Step 2's command.** Expected: PASS.

- [ ] **Step 5: Commit:** `feat(t28a): configure the PII key ring and guard every member`.

### Task 3: Candidate-aware customer lookups and `PiiKeyRotationIT`

**Files:**
- Modify: `contract/infrastructure/CustomerRepository.java`, `contract/application/CustomerSearchService.java`, `contract/application/ContractCommandService.java` (`resolveCustomer` only)
- Create test: `backend/src/test/java/com/serfira/contract/PiiKeyRotationIT.java`

**Interfaces:**
- Consumes: `PiiHasher.nikCandidates` / `phoneCandidates` (Task 1), and the ring properties (Task 2).
- Produces (on `CustomerRepository`):
  - `List<Customer> findByNikHashIn(Collection<String> nikHashes)`.
  - `List<Customer> findByPhoneLookupIn(Collection<String> phoneLookups)`.
  - `default Optional<Customer> findByNikCandidates(List<String> candidates)` and `default Optional<Customer> findByPhoneCandidates(List<String> candidates)`:
    - zero rows → empty; one row → that row;
    - more than one → `IllegalStateException("customer lookup matched " + n + " rows across PII key ids")`.

- [ ] **Step 1: Write `PiiKeyRotationIT`.** The rotation must be real: two application contexts on one database.

  **Setup:**
  - `@Testcontainers`, with `@Container static PostgreSQLContainer postgres` (`postgres:16`, as in `JobRunMigrationIT`).
  - `@BeforeAll`, context A:
    - `new SpringApplicationBuilder(SerfiraCoreApplication.class).properties("spring.datasource.url=" + jdbcUrl, "spring.datasource.username=…", "spring.datasource.password=…", "server.port=0").run()`.
    - The test `application.properties` supplies the flat K1 pair.
    - In A, create two contracts through `ContractCommandService.create`, as `ContractActivationIT.createDraft` does:
      - customer X: NIK `3171012501900011`, phone `08120000011`;
      - customer Z: NIK `3171012501900033`, phone `08120000033`.
    - Insert customer Y by SQL with `nik = LegacyV1Envelope.seal(K1_AES, "3171012501900022")` and `phone = LegacyV1Envelope.seal(K1_AES, "08120000022")`. Set `nik_hash` / `phone_lookup` with `PiiHasher.hashNik` / `hashPhone` computed while A is bound. Fill the remaining `customer` columns as the V1/V2 schema requires.
    - Close A.
  - Context B: the same properties plus `serfira.security.pii.keys.k2.encryption-key`, `serfira.security.pii.keys.k2.hmac-key` and `serfira.security.pii.active-kid=k2`. Keep it for the tests.
  - `@AfterAll`: close B, then restore the test-profile ring.

  **Tests**, all in B, each with its own asset plate:
  - `customersWrittenUnderK1AreFoundByNikAndPhone`: `CustomerSearchService` finds X (`v2:k1`) and Y (`v1`) by raw NIK and by raw phone, and `fullName` matches.
  - `contractCreateReusesTheK1Customer`: create with X's NIK and phone → `select count(*) from customer` is unchanged, and the new contract's `customer_id` is X's id.
  - `aDifferentPhoneForAK1NikIsStillAConflict`: Y's NIK with phone `08129999999` → `ConflictException` with the message "NIK is already registered with a different phone number".
  - `aK1PhoneUnderANewNikIsStillAConflict`: NIK `3171012501900099` with X's phone → `ConflictException` with the message "Phone number is already registered to a different customer".
  - `newCustomersAreWrittenAsV2UnderK2`: a new customer (NIK `3171012501900044`) → its row's `nik` and `phone` start with `v2:k2:`, and `nik_hash` equals `PiiHasher.nikCandidates(nik).get(0)`.
  - `aCustomerMatchedUnderTwoKidsFailsLoudly`:
    - Insert by SQL a second customer row for Z's NIK whose `nik_hash` is the K2 candidate. Its `nik`/`phone` are `PiiSecuritySupport.encrypt` of the plaintexts, and its phone is `08120000055` with its K2 hash.
    - `findByRawNik("3171012501900033")` → `IllegalStateException`.
    - Delete that row afterwards.

- [ ] **Step 2: Run it and watch it fail.**

  Run: `./gradlew test --tests "*PiiKeyRotationIT"`

  Expected: `customersWrittenUnderK1AreFoundByNikAndPhone` and the reuse and conflict tests fail, because the lookups use only the K2 hash. `newCustomersAreWrittenAsV2UnderK2` passes already.

- [ ] **Step 3: Implement.**
  - `CustomerSearchService` uses `findByNikCandidates(PiiHasher.nikCandidates(raw))` and the phone equivalent.
  - `resolveCustomer`:
    - `byNik = customers.findByNikCandidates(nikCandidates)`;
    - the phone check is `!phoneCandidates.contains(existing.getPhoneLookup())` → 409;
    - the phone-owner check is `findByPhoneCandidates(phoneCandidates).isPresent()`;
    - a new customer still stores `nikCandidates.get(0)` / `phoneCandidates.get(0)`, which equal `hashNik` / `hashPhone`.
  - Update the Javadoc of `CustomerSearchService`, `CustomerRepository` and `resolveCustomer`: lookups match every ring key.

- [ ] **Step 4: Run Step 2's command, plus the existing customer and contract suites.**

  Run: `./gradlew test --tests "*PiiKeyRotationIT" --tests "*CustomerPiiIT" --tests "*ContractApiIT" --tests "*ContractActivationIT" --tests "*ContractPersistenceIT"`

  Expected: PASS.

- [ ] **Step 5: Commit:** `feat(t28a): match customers under every PII ring key`.

### Task 4: ADR-025, docs and close-out

**Files:**
- Create: `docs/adr/ADR-025-pii-key-ring.md`
- Modify: `docs/adr/ADR-004-at-rest-pii-protection.md`, `docs/04_GAPS_ADDENDUM.md` §9, `GLOSSARY.md`, `README.md`, `docs/tasks.md`, `docs/PROGRESS.md`

- [ ] **Step 1: ADR-025**, in the style of ADR-027 (Status, Date 2026-11-26, Deciders, Relates to, Context, Decision D1…, Consequences, Alternatives).
  - **D1–D7:** Decisions 1–7 above.
  - **Runbook:**
    1. Add `keys.<new>.*` everywhere and deploy, still on the old active `kid`. Every instance can now read the new key before any instance writes with it.
    2. Switch `active-kid` and deploy.
    3. Run T28b.
    4. Retire the old key only by T28b's retention rule.
  - **Warning, prominent:** do not do step 2 before T28b ships. Switching the active key rotates the HMAC key too, and stored idempotency `request_hash` values would then make replays `409`.
  - **Alternatives:**
    - AAD-bound `kid` (redundant given the distinct-material rule);
    - a separate HMAC ring (rotation moves both keys together, per the spec);
    - storing the `kid` in a column (needs a migration; the envelope already carries it).

- [ ] **Step 2: Other docs.**
  - ADR-004: the consequence line on the `v1:` envelope (line 53) now says rotation exists, citing ADR-025. Add a `v2` mention to item 5 (key management).
  - Addendum §9: the key lines gain the ring and the do-not-rotate-before-T28b rule. Write them in that section's language (Indonesian).
  - `GLOSSARY.md`: add a "PII key ring" entry (`kid`, active, legacy, candidates) next to "Customer".
  - README "Fail-fast secrets": keep the three existing variables. Add the optional ring variables (`SERFIRA_SECURITY_PII_KEYS_<KID>_ENCRYPTIONKEY` / `_HMACKEY`, `SERFIRA_SECURITY_PII_ACTIVEKID`, `SERFIRA_SECURITY_PII_LEGACYKID`), a link to ADR-025, and the warning not to switch the active key until T28b.

- [ ] **Step 3: Full check.**

  Run: `cd backend && ./gradlew check --rerun-tasks`

  Expected: BUILD SUCCESSFUL, with 0 failures. Record the suite and test counts from `build/test-results/test/*.xml`.

- [ ] **Step 4: Close-out.**
  - In `docs/tasks.md`:
    - T28a becomes `Status: DONE`, with an implementation note: the decisions, the two rulings (the prefix assertions, and keeping the single-hash finders for tests), and the verified counts;
    - add the Completed Work row `T28a | CR-11 PII key ring: v2:<kid> envelope, candidate lookups, ring validation + guard | ADR-025, t28a-pii-key-ring`;
    - update the current-state bullet;
    - mark it DONE in the Sprint 8 scope list.
  - `docs/PROGRESS.md`: tick T28a.

- [ ] **Step 5: Commit** `docs(t28a): ADR-025 key ring, runbook and close-out`, push `t28a-pii-key-ring`, and open a PR to `main`.
