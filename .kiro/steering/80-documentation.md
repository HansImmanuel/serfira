---
inclusion: always
---

# Documentation Rules

## Source of Truth

Keep these responsibilities separate:

- PRD → what the system must do
- Technical spec → how the system is structured
- Domain model → entities, invariants, and business semantics
- Addendum → resolved gaps and explicit decisions
- Sprint plan → implementation order and scope
- ADRs (`docs/adr/`) → significant decisions, with alternatives considered and rejected
- Steering (`.kiro/steering/`) → how the coding agent must work

Do not duplicate entire specifications inside steering.

## Consistency

When changing a business rule or architecture:

- update the authoritative document
- update affected derived documentation (README, Tech Spec §1 dependency rules, `docs/PROGRESS.md`)
- update relevant sprint references when scope changes

## Writing

- Prefer precise terminology.
- Avoid ambiguous terms such as "balance" or "outstanding" when a more specific term exists.
- Include examples for calculations or tricky business rules.
- Record significant architectural decisions as a new ADR (`ADR-0NN-kebab-title.md`), following the format of the existing ADRs. Do not create ADRs for trivial implementation details.

## Steering Maintenance

Keep steering concise.
Remove obsolete rules instead of accumulating historical instructions.
