---
inclusion: fileMatch
fileMatchPattern: "frontend/**"
---

# Frontend Rules

## Stack

- Next.js
- TypeScript
- React
- Follow the frontend stack and conventions documented in `/docs`.

## Architecture

- Frontend is a client of the Spring Boot API.
- Business rules belong in the backend.
- Never duplicate financial calculation logic in the frontend.
- Never treat frontend validation as the authoritative validation layer.

## API

- Use the shared API client.
- Centralize response-envelope handling.
- Use typed API responses.
- Handle loading, empty, error, and success states explicitly.

## State

- Server state should use the project's server-state/data-fetching pattern.
- Form state should remain local unless shared state is actually required.
- Do not introduce global state for convenience.

## Financial UI

- Format monetary amounts consistently.
- Do not perform financial calculations using JavaScript floating-point arithmetic.
- Treat all values received from the backend as authoritative.

## UX

For financial mutations such as payment, settlement, void, and credit application:

- prevent accidental duplicate submission
- show clear mutation state
- surface API errors clearly
- do not hide business validation errors

## Scope

Do not build a large design system unless requested.
Prefer existing project components and patterns.
