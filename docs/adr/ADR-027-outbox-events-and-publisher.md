# ADR-027: Outbox Events and the Log-Based Publisher (T38)

- **Status:** Accepted; implemented by T38
- **Date:** 2026-11-26
- **Deciders:** Hans (solo developer)
- **Relates to:** `02_TECH_SPEC.md` §2.7, `03_DOMAIN_MODEL.md` §1.15, `04_GAPS_ADDENDUM.md` §9 (no PII in
  logs), ADR-011 (MATURITY close), ADR-017 (idempotent replay), ADR-018 (settlement and write-off), ADR-019
  (penalty adjustment), ADR-020 (payment void), ADR-024 (exact decimal wire format), T20 (ECS logging).

---

## Context

`outbox_events` has existed since V1, but nothing wrote to it, and TS §2.7 deferred the publisher (and
Kafka) to Phase 3. Phase 3 needs domain events that are durable and observable without a broker: written
in the same transaction as the change they report, and delivered by a separate process that a broker can
later take over.

Three things had to be decided that the table alone does not: which transitions are events and what they
carry, how two events written in one transaction stay in order, and how the publisher behaves on failure.

---

## Decision

### D1 — Event catalogue

Each event is written only when its transition is actually performed. Payload keys are snake_case; every
payload starts with `schema_version: 1`. The event id is the outbox row id.

| Event | Aggregate | Written by | Payload keys |
|---|---|---|---|
| `PaymentReceived` | `PAYMENT` | `PaymentApplicationService.receive`, after the allocations flush and before the resolution | `payment_id, payment_no, contract_id, contract_no, amount, channel, paid_at, excess_amount` |
| `PaymentVoided` | `PAYMENT` | `PaymentVoidApplicationService.reverse`, after the reversal journal | `payment_id, payment_no, contract_id, amount, voided_at, contract_reopened` |
| `ContractActivated` | `CONTRACT` | `ContractCommandService.activate`, after the disbursement journal | `contract_id, contract_no, start_date, principal, tenor_months, interest_scheme` |
| `ContractClosed` | `CONTRACT` | `InstallmentReceivableService.closeWhenEveryInstallmentIsPaid` (MATURITY) and `SettlementCloseService` (SETTLEMENT), only when `Contract.close(...)` returns `true` | `contract_id, contract_no, closed_reason, closed_at` |
| `ContractWrittenOff` | `CONTRACT` | `ContractWriteOffCommandService`, after the write-off journal | `contract_id, contract_no, written_off_at, total_written_off, installment_count` |
| `SettlementExecuted` | `SETTLEMENT` | `SettlementExecutionCommandService`, before `closeBySettlement` | `settlement_id, settlement_no, contract_id, quote_id, cash_received, credit_used, rebate_amount, admin_fee, executed_at` |

- The MATURITY close has one site, so one emission point covers a closing payment, a closing credit
  application, and a penalty waiver that clears the last amount owed (`InstallmentStatusRecomputeService`
  reuses the same method). The waiver case is defensive: the allocation waterfall pays penalty first, so no
  API flow leaves penalty as the only amount owed today (see `PenaltyAdjustmentIT`). It is pinned by a unit
  test on the shared site, not end to end.
- A write-off emits `ContractWrittenOff` only; it is not a `close()`.
- An idempotent replay (ADR-017) returns the stored response without running the operation, so it writes
  nothing. A repeat activation of an ACTIVE contract, and a void of an already-VOIDED payment, are no-ops
  and write nothing. A transaction that rolls back drops its event. The retrying wrappers re-run in a fresh
  transaction, so a conflict-retry still yields one event.

### D2 — Payload rules

- **Allowed:** ids, business numbers, amounts (decimal strings, ADR-024), enums, business dates and
  instants.
- **Never:** the customer's name, NIK, phone or address, and no free text — no void reason, no write-off
  reason. The publisher writes the payload to the log, so Addendum §9 makes this a hard rule.
- Payloads are dedicated records implementing `OutboxPayload`, never response DTOs: the write-off response,
  for example, carries the free-text reason.

### D3 — Writer

`shared.outbox.OutboxWriter.append(aggregateType, aggregateId, eventType, payload)` runs with
`Propagation.MANDATORY` (no transaction is an error) and serializes with the application `ObjectMapper`.
`created_by` is the request's actor. Only the transactional services call it; controllers never do.

### D4 — Order inside one transaction

The publisher claims `ORDER BY created_at, id`. `created_at` is stamped per persist by the audit listener
from the application clock (`AuditSupport.onCreate`): in production that is the wall clock at microsecond
resolution, so two events of one transaction normally differ in `created_at` and sort in write order by it.
They tie when the clock does not advance between the two persists — always under the tests' `FixedClock`,
and possible at the clock's resolution. For ties the writer assigns the id itself: a UUIDv7 that is strictly
increasing within the process (`OutboxEventIds`: 48-bit milliseconds, a 12-bit counter, random tail; its
millisecond never goes backwards). PostgreSQL compares `uuid` byte-wise, so the id breaks every tie in write
order: `PaymentReceived` before the `ContractClosed` it caused, and `SettlementExecuted` before its
`ContractClosed`. No migration was needed.

The guarantee assumes a wall clock that does not step backwards between two persists of one transaction. An
NTP step back at that instant can publish `ContractClosed` before its cause; consumers already deduplicate by
event id and must not rely on cross-aggregate order for correctness.

### D5 — Publisher and delivery semantics

- `OutboxPublisherScheduler` (cron `serfira.jobs.outbox-publisher.cron`, default every 5 seconds,
  Asia/Jakarta, on the shared `serfira-scheduling-` pool of `JobSchedulingConfiguration`, so it never waits
  for the nightly jobs or for ShedLock's keep-alive thread) → `LockedOutboxPublisherJob` (ShedLock lock `outbox-publisher`) → `OutboxPublisherJob`
  (SYSTEM actor) → `OutboxBatchPublisher.publishBatch` (`REQUIRES_NEW`).
- A batch claims up to `batch-size` (default 100) `PENDING` rows with `FOR UPDATE SKIP LOCKED`, hands each
  to the `OutboxSink`, and marks it `SENT` with `sent_at`. A sink failure increments `retry_count`; at
  `max-retries` (default 10) the row becomes `FAILED` and is no longer claimed. One failing event never
  rolls back the rest of its batch.
- A tick keeps claiming while batches come back full **and** clean. It stops after any batch with a
  failure, so a refused event retries on the next tick instead of spending its budget in one loop.
- **At-least-once.** A crash after the sink accepted an event but before the `SENT` commit re-delivers it.
  Consumers deduplicate by event id. Order is best-effort once a row has been retried.
- **Re-driving a `FAILED` row** is a manual operation once the sink's cause is fixed:
  `UPDATE outbox_events SET status = 'PENDING', retry_count = 0 WHERE id = …` (or `WHERE status = 'FAILED'`).
  The next tick publishes it; consumers deduplicate if an earlier attempt did reach them. The retry budget
  (`max-retries` 10 at a five-second cadence, about 50 seconds) has no backoff. That suits the log sink, which
  practically cannot fail; a broker sink needs backoff before it ships (Deferred).
- **A row that cannot be loaded blocks the claim.** The claim maps rows to the `OutboxEvent` entity, so a row
  this instance cannot read — for example an `event_type` added by a newer version during a rolling deploy —
  fails the whole batch on every tick, before any per-event retry, and never reaches `FAILED` or `job_run`.
  It is visible only as the scheduler's error log. With a single instance today this cannot happen; a
  multi-version deployment needs a per-row guard first (Deferred).

### D6 — `job_run` policy

Only a tick in which at least one event failed writes a `job_run` row (`outbox-publisher`,
`records_processed` = sent, `records_failed` = failed, status `FAILED`). Idle and all-success ticks write
nothing: at a five-second cadence they would bury the daily jobs' rows, and the per-event log line plus the
`SENT` row already record them.

### D7 — Sink seam

`OutboxSink.publish(OutboxEvent)` is the only broker-facing surface. Today `LoggingOutboxSink` writes one INFO
line per event (`Published outbox event`) whose key-values — `event_id`, `event_type`, `aggregate_type`,
`aggregate_id`, `payload` — become fields of the ECS line (T20). With INFO disabled for that logger it throws
instead of returning, so the event counts a retry and is never marked `SENT` unlogged. A broker (Kafka, TS §2.7) replaces only this
bean; writer, ordering, retries and `job_run` stay as they are.

---

## Consequences

- Every business transition of the six is observable in the log within a few seconds, without a broker.
- Adding an event means a payload record, one `append` at the transition, and a row in D1.
- `SENT` rows accumulate. Retention is deferred (T38 Deferred row); `idx_outbox_status_created` keeps the
  claim query on the `PENDING` slice.
- While one event keeps failing, a tick processes at most one batch, so a large backlog drains more slowly
  until the event reaches `FAILED`.

## Alternatives Considered

- **Database-generated random ids.** Order of same-transaction events would be arbitrary. Rejected (D4).
- **A sequence column.** Gives total order but needs a migration; the time-ordered id gives the same result
  for the claim query. Rejected.
- **Emitting from controllers or a Spring `ApplicationEvent` after commit.** Loses the same-transaction
  guarantee (an event without its change, or a change without its event). Rejected.
- **A `job_run` row per tick.** ~17,000 rows a day. Rejected (D6).
