---
inclusion: always
---

# Development Workflow

`docs/tasks.md` is the only backlog. Tracker operations (create, claim, close, open question) are defined in `docs/agents/issue-tracker.md`. Do not create GitHub issues, `.scratch/` files, or Kiro specs (`.kiro/specs/`) for backlog work. They would duplicate `tasks.md`.

## Pick the workflow by task state

1. **Fully specified task** (has Scope, Business Rules, Acceptance Criteria, Tests, no open `A-n` dependency): implement it directly. Read the task and everything it cites first. Write test-first for financial rules, invariants, and engines (`/tdd`).
2. **Task with an open or fuzzy decision**: run `/grill-with-docs` first. Record each answer in its authoritative doc or ADR and resolve the `A-n` row. Then it becomes type 1. If the decision changes a documented business rule, stop and confirm with the user (`00-core`).
3. **Unplanned work** (a "Later sprints" row or a new feature): grill it, then write detailed `### T<n>` tasks into the sprint section in the existing format. Implement only after the user approves the tasks.
4. **Bug or failing test**: `/diagnosing-bugs`. If the fix needs more than a small change, add a task for it.

## Session rules

- One task per session and one branch per task. Claim it (`Status: IN PROGRESS`) before the first code change.
- Respect `Dependencies:`. Never start a task whose dependencies are not `DONE` or resolved. Say which one blocks it.
- Before declaring done: narrowest tests, then the full `./gradlew test`, then `./gradlew check`. Close the task per `issue-tracker.md`: implementation note with the verification counts, Completed Work, Current Project State, and `PROGRESS.md`.
- Before merge: `/code-review` against `main`, with the task section as the spec. Then `/pr` for the PR body.
- New domain terms go in `GLOSSARY.md`. Significant decisions go in a new ADR.
