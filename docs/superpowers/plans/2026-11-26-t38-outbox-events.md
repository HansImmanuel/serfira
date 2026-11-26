# T38 — Transactional Outbox, Six Domain Events and a Log-Based Publisher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Six business transitions each write exactly one PII-free event into `outbox_events` inside their own transaction, and a ShedLock-guarded publisher drains them to the structured log and marks them `SENT`.

**Architecture:** A reusable `shared.outbox.OutboxWriter` (JPA, `Propagation.MANDATORY`) is called from the existing transactional services at the exact point each transition happens; no controller writes events. A separate publisher (scheduler → locked job → per-batch `REQUIRES_NEW` service → `OutboxSink`) claims `PENDING` rows with `FOR UPDATE SKIP LOCKED`, hands each one to a logging sink, and records `SENT`, a retry or `FAILED`. A future broker replaces only the `OutboxSink` bean.

**Tech Stack:** Java 21, Spring Boot 4 (Jackson 3 `tools.jackson`, Hibernate 7), PostgreSQL 16 (Testcontainers), ShedLock 7.10.1, JUnit 5 + AssertJ + Mockito.

**Spec:** `docs/tasks.md` § "T38 — G6" (scope, rules, acceptance, tests, risks). Business rules it cites: `02_TECH_SPEC.md` §2.7, `03_DOMAIN_MODEL.md` §1.15, `04_GAPS_ADDENDUM.md` §9, ADR-011, ADR-017, ADR-018, ADR-019, ADR-020.

## Decisions taken at planning (each goes into ADR-027)

1. **Event order inside one transaction is pinned by the id.** Two events written in one transaction (payment → `ContractClosed`, settlement → `ContractClosed`) get the same `created_at` from `Clock.now()`, and V1's default `id` is a random UUID, so `ORDER BY created_at, id` would order them by chance. The writer assigns the id itself: a **UUIDv7, monotonic per process** (`OutboxEventIds`). PostgreSQL compares `uuid` byte-wise, so `(created_at, id)` is emission order. No migration.
2. **Emission points.** `PaymentReceived` right after the allocations flush and *before* `applyPaymentResolution` (which may close the contract), and `SettlementExecuted` right after the settlement's allocations and credit applications are saved and *before* `closeBySettlement`, so "X, then `ContractClosed`" holds. Every other event is written at the end of its use case, after the ledger post.
3. **`ContractClosed` has exactly two sites:** the `if (contract.close(MATURITY, …))` branch in `InstallmentReceivableService.closeWhenEveryInstallmentIsPaid` and `if (closed)` in `SettlementCloseService`. The first covers payment, credit application **and** a penalty waiver that clears the last amount (`InstallmentStatusRecomputeService` reuses it), which the spec's table does not list but "every `close()` that returns `true`" includes.
4. **`job_run` policy:** the publisher writes one `job_run` row named `outbox-publisher` **only for a tick in which at least one event failed** (a retry or a `FAILED`): `records_processed` = events sent, `records_failed` = events that failed, so `JobRun.complete` marks it `FAILED`. Idle and all-success ticks write nothing; the per-event log line and the `SENT` row are their record.
5. **Schedule properties follow the existing `serfira.jobs.<name>.*` convention** (cron `-` disables, as the other two jobs do in tests).

## Global Constraints

- Branch `t38-outbox-events` off `main`; claim T38 `IN PROGRESS` in `docs/tasks.md` first; no `Co-Authored-By` / "Generated with" lines in any commit or PR.
- **No new migration:** `outbox_events` and `idx_outbox_status_created` exist in V1. Retention of `SENT` rows is out of scope → a Deferred row.
- Payloads: allowed `schema_version: 1`, ids, business numbers, amounts, enums, business dates/instants. **Never** customer name, NIK, phone, address, or any free text (no write-off reason, no void reason). Event id = outbox row id.
- Money in payloads is a decimal string (ADR-024): serialize with the application Jackson 3 `ObjectMapper` (snake_case, `BigDecimalStringModule`).
- Payloads are dedicated records implementing `OutboxPayload`; never a response DTO.
- `AccountingInvariantsIT`, `EndpointRoleMatrixIT`, `./gradlew check` stay green; `node scripts/demo.mjs` still exits 0.
- Test properties set `serfira.jobs.outbox-publisher.cron=-` so no background tick flips rows during an IT.

## Review Focus

1. **Two events in one transaction share `created_at`** — the publisher must still emit them in write order (`SettlementExecuted` before `ContractClosed`). Pinned by `OutboxEventIdsTest` and `settlementWritesSettlementExecutedThenContractClosed` (Task 4) reading rows `ORDER BY created_at, id`.
2. **A penalty waiver that clears the last amount closes the contract** — it must emit `ContractClosed` too. Pinned by `InstallmentReceivableServiceOutboxTest` (Task 3) on the single site.
3. **Voiding an already-`VOIDED` payment with a fresh key** is a 200 no-op and must emit nothing. Pinned by `voidingAnAlreadyVoidedPaymentWritesNoSecondEvent` (Task 2).
4. **Activating an already-`ACTIVE` contract** returns 200 without writing and must emit nothing. Pinned by `repeatingActivationWritesNoSecondEvent` (Task 3).
5. **One failing event in a batch** must not roll back the rows that were sent in the same tick. Pinned by `aFailingEventDoesNotUndoTheRestOfTheBatch` (Task 5).

---

### Task 1: Outbox core — ids, entity, writer

**Files:**
- Modify: `docs/tasks.md` (T38 `Status: IN PROGRESS`, snapshot line) — claim commit together with this plan file
- Create: `backend/src/main/java/com/serfira/shared/outbox/OutboxEventIds.java`
- Create: `.../shared/outbox/OutboxEvent.java`, `OutboxEventStatus.java`, `OutboxAggregateType.java`, `OutboxEventType.java`, `OutboxPayload.java`, `OutboxEventRepository.java`, `OutboxWriter.java`
- Test: `backend/src/test/java/com/serfira/shared/outbox/OutboxEventIdsTest.java`, `OutboxWriterTest.java`, `OutboxWriterIT.java`

**Interfaces:**
- Produces:
  - `enum OutboxAggregateType { PAYMENT, CONTRACT, SETTLEMENT }`
  - `enum OutboxEventType { PaymentReceived, PaymentVoided, ContractActivated, ContractClosed, ContractWrittenOff, SettlementExecuted }` (stored with `name()`, so the column value is e.g. `PaymentReceived`)
  - `enum OutboxEventStatus { PENDING, SENT, FAILED }`
  - `interface OutboxPayload { int SCHEMA_VERSION = 1; }` — marker; each record has a first component `int schemaVersion` set to `OutboxPayload.SCHEMA_VERSION`
  - `OutboxEventIds(Clock clock)` → `synchronized UUID next()`
  - `OutboxEvent extends Auditable` (JPA, table `outbox_events`, assigned `@Id UUID id`, `@Enumerated(STRING)` type/aggregate/status, `@JdbcTypeCode(SqlTypes.JSON) String payloadJson`, `Integer retryCount`, `OffsetDateTime sentAt`) with `markSent(OffsetDateTime)`, `recordFailure(int maxRetries) → boolean failedTerminally`, getters
  - `OutboxEventRepository extends JpaRepository<OutboxEvent, UUID>` with `List<OutboxEvent> claimPending(int limit)` (native, Task 5 uses it) and `List<OutboxEvent> findByAggregateIdOrderByCreatedAtAscIdAsc(UUID)` for tests
  - `OutboxWriter.append(OutboxAggregateType aggregateType, UUID aggregateId, OutboxEventType eventType, OutboxPayload payload) → UUID` — `@Transactional(propagation = MANDATORY)`, persists via `EntityManager.persist`

- [ ] **Step 1: Branch and claim.** `git switch -c t38-outbox-events main`; set T38 `Status: IN PROGRESS` and the snapshot line in `docs/tasks.md`; commit this plan with it: `docs(t38): plan and claim T38`.

- [ ] **Step 2: Write `OutboxEventIdsTest`**

```java
@Test void idsAreVersion7WithTheRfcVariant()            // id.version() == 7, id.variant() == 2
@Test void idsUnderAFrozenClockStrictlyIncrease()      // FixedClock; 10_000 ids; each id.toString() > previous (lexical == byte order)
@Test void idsKeepIncreasingWhenTheClockMovesBackwards()// next(); clock.set(an hour earlier); next() > previous
@Test void theTimestampIsTheClockMillis()              // (msb >>> 16) == clock.instant().toEpochMilli() for the first id
```

- [ ] **Step 3: Run it, watch it fail** — `./gradlew test --tests "*OutboxEventIdsTest"` → compile failure (class missing).

- [ ] **Step 4: Implement `OutboxEventIds`.** Layout: 48-bit ms | version `0111` | 12-bit counter | variant `10` | 62 random bits (`ThreadLocalRandom`). State `lastMs`, `counter`: `ms = max(clock ms, lastMs)`; same ms → `counter + 1`; counter past `0xFFF` → `ms = lastMs + 1, counter = 0`; new ms → `counter = 0`. Register it as a `@Component`.

- [ ] **Step 5: Run it, watch it pass.**

- [ ] **Step 6: Write `OutboxWriterTest`** (plain unit: mocked `EntityManager`, a real `JsonMapper` built with `PropertyNamingStrategies.SNAKE_CASE` and the app's `BigDecimalStringModule`, `OutboxEventIds` on a `FixedClock`; a test-local `record SamplePayload(int schemaVersion, UUID paymentId, BigDecimal amount, OffsetDateTime paidAt) implements OutboxPayload`):
  - `appendPersistsOnePendingRowWithTheGivenTypeAndAggregate` — captured entity: status `PENDING`, `retryCount` 0, `sentAt` null, returned id == entity id.
  - `moneyIsADecimalStringAndKeysAreSnakeCase` — `payloadJson` contains `"amount":"4769959.90"` and `"payment_id"` and `"schema_version":1`.
  - `aNullPayloadOrTypeIsRejected` — `NullPointerException`, nothing persisted.

- [ ] **Step 7: Run it, watch it fail; implement `OutboxEvent`, the enums, `OutboxPayload`, `OutboxEventRepository` (no `claimPending` yet) and `OutboxWriter`; run it, watch it pass.** The writer injects `tools.jackson.databind.ObjectMapper` (the Boot bean), `EntityManager`, `OutboxEventIds`.

- [ ] **Step 8: Write `OutboxWriterIT`** (`@SpringBootTest`, `@Import(TestcontainersConfiguration.class)`, truncate `outbox_events` before/after):
  - `appendOutsideATransactionIsRejected` — `assertThatThrownBy(() -> writer.append(...)).isInstanceOf(IllegalTransactionStateException.class)`; 0 rows.
  - `aRolledBackTransactionDropsItsEvent` — `TransactionTemplate` that appends then throws; 0 rows.
  - `aCommittedAppendStoresJsonbAndTheAuditActor` — inside `auditContext.runAs(actor, …)` + `TransactionTemplate`: row has `status = 'PENDING'`, `jsonb_typeof(payload_json) = 'object'`, `created_by = actor`.

- [ ] **Step 9: Run it, watch it pass**; add `serfira.jobs.outbox-publisher.cron=-` to `backend/src/test/resources/application.properties` now (Task 5 reads it).

- [ ] **Step 10: Commit** — `feat(t38): outbox writer with time-ordered event ids`.

---

### Task 2: Payment events — `PaymentReceived`, `PaymentVoided`

**Files:**
- Create: `backend/src/main/java/com/serfira/payment/application/event/PaymentReceivedPayload.java`, `PaymentVoidedPayload.java`
- Modify: `payment/application/PaymentApplicationService.java` (`receive`), `payment/application/PaymentVoidApplicationService.java` (`reverse`), `contract/application/PaymentVoidPort.java:70` + `contract/application/PaymentVoidService.java:74,129` (return `boolean reopened`)
- Test: `backend/src/test/java/com/serfira/shared/outbox/OutboxEventsIT.java` (created here, grown in Tasks 3–4)

**Interfaces:**
- Consumes: `OutboxWriter.append(...)`, `OutboxEventRepository.findByAggregateIdOrderByCreatedAtAscIdAsc`
- Produces:
  - `record PaymentReceivedPayload(int schemaVersion, UUID paymentId, String paymentNo, UUID contractId, String contractNo, BigDecimal amount, PaymentChannel channel, OffsetDateTime paidAt, BigDecimal excessAmount) implements OutboxPayload`
  - `record PaymentVoidedPayload(int schemaVersion, UUID paymentId, String paymentNo, UUID contractId, BigDecimal amount, OffsetDateTime voidedAt, boolean contractReopened) implements OutboxPayload`
  - `PaymentVoidPort.applyVoidRecomputation(...)` now returns `boolean` — `true` only when `reopenFromMaturity()` returned `true`.
  - `OutboxEventsIT` helpers (private, reused by Tasks 3–4): `createAndActivate(int tenorMonths) → UUID contractId` through `POST /contracts` + `/activate`, `pay(contractId, amount) → JsonNode`, `rows(aggregateId) → List<Map<String,Object>>` (`select event_type, aggregate_type, payload_json::text …` ordered by `created_at, id`), `payload(row) → JsonNode`. Seed customer/actor and clock exactly as `PaymentVoidIT` does (`PaymentClockTestConfiguration`, business date 2026-02-28); customer `full_name`, `nik`, `phone` (and address, if the request has one) are distinctive constants kept as fields for the PII scan in Task 4.

- [ ] **Step 1: Write the failing cases in `OutboxEventsIT`**
  - `aPaymentWritesOnePaymentReceived` — 1 row for the payment id: `event_type = PaymentReceived`, `aggregate_type = PAYMENT`; payload keys exactly `{schema_version, payment_id, payment_no, contract_id, contract_no, amount, channel, paid_at, excess_amount}`; `amount` is a JSON string equal to the amount paid (`"1573333.33"` with `PaymentVoidIT`'s contract terms).
  - `aReplayedPaymentKeyWritesNoSecondRow` — same `Idempotency-Key` twice → still 1 row.
  - `aRejectedPaymentWritesNothing` — payment to a DRAFT contract → 409, 0 `PaymentReceived` rows for that contract id (query by `payload_json->>'contract_id'`).
  - `aPaymentThatDoesNotCloseTheContractWritesNoContractClosed` — 12-month contract, pay period 1 → 0 `ContractClosed` rows for the contract.
  - `aVoidWritesPaymentVoidedWithoutTheReason` — void with reason `"VOID-REASON-SENTINEL"` → 1 `PaymentVoided` row, keys exactly `{schema_version, payment_id, payment_no, contract_id, amount, voided_at, contract_reopened}`, `contract_reopened = false`, payload text does not contain the sentinel.
  - `aVoidThatReopensAMaturityCloseSaysSo` — 1-month contract, pay it in full (MATURITY close), void → `contract_reopened = true`.
  - `voidingAnAlreadyVoidedPaymentWritesNoSecondEvent` — void, then void again with a **fresh** key → 200 and still 1 `PaymentVoided` row.

- [ ] **Step 2: Run** `./gradlew test --tests "*OutboxEventsIT"` → FAIL (0 rows found).

- [ ] **Step 3: Implement.** `receive`: append `PaymentReceived` (aggregate `PAYMENT`, `payment.getId()`) immediately after `allocations.flush()`, before `applyPaymentResolution` (Decision 2). `PaymentVoidService.applyVoidRecomputation` returns whether `reopenIfNoLongerMatured` reopened (make that helper return `boolean`); `reverse`: capture it, append `PaymentVoided` after `ledger.postReversal`. The already-`VOIDED` early return stays untouched (no event).

- [ ] **Step 4: Run** `OutboxEventsIT`, `PaymentVoidIT`, `PaymentApiIT`, `PaymentIdempotencyIT` → PASS.

- [ ] **Step 5: Commit** — `feat(t38): emit PaymentReceived and PaymentVoided`.

---

### Task 3: Contract events — `ContractActivated`, `ContractClosed`, `ContractWrittenOff`

**Files:**
- Create: `backend/src/main/java/com/serfira/contract/application/event/ContractActivatedPayload.java`, `ContractClosedPayload.java`, `ContractWrittenOffPayload.java`
- Modify: `contract/application/ContractCommandService.java` (`activate`, after the ledger post), `contract/application/InstallmentReceivableService.java:142`, `contract/application/SettlementCloseService.java:89`, `contract/application/ContractWriteOffCommandService.java` (`terminate`, before the return)
- Test: `backend/src/test/java/com/serfira/contract/application/InstallmentReceivableServiceOutboxTest.java`; more cases in `OutboxEventsIT`

**Interfaces:**
- Consumes: Task 1 writer; Task 2 `OutboxEventsIT` helpers
- Produces:
  - `record ContractActivatedPayload(int schemaVersion, UUID contractId, String contractNo, LocalDate startDate, BigDecimal principal, int tenorMonths, InterestScheme interestScheme) implements OutboxPayload`
  - `record ContractClosedPayload(int schemaVersion, UUID contractId, String contractNo, ClosedReason closedReason, OffsetDateTime closedAt) implements OutboxPayload` — built from the contract *after* `close()`
  - `record ContractWrittenOffPayload(int schemaVersion, UUID contractId, String contractNo, OffsetDateTime writtenOffAt, BigDecimal totalWrittenOff, int installmentCount) implements OutboxPayload`

- [ ] **Step 1: Write `InstallmentReceivableServiceOutboxTest`** (Mockito: `ContractRepository`, `InstallmentRepository`, `OutboxWriter`; real `Contract`/`Installment` fixtures or mocks of them):
  - `closingTheLastPaidInstallmentWritesContractClosed` — all PAID, ACTIVE contract → `verify(outbox).append(CONTRACT, contractId, ContractClosed, payload with closedReason MATURITY)`.
  - `anAlreadyClosedContractWritesNothing` — contract not active → `verifyNoInteractions(outbox)`.
  - `anUnpaidScheduleWritesNothing`.
  (This one site serves payment, credit application and the penalty-waiver recompute — Decision 3, Review Focus 2.)

- [ ] **Step 2: Write the failing IT cases**
  - `activationWritesOneContractActivated` — keys exactly `{schema_version, contract_id, contract_no, start_date, principal, tenor_months, interest_scheme}`.
  - `repeatingActivationWritesNoSecondEvent` — activate twice → 1 row.
  - `aPaymentThatMaturesTheContractWritesPaymentReceivedThenContractClosed` — 1-month contract paid in full → rows for the contract id ordered `created_at, id`: `PaymentReceived` is written before `ContractClosed`; `ContractClosed` keys exactly `{schema_version, contract_id, contract_no, closed_reason, closed_at}`, `closed_reason = MATURITY`.
  - `aCreditApplicationThatMaturesTheContractWritesContractClosed` — 2-month contract; on the first due date pay installment 1's total plus installment 2's total (installment 1 → `PAID`, the rest lands as EXCESS credit, ADR-009; the payment writes no `ContractClosed`); move the clock to installment 2's due date; `POST /contracts/{id}/credit/apply` for installment 2's total → exactly 1 `ContractClosed`, `MATURITY`. Copy the apply request shape from `ContractCreditIT.seedAppliableCredit` (line ~501); reset the clock in `@AfterEach`.
  - `writeOffWritesContractWrittenOffWithoutTheReason` — reason `"WRITE-OFF-REASON-SENTINEL"` → 1 row, keys exactly `{schema_version, contract_id, contract_no, written_off_at, total_written_off, installment_count}`, sentinel absent; no `ContractClosed` row.
  - `aReplayedWriteOffKeyWritesNoSecondRow`.

- [ ] **Step 3: Run** → FAIL.

- [ ] **Step 4: Implement** the four emission sites (Decision 2–3). `SettlementCloseService`: append only when `closed` is `true`.

- [ ] **Step 5: Run** the unit test, `OutboxEventsIT`, `ContractActivationIT`, `ContractWriteOffIT`, `ContractCreditIT`, `PenaltyAdjustmentIT` → PASS.

- [ ] **Step 6: Commit** — `feat(t38): emit ContractActivated, ContractClosed and ContractWrittenOff`.

---

### Task 4: `SettlementExecuted`, ordering and the PII scan

**Files:**
- Create: `backend/src/main/java/com/serfira/settlement/application/event/SettlementExecutedPayload.java`
- Modify: `settlement/application/SettlementExecutionCommandService.java` (between the credit-application `saveAll` ~line 258 and `settlementClose.closeBySettlement` ~line 266)
- Test: `OutboxEventsIT`

**Interfaces:**
- Produces: `record SettlementExecutedPayload(int schemaVersion, UUID settlementId, String settlementNo, UUID contractId, UUID quoteId, BigDecimal cashReceived, BigDecimal creditUsed, BigDecimal rebateAmount, BigDecimal adminFee, OffsetDateTime executedAt) implements OutboxPayload`

- [ ] **Step 1: Write the failing cases**
  - `settlementWritesSettlementExecutedThenContractClosed` — quote + execute (request shapes from `SettlementExecutionIT`) → exactly two new rows in this transaction, read with `ORDER BY created_at, id`: `SettlementExecuted` (aggregate `SETTLEMENT`, the settlement id) then `ContractClosed` (`closed_reason = SETTLEMENT`); the two `created_at` values are equal (proves the id breaks the tie — Review Focus 1).
  - `aReplayedSettlementKeyWritesNoSecondRow`.
  - `noPayloadCarriesCustomerDataOrFreeText` — runs last over **all** rows the class created: for every `payload_json::text`, assert it contains none of the seeded `full_name`, `nik`, `phone` (and address) constants, neither sentinel reason, and that no key is in `{full_name, name, nik, phone, address, reason, void_reason, write_off_reason, note}`. Make it self-contained: drive one flow of each type inside the test so it does not depend on test order.

- [ ] **Step 2: Run** → FAIL. **Step 3: Implement** the emission (Decision 2). **Step 4: Run** `OutboxEventsIT`, `SettlementExecutionIT`, `AccountingInvariantsIT` → PASS.

- [ ] **Step 5: Commit** — `feat(t38): emit SettlementExecuted before its ContractClosed`.

---

### Task 5: Publisher — sink seam, batch, locked job

**Files:**
- Create in `backend/src/main/java/com/serfira/shared/outbox/`: `OutboxSink.java`, `LoggingOutboxSink.java`, `OutboxBatchPublisher.java`, `OutboxBatchResult.java`, `OutboxPublisherJob.java`, `LockedOutboxPublisherJob.java`, `OutboxPublisherScheduler.java`
- Modify: `OutboxEventRepository.java` (`claimPending`), `backend/src/main/resources/application.properties`
- Test: `backend/src/test/java/com/serfira/shared/outbox/OutboxBatchPublisherTest.java`, `OutboxPublisherJobTest.java`, `OutboxPublisherIT.java`

**Interfaces:**
- Consumes: `OutboxEvent.markSent/recordFailure`, `JobRunService.start/complete/failHard`, `AuditContext.runAs`
- Produces:
  - `interface OutboxSink { void publish(OutboxEvent event); }` — throws on failure; the broker seam
  - `LoggingOutboxSink` (`@Component`): one INFO line per event via the SLF4J fluent API — message `"Published outbox event"`, key-values `event_id`, `event_type`, `aggregate_type`, `aggregate_id`, `payload` (the raw JSON text)
  - `@Query(value = "select * from outbox_events where status = 'PENDING' order by created_at, id limit :limit for update skip locked", nativeQuery = true) List<OutboxEvent> claimPending(@Param("limit") int limit)`
  - `record OutboxBatchResult(int claimed, int sent, int failed)` (`failed` counts both retried and terminally failed events)
  - `OutboxBatchPublisher.publishBatch(int limit) → OutboxBatchResult` — `@Transactional(REQUIRES_NEW)`; per event: `sink.publish` → `markSent(clock.now())`; on `RuntimeException` → `recordFailure(maxRetries)` (WARN log with id and type, never the payload) and continue
  - `OutboxPublisherJob.run()` — under `AuditContext.SYSTEM_USER_ID`: loop `publishBatch(batchSize)` until `claimed < batchSize`; sum results; if total `failed > 0` → `jobRuns.start("outbox-publisher", clock.today())` then `complete(id, sent, failed)` (Decision 4); otherwise no `job_run`
  - `LockedOutboxPublisherJob.run()` — `@SchedulerLock(name = "outbox-publisher", lockAtMostFor = "${serfira.jobs.outbox-publisher.lock-at-most-for:PT1M}")`, `LockAssert.assertLocked()`
  - `OutboxPublisherScheduler` — `@Scheduled(cron = "${serfira.jobs.outbox-publisher.cron}", zone = "Asia/Jakarta")`
  - Properties (main): `serfira.jobs.outbox-publisher.cron=*/5 * * * * *`, `…lock-at-most-for=PT1M`, `…batch-size=100`, `…max-retries=10`, each with a one-line comment like the existing job blocks. Constructor guards: `batch-size >= 1`, `max-retries >= 1`.
  - `OutboxEvent.recordFailure(maxRetries)`: `retryCount++`; `retryCount >= maxRetries` → status `FAILED`, return `true`; else stays `PENDING`.

- [ ] **Step 1: Write the unit tests** (`OutboxBatchPublisherTest` with a mocked repository returning built `OutboxEvent`s and a fake sink; `OutboxPublisherJobTest` with a mocked `OutboxBatchPublisher` and `JobRunService`):
  - `aPublishedEventIsMarkedSentWithTheClockInstant`
  - `aSinkFailureIncrementsRetryAndLeavesThePendingRow`
  - `theLastAllowedFailureMarksTheRowFailed` — max 3, third failure → `FAILED`
  - `aFailingEventDoesNotUndoTheRestOfTheBatch` — 3 events, sink fails on the 2nd → result `(3, 2, 1)`, events 1 and 3 `SENT` (Review Focus 5)
  - `anIdleTickWritesNoJobRun` — first batch claims 0 → `verifyNoInteractions(jobRuns)`
  - `anAllSuccessTickWritesNoJobRun`
  - `aTickWithFailuresRecordsOneJobRun` — `start("outbox-publisher", …)` + `complete(id, sent, failed)`
  - `theJobDrainsFullBatchesUntilAShortOne` — results `(100,100,0)`, `(40,40,0)` with batch 100 → two calls
  - `nonPositiveBatchSizeOrMaxRetriesIsRejected`

- [ ] **Step 2: Run → FAIL; implement; run → PASS.**

- [ ] **Step 3: Write `OutboxPublisherIT`** (`@SpringBootTest`, Testcontainers, `@TestPropertySource(properties = "serfira.jobs.outbox-publisher.max-retries=3")`, a `@TestConfiguration` `@Primary OutboxSink` that records published ids, can fail for chosen ids, and can block on a `CountDownLatch`; rows seeded through `OutboxWriter` in a `TransactionTemplate`; truncate `outbox_events, job_run` around each test):
  - `aTickDrainsEveryPendingRowInOrderAndMarksThemSent` — 5 rows → sink saw them in `(created_at, id)` order; all `SENT` with `sent_at`; a second tick publishes nothing.
  - `sentRowsAreLeftAlone` — a pre-`SENT` row is never re-published.
  - `twoConcurrentBatchesNeverPublishTheSameRow` — 4 rows; thread A calls `publishBatch(2)` and its sink blocks on a latch after the first event; thread B calls `publishBatch(10)` and must return the other 2 without waiting; release A; the union of published ids has 4 distinct elements and no duplicates.
  - `aRowFailingEveryTickEndsFailedAfterMaxRetries` — 3 ticks via `LockedOutboxPublisherJob.run()` → `status = 'FAILED'`, `retry_count = 3`, and 3 `job_run` rows `outbox-publisher` with `status = 'FAILED'`.
  - `theLoggingSinkWritesOneStructuredLinePerEvent` — swap in the real `LoggingOutboxSink` (call it directly on a persisted event) with a Logback `ListAppender` as in `PiiLoggingIT`: one INFO event whose key-value pairs carry `event_id`, `event_type`, `aggregate_id`, `payload`.

- [ ] **Step 4: Run** `OutboxPublisherIT` → PASS.

- [ ] **Step 5: Commit** — `feat(t38): log-based outbox publisher with ShedLock, retries and FAILED`.

---

### Task 6: ADR-027, docs, close-out and full verification

**Files:**
- Create: `docs/adr/ADR-027-outbox-events-and-publisher.md`
- Modify: `docs/02_TECH_SPEC.md` §2.7 (Indonesian, matching the section), `docs/03_DOMAIN_MODEL.md` §1.15 (`outbox_events` line → point to the ADR-027 catalogue), `GLOSSARY.md` (`## Operational`: **Outbox event**, **Outbox publisher**), `docs/tasks.md` (T38 DONE + implementation note, Completed Work row, Deferred row "retention of `SENT` outbox rows", snapshot/test counts, Sprint 8 scope line), `docs/PROGRESS.md` (T38 tick, Indonesian), `README.md` (ADR count, if it lists one)

- [ ] **Step 1: Write ADR-027** in the house format of ADR-026: context (V1 table, no producers, Kafka deferred), decisions — the six-event catalogue with aggregate, emission site and exact payload keys (copy the record components from Tasks 2–4, snake_case); the PII/free-text rule; Decisions 1–4 of this plan; at-least-once delivery (a crash after the log line and before the `SENT` commit re-emits; consumers deduplicate by event id); ordering is best-effort once a row is retried; the `OutboxSink` seam for a broker; consequences; deferred retention.

- [ ] **Step 2: Update §2.7, §1.15 and GLOSSARY** — §2.7: publisher now drains to the structured log (ADR-027), Kafka later behind `OutboxSink`; remove "ditunda ke Fase 3".

- [ ] **Step 3: Full verification** (record exact counts):
  - `cd backend && ./gradlew check --rerun-tasks` → 0 failed
  - `cd frontend && npm test` (unchanged code, sanity) → 0 failed
  - `node --test "scripts/demo/*.test.mjs"` and `node scripts/demo.mjs --reset` → exit 0; then `docker compose logs app | grep "Published outbox event"` shows the demo's events (≥ 2 `ContractActivated`, 3 `PaymentReceived`) and no customer name/NIK/phone.

- [ ] **Step 4: Close T38** in `docs/tasks.md` and `docs/PROGRESS.md` with the verified counts; commit `docs(t38): ADR-027, spec and glossary updates; close T38`.

- [ ] **Step 5: Final review, push and PR** — whole-branch review; push `t38-outbox-events`; open a PR to `main` (no attribution lines). Do not merge.
