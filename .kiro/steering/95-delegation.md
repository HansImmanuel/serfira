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
