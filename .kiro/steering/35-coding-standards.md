---
inclusion: always
---

# Coding Standards

Project-specific rules beyond `structure.md` (code style) and `tech.md` (conventions). Follow existing patterns before inventing new ones.

## Java

- Target Java 21. `final` fields by default; `record` for DTOs, snapshots, and value objects; `enum` for finite domain states.
- `Optional` only as a return type where absence is expected. Never as an entity field or method parameter. Never use `null` as an undocumented signal.
- Guard clauses over nested conditionals. Prefer a plain loop over a long or deeply chained stream.
- Never expose mutable internal collections. Return `List.copyOf` / unmodifiable views.
- Use domain terminology from `docs/03_DOMAIN_MODEL.md`. Do not invent synonyms for established concepts.
- Avoid generic names (`data`, `info`, `value`, `result`, `handler`) unless the meaning is obvious from context.

## Layers

- Never expose JPA entities through the API. Map explicitly to request/response records.
- Controllers: HTTP only. Application services: orchestration and transaction boundaries. Repositories: persistence only, no business decisions.
- Validate input at the API boundary (Bean Validation) and re-validate invariants in the domain/application layer.

## Exceptions and logging

- Catch only to handle, translate, or recover. Preserve the cause when wrapping. Never swallow.
- API errors go through `GlobalExceptionHandler` and `ErrorCode`. Never expose stack traces or internals.
- Use SLF4J, never `System.out`. Log identifiers and safe metadata, never PII, tokens, secrets, or full financial payloads.

## Comments

- Explain why, not what. Remove comments that become stale. Do not duplicate `/docs` in block comments; cite the section instead.

## Dependencies and refactoring

- Prefer the JDK and existing dependencies. Every new dependency needs a clear justification.
- No unrelated refactors. Keep structural refactors separate from behavior changes.

## Forbidden

- `float`/`double` for money or rates
- field injection
- business logic in controllers or repositories
- bypassing module boundaries or domain invariants
- modifying posted financial history
- disabling or weakening tests to get a green build
- suppressing warnings without understanding them
- patterns or infrastructure not required by the current phase
