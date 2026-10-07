---
inclusion: always
---

# Delegation vs. Inline Work

When to spin up a workflow versus do the work in the main session. The default is **inline**;
delegate only when a task would otherwise swamp this session. Delegation keeps the main context
lean but costs more total tokens (fresh sessions re-read steering and files the main session
already holds, and a review loop pays its reviewer every iteration). Bias toward the cheaper
option unless context budget or a required review gate justifies the overhead.

## Do it inline (default)

- Single file, or a few files, where the change is clear and the paths are known.
- Edits to tests, config, docs, steering, or a well-scoped bug fix.
- Follow-up fixes after a review, when the needed context is already loaded.
- Any change small enough to make without reading large amounts of new source into context.

## Delegate to a workflow

- The task spans many files or subsystems and needs substantial new reading, enough that doing
  it inline would crowd out the orchestration role or risk a context compaction.
- The work genuinely needs an implement-and-review loop or a parallel multi-model review.
- Ambiguous or heavy work (new architecture, multiple subsystems) that warrants a design pass.
- Read-only investigation that would otherwise fill the main context: use the
  `bundled://investigate` fast path, not a hand-built workflow.

## Branch and PR flow for delegated workflows (required)

Every task delegated to a workflow runs on its own branch and ends in a pull request — never a
direct push or fast-forward to `main`. The flow is always:

1. **Branch first.** Before any source edit, the workflow's first step checks the task out on a
   fresh branch off `main`, named for the task (`t<n>-<slug>`, e.g. `t14-contract-credit`). When
   the task warrants isolation, create that branch inside a git worktree
   (`git worktree add .worktrees/<name> -b <branch> main`) and run the later steps there; the
   orchestrator removes the worktree after the run. One branch per task.
2. **Commit on the branch when done.** After the work is verified (narrowest tests → full
   `./gradlew test` → `./gradlew check`, all green) and the docs are updated, the workflow commits
   to that branch. Do not commit to `main`.
3. **Open a PR against `main`.** The workflow's final step pushes the branch and opens a pull
   request targeting `main` (`gh pr create`), with the task section as the spec and a body that
   follows the `/pr` convention (summary, what was tested, blocked items). It must **not** rebase
   onto `main` and fast-forward `main` itself — merging is the user's decision, made by reviewing
   and merging the PR.

This supersedes any generic "rebase onto mainline and fast-forward" guidance: delegated work lands
through a PR, not a direct merge. The orchestrator relays the PR link to the user and leaves the
merge to them.

## Cost discipline

- Prefer one review pass, not two. Do not run the `/code-review` skill on top of a workflow's
  own review loop unless the structured two-axis review adds something the loop cannot (e.g. the
  Standards + Spec split, or a second model). Pick the loop's reviewer **or** a post-hoc
  `/code-review`, not both, unless the task's stakes justify the duplicate spend.
- Do not pre-read application source in the main session just to brief a workflow. Point the
  workflow at the paths; it does the reading. One grep to confirm a path is fine.
- A borderline task that is test-only or has precisely known paths leans inline, even if large,
  when no review gate is required.
- When unsure, state the trade-off in one line and pick the cheaper option.
