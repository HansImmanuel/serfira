# Issue tracker: `docs/tasks.md`

The backlog for this repo is the markdown file `docs/tasks.md`. It is canonical (it supersedes the per-sprint allocation in `05_SPRINT_PLAN.md`). Do **not** create GitHub issues or `.scratch/` files for planned work; that would fork the backlog. GitHub is used only for branches and pull requests.

`tasks.md` points at the specs and ADRs and never restates them. Business rules stay in `01_PRD.md` → `02_TECH_SPEC.md` → `03_DOMAIN_MODEL.md` → `04_GAPS_ADDENDUM.md` → `docs/adr/`.

## Structure of `tasks.md`

- **Current Project State**: snapshot, verified test counts, what is implemented, current sprint, blockers, and the spec/implementation discrepancy table (`X-n`).
- **Completed Work**: summary table of DONE stories. Never re-implement these.
- **Sprint sections** (`## Sprint 4c — …`): a Goal / Scope (points) / Exit header, then one `### T<n> — Title (story or review refs)` per task.
- **Future / Deferred Work**: "Later sprints" table (one-line stories, detailed at their sprint planning) and a "Deferred" table.
- **Planning Notes**: accepted invariants, verified review findings (`CR-n`), ambiguities (`A-n`), assumptions, and the dependency-order diagram.

A task (one "ticket") uses these fields, in this order:

```markdown
### T<n> — Title (story id; review CR-xx)

Status: TODO
Estimate: <n> pts

Goal: …
Scope: …
Business Rules: … (cite spec/ADR; never decide an open ambiguity here)
Implementation Notes: …
Dependencies: T<n>, A-<n>, or "none".
Acceptance Criteria: …
Tests: … (narrowest first)
Risks: …
```

Status values: `TODO` · `IN PROGRESS` · `BLOCKED` · `DONE` · `DEFERRED`. Estimates use sprint points (1 pt ≈ 2–3 h).

## Conventions

- **Create a ticket**: add a `### T<n>` section to the target sprint, with `n` = highest existing T-number + 1 (search `tasks.md` and the Later sprints table). Update the sprint header's Scope points, and the dependency-order diagram in Planning Notes. A ticket for a later sprint that is not yet planned in detail goes in the "Later sprints" table as one row with its carry-forward requirements.
- **Read a ticket**: read its `### T<n>` section, plus every spec section, ADR, `A-n`, `X-n`, and `CR-n` it cites.
- **List tickets**: scan the sprint sections for `Status:` lines; the Later sprints and Deferred tables hold the rest.
- **Comment on a ticket**: append a dated paragraph inside the task section, directly under `Status`/`Estimate`, e.g. `Triage note (2026-10-01): …` or `Decision (2026-10-01): …`.
- **Apply / remove a label**: edit the `Status:` line. Triage roles are recorded as a `Triage:` line under `Status:` (see `triage-labels.md`).
- **Close (done)**: set `Status: DONE`, add `Implementation note (<date>): …` (what changed, decisions, verification commands and test counts), add a row to Completed Work, update Current Project State, and tick the item in `docs/PROGRESS.md`.
- **Close (won't do)**: set `Status: DEFERRED` with the reason on the same line, and move it to the Deferred table if it leaves the sprint.
- **Open question**: add an `A-n` row to Planning Notes → Ambiguities with the documents in tension and the tasks it affects; set dependent tasks to `BLOCKED` until it is resolved in an ADR or spec section.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Solo repo; GitHub PRs are only the merge vehicle for a task.)_

## When a skill says "publish to the issue tracker"

Add or update the `### T<n>` section(s) in `docs/tasks.md` as described above. Show the user the diff; do not commit.

## When a skill says "fetch the relevant ticket"

Read the `### T<n>` section of `docs/tasks.md` and the documents it cites.

## Wayfinding operations

Used by `/wayfinder`.

- **Map**: the target sprint section's header (Goal / Scope / Exit) plus Planning Notes. Notes and fog go in Planning Notes; decisions-so-far are the resolved `A-n` rows and the ADRs they cite.
- **Child ticket**: a `### T<n>` task in that sprint. Decision tickets follow the precedent of T1 ("Resolve Phase-1 specification decisions"): Business Rules "None created here", Tests "None (documentation)".
- **Blocking**: the `Dependencies:` line and the dependency-order diagram. A ticket is unblocked when every listed T-task is `DONE` and every listed `A-n` is resolved.
- **Frontier query**: tasks in sprint order with `Status: TODO` and no open dependency; first wins.
- **Claim**: set `Status: IN PROGRESS`, the session's first write.
- **Resolve**: close as above. For a decision ticket, record the answer in the authoritative doc or ADR, mark the `A-n` row resolved with its reference, and unblock dependents.
