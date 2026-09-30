---
inclusion: always
---

# Financial Domain Rules

## Monetary Values

- Never use `float` or `double` for monetary calculations.
- Use `BigDecimal`.
- Use explicit scale and rounding.
- Monetary database columns use `NUMERIC(19,2)`.
- Rate values are stored as fractions:
  - `1.5%` = `0.0150`
  - `0.5%` = `0.0050`

## Rounding

- Use the documented rounding policy consistently.
- Do not introduce ad-hoc rounding inside individual operations.
- Final installment rounding adjustments must preserve the total contractual amount.

## Payment Allocation

Default allocation order:

`PENALTY → INTEREST → PRINCIPAL → EXCESS`

Allocation must process the oldest eligible installment first.

A posted payment must satisfy:

`sum(payment allocations) = payment amount`

## Ledger

- All financial mutations must produce the appropriate double-entry journal.
- `total debit = total credit` for every journal entry.
- Posted journal entries and journal lines are immutable.
- Never UPDATE or DELETE posted ledger records.
- Corrections require reversal journals.

## Idempotency

- Payment mutations must be idempotent.
- Settlement mutations must be idempotent.
- Never execute a financial mutation twice because of a retry.

## Payment Void

- Voiding a payment is a reversal, never a deletion.
- Do not mutate historical financial records to hide the original transaction.
- Recalculate affected installment state and penalty according to the documented void flow.

## Contract Credit

- Excess payment is recorded as customer credit.
- Credit may be partially applied.
- Never lose the audit trail of credit applications.
- Do not silently apply excess credit to future installments unless the documented business rule explicitly allows it.

## Settlement

- Never assume future unrecognized interest is immediately receivable.
- Settlement must follow the documented quote and posting rules.
- A stale quote must not execute against changed contract state without revalidation.

## Invariants

Never knowingly violate these:

- journal entries balance
- payment allocations equal payment amount
- installment paid amount cannot exceed recognized receivable
- one contract has one schedule
- settlement only applies to eligible active contracts
- financial corrections use reversal/audit mechanisms
