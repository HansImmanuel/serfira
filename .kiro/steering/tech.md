# Tech Stack

- **Language**: Java 21 (Gradle toolchain)
- **Framework**: Spring Boot 4.1 (web MVC, data JPA, validation, actuator, security, OAuth2 resource server)
- **Database**: PostgreSQL 16, schema owned by Flyway (`spring.jpa.hibernate.ddl-auto=validate`)
- **Scheduling**: Spring scheduling + ShedLock 7.10 (JDBC provider) for the daily servicing job
- **API docs**: springdoc-openapi 3.1 (`/swagger-ui.html`)
- **Auth**: bearer JWT, HS256, via Spring OAuth2 resource server. Default-deny. No login endpoint yet.
- **JSON**: Jackson 3 (`tools.jackson.*` packages; annotations still `com.fasterxml.jackson.annotation`)
- **Testing**: JUnit 5, AssertJ, Spring Boot Test, MockMvc, spring-security-test, Testcontainers 2.0 (PostgreSQL 16)
- **Build**: Gradle 9.7.1 wrapper, Kotlin DSL (`backend/build.gradle.kts`)
- **Container**: multi-stage Dockerfile (JRE 21, non-root), `docker-compose.yml` at repo root
- **CI**: GitHub Actions (`.github/workflows/ci.yml`) runs `./gradlew test` and `docker compose build`
- Lombok is on the classpath but **not used**. Write explicit constructors/getters, matching existing code.

Spring Boot 4 is modularized: Flyway auto-config needs `spring-boot-flyway`, and MockMvc test support is in `spring-boot-webmvc-test` (`org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`).

## Common commands

Run from `backend/` (Windows: `gradlew.bat` or `.\gradlew`):

```powershell
.\gradlew test                                    # all unit + *IT integration tests (Docker must be running)
.\gradlew test --tests "com.serfira.payment.*"    # a package
.\gradlew test --tests "*PaymentAllocationEngineTest"   # a single class
.\gradlew build                                   # compile + test + bootJar
.\gradlew bootJar                                 # build jar only
```

From repo root:

```powershell
docker compose up --build    # PostgreSQL + app on http://localhost:8080 (long-running; user runs manually)
```

Health: `/actuator/health`. The app fails fast without `SERFIRA_SECURITY_PII_ENCRYPTION_KEY`, `SERFIRA_SECURITY_PII_HMAC_KEY`, and `SERFIRA_SECURITY_JWT_SECRET_BASE64`. Compose provides dev-only defaults.

## Technical conventions (non-negotiable)

**Money**

- `BigDecimal` only, scale 2. Never `float`/`double`.
- DB: `NUMERIC(19,2)` for amounts, `NUMERIC(7,4)` for rates. Rates are decimal fractions (1.5% = `0.0150`).
- Rounding: `RoundingMode.HALF_EVEN`. Last installment absorbs rounding residuals.
- Reject input finer than scale 2 (400 `VALIDATION_ERROR`). Never silently round client input.

**Time**

- Never call `LocalDate.now()` / `Instant.now()` in business code. Inject `com.serfira.shared.clock.Clock` (`FixedClock` in tests).
- Business zone is `Asia/Jakarta`. Timestamps are `OffsetDateTime` / `timestamptz`.

**API**

- Base path `/api/v1/...`. Every response uses the `ApiResponse<T>` envelope `{ "data": ..., "error": null }`.
- JSON wire names are snake_case (global Jackson `SNAKE_CASE`). Java fields stay camelCase.
- Retryable mutations (`POST /contracts`, `POST /payments`, future settlement/credit) require an `Idempotency-Key` header, handled by `shared.idempotency.IdempotencyService`.
- Pagination: `page`, `size`, `sort` (snake_case sort tokens). Page envelope is `PageResponse`.
- Error codes live in `shared.error.ErrorCode`. Throw `SerfiraException` subclasses (`BadRequestException`, `NotFoundException`, `ConflictException`); `GlobalExceptionHandler` maps them.
- Document controllers with springdoc `@Operation` / `@ApiResponses`, listing every error code.

**Persistence and transactions**

- Schema changes go only through new Flyway migrations: `backend/src/main/resources/db/migration/V{n}__snake_case_description.sql`. Migrations are forward-only and must apply cleanly from an empty database. Never edit an applied migration. Never use `ddl-auto` values other than `validate`.
- Invariants are enforced in the DB too (CHECK constraints, deferred constraint triggers, append-only triggers). Mirror DB checks as guards in entity constructors.
- Protect concurrency-sensitive uniqueness with DB constraints (business numbers, `(contract_id, period_no)`, `(installment_id, accrual_date)`, idempotency keys), not application checks alone.
- `@Transactional` belongs on application services, never controllers. Ports and ledger posting use `Propagation.MANDATORY` (they join the caller's transaction). Read services use `readOnly = true`. `job_run` writes use `REQUIRES_NEW`.
- Write path order: validate → compute → write aggregate → post journal → commit.
- Optimistic locking with `@Version` on concurrently modified entities (Contract, Installment, Payment). Retry lives outside the transactional boundary (see `PaymentRetryingService`: 3 attempts, 50 and 150 ms pauses).
- `spring.jpa.open-in-view=false`. Load graphs explicitly (EntityGraph / DTO projections).
- Journal lines and other posted records are immutable. Corrections are reversal entries.
- Every table has audit columns (`created_at/by`, `updated_at/by`); entities extend `Auditable` or `ImmutableAuditable`. The actor is the JWT `sub` (an `app_user` UUID) or the seeded `SYSTEM` user for jobs.

**Security**

- PII fields use `AesGcmStringAttributeConverter`; lookups and uniqueness use HMAC columns (`PiiHasher`). Mask PII in responses (`PiiMasker`).
- New endpoints are authenticated by default. Only health, info, and OpenAPI paths are public.

## Testing conventions

- Unit tests: `*Test.java`, pure Java without Spring, especially for engines (`ScheduleEngine`, `PaymentAllocationEngine`, `PenaltyCalculator`). Golden tests use hardcoded expected values.
- Integration tests: `*IT.java`, `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` against real PostgreSQL. Tests commit for real (no test transaction) so deferred triggers fire.
- `src/test/resources/application.properties` shadows the main one. Keep the two in sync. Test cron is `-` (disabled). Jobs are invoked explicitly.
- Tests mint their own HS256 JWTs with the test secret. Use `FixedClock` for deterministic dates.
