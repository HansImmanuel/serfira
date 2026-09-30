---
inclusion: always
---

# Security Rules

## Secrets

- Never commit secrets, passwords, API keys, JWT signing keys, database credentials, or tokens.
- Never hardcode secrets in source code or tests.
- Use environment/configuration mechanisms.

## Authentication

- Never trust user identity supplied in request bodies.
- Audit actors must come from the authenticated security context.
- Do not store refresh tokens in plaintext.
- Keep access tokens short-lived according to the project specification.

## Authorization

- Enforce authorization on the server.
- Default to deny.
- Never rely on frontend visibility as an authorization mechanism.

## PII

Never log:

- NIK
- passwords
- authentication tokens
- refresh tokens
- sensitive customer data

Mask sensitive values when logging is unavoidable.

## API Security

- Validate all external input.
- Do not expose internal stack traces.
- Do not disable security controls just to make local development easier unless the change is explicitly scoped and isolated to development.

## Financial Actions

Extra care is required for:

- payment
- payment void
- settlement
- write-off
- penalty waiver
- credit application

Never implement these actions without checking authorization, idempotency, validation, and audit requirements.
