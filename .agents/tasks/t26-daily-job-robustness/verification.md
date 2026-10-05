# T26 — Daily job robustness: verification evidence

Branch: `t26-daily-job-robustness`. All commands run from
`c:\Users\hans\Documents\serfira\.worktrees\t26-daily-job-robustness\backend` with `.\gradlew`.
Docker **was available** (Docker server 29.7.2), so the Testcontainers `*IT` suites ran.

## Commands run and results

| # | Command | Result |
|---|---------|--------|
| 1 | `.\gradlew compileJava` | BUILD SUCCESSFUL |
| 2 | `.\gradlew compileJava compileTestJava` | BUILD SUCCESSFUL |
| 3 | `.\gradlew test --tests "*DailyServicingOrchestratorTest" --tests "*JobRunTest" --tests "*ActiveContractListingServiceTest"` | BUILD SUCCESSFUL (narrowest new/updated unit tests) |
| 4 | `.\gradlew test --tests "*Test"` | BUILD SUCCESSFUL (all unit tests, no Docker needed) |
| 5 | `.\gradlew test --tests "*DailyJobRobustnessIT" --tests "com.serfira.penalty.*"` | BUILD SUCCESSFUL (new IT + all penalty ITs) |
| 6 | `.\gradlew test --tests "*DailyServicingJobIT" --tests "*DailyServicingLockIT" --tests "*DailyAgingJobIT" --tests "*DailyServicingTransactionIT" --tests "*TransactionIT"` | BUILD SUCCESSFUL (the suites the task requires stay green) |
| 7 | `.\gradlew check` (full) | BUILD FAILED — only `com.serfira.contract.ContractStatementIT` fails (5 of 621), see below |

## The 5 `check` failures are pre-existing, not a T26 regression

- The full `.\gradlew check` reports `621 tests completed, 5 failed`. The test-results XML shows the only
  failing class is `com.serfira.contract.ContractStatementIT` (failures=5, errors=0). Those are
  `GET /contracts/{id}/statement` returning a non-200 status — a path T26 does not touch (no change to the
  contract statement controller, service, ledger read, or the security matrix).
- Verified pre-existing: running `.\gradlew test --tests "*ContractStatementIT"` on the **unmodified `main`
  checkout** (`c:\Users\hans\Documents\serfira`, commit `a10b552`, no working-tree changes) fails with the
  identical 5 failures at the identical lines (`ContractStatementIT.java:308` and `:254`). It also fails in
  isolation inside the worktree. So this failure exists on `main` before any T26 change.
- Every other test passes (616 of 621), including all new T26 unit tests, `DailyJobRobustnessIT`, and the
  four IT suites the task names as must-stay-green (`DailyServicingJobIT`, `DailyServicingLockIT`,
  `DailyAgingJobIT`, `DailyServicingTransactionIT`) plus `TransactionIT`.

## New / changed tests added by T26

Unit (`*Test`, pure Java, no Spring):
- `DailyServicingOrchestratorTest` — migrated to the new constructor (injected `Sleeper` + `batchSize`) and
  the keyset port method; added: exact backoff sequence `{50,150,400,1000}` with a fake `Sleeper` (succeed-on-5th
  and fail-all-5 cases), `BACKOFF_MILLIS.length == MAX_ATTEMPTS - 1`, no-pause-when-nothing-retried,
  `abandonStaleRuns` runs before the first `start` (InOrder, against the package-private constants),
  multi-page keyset iteration (each contract once), exact-full-last-page extra empty read, loop-escape
  finalizes all three rows via `failHard` and never calls `complete`, non-positive batch-size rejected.
- `JobRunTest` (new) — `abandon` RUNNING→ABANDONED + stamps finished_at, rejects non-RUNNING / null;
  `failHard` always FAILED (incl. recordsFailed == 0), keeps counters, rejects negative counters / non-RUNNING.
- `ActiveContractListingServiceTest` (new) — passes `ACTIVE` + afterId + `Limit.of(limit)` through, rejects
  `limit < 1` and null `afterId`.

Integration (`*IT`, Testcontainers PostgreSQL, Flyway V1–V13):
- `DailyJobRobustnessIT` (new) — (a) seeds three `RUNNING` crash-remnant rows, runs the locked job, asserts
  no RUNNING row of the three names remains, the remnants are ABANDONED with finished_at set, and this run's
  three rows COMPLETED; (b) `@SpringBootTest(properties = "serfira.jobs.daily-servicing.batch-size=2")` over
  5 ACTIVE contracts asserts each billed once + 2 accruals and the same three COMPLETED `job_run` rows /
  counters as a single batch. V13 is exercised under `ddl-auto=validate`.

## Notes

- No money/ledger/HTTP/security-matrix behaviour changed. A-9 counter semantics (contracts, not rows) and the
  three-`job_run`-rows-per-invocation contract are preserved.
- `docs/tasks.md` status and `docs/PROGRESS.md` were intentionally left unmodified per the task instruction.
