---
inclusion: always
---

# Serfira Core Agent Rules

## Source of Truth

Treat these as the authoritative specification (Bahasa Indonesia):

- `docs/01_PRD.md`: product requirements and scope
- `docs/02_TECH_SPEC.md`: architecture and technical decisions
- `docs/03_DOMAIN_MODEL.md`: entities, invariants, and domain rules
- `docs/04_GAPS_ADDENDUM.md`: resolved design gaps and clarifications
- `docs/05_SPRINT_PLAN.md`: implementation order and scope
- `docs/adr/`: architecture decisions

Order of authority: project docs → steering → existing implementation → skill or generic guidance. When a skill or generic best practice conflicts with the docs, follow the docs and say so.

## Before Coding

- Read the relevant source-of-truth documents before changing behavior.
- Identify affected modules, business rules, invariants, migrations, and tests.
- Prefer the smallest change that fully satisfies the requested task.
- Do not introduce new libraries, frameworks, infrastructure, or architectural patterns without justification.
- For version-sensitive framework behavior (Spring Boot 4, Spring Security, Flyway, Testcontainers, Jackson 3), check current docs via Context7 rather than relying on memory.

## Implementation Behavior

- Follow existing project patterns before inventing new ones.
- Keep business logic explicit and testable.
- Never bypass a domain invariant merely to make a feature easier to implement.
- Never silently change a documented business rule.
- If the implementation conflicts with the docs, stop and identify the conflict instead of guessing.
- If a requested feature appears to violate the architecture, explain the conflict before implementing it.

## Completion

A task is not complete until:

1. Relevant tests are added or updated.
2. Existing tests still pass.
3. Relevant documentation is updated when behavior or architecture changes.
4. No unrelated files are modified.
5. The final response summarizes what changed and which verification commands passed.

## Scope Discipline

- Do not implement future-phase features unless explicitly requested.
- Stay a modular monolith. Do not add microservices, Kafka, Spring Cloud, WebFlux, virtual threads, CQRS, or other deferred architecture.
- Do not build frontend functionality before the backend contract is sufficiently defined.
