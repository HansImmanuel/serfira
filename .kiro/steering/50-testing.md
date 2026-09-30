---
inclusion: always
---

# Testing Rules

## Business Logic

Every new financial or domain rule must have tests.

Prefer pure unit tests for:

- schedule calculation
- interest calculation
- penalty calculation
- payment allocation
- settlement calculation
- state transitions

## Integration Tests

Use Testcontainers PostgreSQL for:

- repository behavior
- transaction behavior
- database constraints
- idempotency behavior
- ledger persistence
- financial write paths

## Regression

Do not modify a golden test merely to make the implementation pass.

If an expected value changes:

1. determine why,
2. verify the business rule,
3. update the test only when the specification intentionally changed.

## Edge Cases

For financial/date logic, consider:

- zero interest
- one-period tenor
- month-end dates
- February
- leap years
- rounding
- partial payments
- overpayments
- repeated requests
- concurrent modification
- payment void
- settlement mid-period

## Verification

Before declaring a backend task complete, run the narrowest useful test first, then the full relevant test suite.

At minimum:

`./gradlew test`
