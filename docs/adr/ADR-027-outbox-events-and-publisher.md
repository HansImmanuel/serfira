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
  reuses the same method).
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

Two events of one transaction share `created_at` (one `Clock.now()` instant), and the publisher claims
`ORDER BY created_at, id`. The writer therefore assigns the id itself: a UUIDv7 that is strictly increasing
within the process (`OutboxEventIds`: 48-bit milliseconds, a 12-bit counter, random tail; the millisecond
never goes backwards). PostgreSQL compares `uuid` byte-wise, so `(created_at, id)` is write order:
`PaymentReceived` before the `ContractClosed` it caused, and `SettlementExecuted` before its
`ContractClosed`. No migration was needed.

### D5 — Publisher and delivery semantics

- `OutboxPublisherScheduler` (cron `serfira.jobs.outbox-publisher.cron`, default every 5 seconds,
  Asia/Jakarta) → `LockedOutboxPublisherJob` (ShedLock lock `outbox-publisher`) → `OutboxPublisherJob`
  (SYSTEM actor) → `OutboxBatchPublisher.publishBatch` (`REQUIRES_NEW`).
- A batch claims up to `batch-size` (default 100) `PENDING` rows with `FOR UPDATE SKIP LOCKED`, hands each
  to the `OutboxSink`, and marks it `SENT` with `sent_at`. A sink failure increments `retry_count`; at
  `max-retries` (default 10) the row becomes `FAILED` and is no longer claimed. One failing event never
  rolls back the rest of its batch.
- A tick keeps claiming while batches come back full **and** clean. It stops after any batch with a
  failure, so a refused event retries on the next tick instead of spending its budget in one loop.
- **At-least-once.** A crash after the sink accepted an event but before the `SENT` commit re-delivers it.
  Consumers deduplicate by event id. Order is best-effort once a row has been retried.

### D6 — `job_run` policy

Only a tick in which at least one event failed writes a `job_run` row (`outbox-publisher`,
`records_processed` = sent, `records_failed` = failed, status `FAILED`). Idle and all-success ticks write
nothing: at a five-second cadence they would bury the daily jobs' rows, and the per-event log line plus the
`SENT` row already record them.

### D7 — Sink seam

`OutboxSink.publish(OutboxEvent)` is the only broker-facing surface. Today `LoggingOutboxSink` writes one INFO
line per event (`Published outbox event`) whose key-values — `event_id`, `event_type`, `aggregate_type`,
`aggregate_id`, `payload` — become fields of the ECS line (T20). A broker (Kafka, TS §2.7) replaces only this
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
