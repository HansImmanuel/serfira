# Project Structure

```
serfira/
├── .github/workflows/ci.yml     # CI: check (unit + Testcontainers IT) + gitleaks + dependency-check + compose smoke
├── docker-compose.yml           # postgres:16 + app (dev-only secrets, DB/API bound to 127.0.0.1)
├── docs/                        # Specs (Bahasa Indonesia), ADRs, progress log. Source of truth.
│   ├── 01_PRD.md … 06_FRONTEND_SPEC.md
│   ├── adr/ADR-0NN-*.md         # Architecture decisions (currently 001–022)
│   ├── PROGRESS.md, tasks.md    # Sprint narrative, live backlog; tasks-archive.md holds closed detail
└── backend/                     # Spring Boot app (Gradle, Kotlin DSL)
    ├── build.gradle.kts, Dockerfile
    └── src/
        ├── main/java/com/serfira/
        │   ├── SerfiraCoreApplication.java
        │   ├── contract/        # contracts, customers, assets, activation, schedule engine
        │   ├── payment/         # payments, allocation engine, conflict retry, void
        │   ├── penalty/         # penalty accrual, daily servicing job (billing → penalty → aging → consistency)
        │   ├── settlement/      # settlement quote and execution
        │   ├── ledger/          # double-entry journal, chart of accounts, posting
        │   ├── reporting/       # read models (aging report, statements)
        │   ├── auth/            # app users, login, token issuance (ADR-022)
        │   └── shared/          # api envelope, audit, clock, concurrency, config, document numbers,
        │                        # error, idempotency, job, observability, security (PII + JWT)
        ├── main/resources/
        │   ├── application.properties
        │   ├── db/migration/V{n}__*.sql   # Flyway migrations (V1 baseline … V23)
        │   └── db/demo/R__demo_seed.sql   # repeatable demo-only seed (profile `demo`, ADR-022 D8)
        └── test/
            ├── java/com/serfira/          # mirrors main packages; *Test = unit, *IT = integration
            └── resources/application.properties   # shadows main; keep in sync
```

## Module layout (modular monolith, ADR-001)

Every business module uses the same four layers: `com.serfira.<module>.{api, application, domain, infrastructure}`.

- **api**: `@RestController`s and request/response DTOs as Java `record`s (`*Request`, `*Response`, `*ListItem`). HTTP concerns only.
- **application**: `@Service`s holding transaction boundaries (`*CommandService`, `*QueryService`, `*ApplicationService`), cross-module **ports** (`*Port` interfaces + implementing `*Service`), snapshots/DTOs exposed through ports, and module exceptions.
- **domain**: JPA entities, enums, and pure calculation engines (in subpackages such as `domain/schedule`, `domain/allocation`). Engines are `final` classes with static methods or plain objects, with no Spring dependencies.
- **infrastructure**: Spring Data repositories, JPA Specifications, projections.

`shared` is organized by concern (`shared/api`, `shared/clock`, `shared/error`, ...), and some subpackages use their own `application/domain/infrastructure` split (e.g. `shared/job`).

## Module dependency rules (strict)

- `ledger` depends on no other module.
- `contract` → `ledger`, and → `penalty` only via `EffectivePenaltyPort` (ADR-019). `contract` knows nothing about `payment`.
- `payment` → `ledger`, `contract` (only via its ports: `InstallmentReceivablePort`, `InstallmentBillingPort`, `ContractCreditPort`, `PaymentVoidPort`), `penalty` (only via `PenaltyAccrualPort`).
- `penalty` → `ledger`, `contract` (only via `InstallmentPenaltyPort`, `InstallmentAgingPort`, `ActiveContractListingPort`, `InstallmentStatusRecomputePort`, `ReceivableReconciliationPort`), `payment` (read-only via `PenaltyAllocationPort`, `PaymentReconciliationPort`).
- `settlement` → `ledger`, `contract` (only via settlement/credit ports), `penalty` (`EffectivePenaltyPort`).
- `reporting` may read everything, read-only, through each owner's ports (e.g. `AgingReportSourcePort`).
- `auth` → `shared` only; no business module depends on `auth`. Identity reaches other modules through `shared/security` (`AuditContext`, JWT claims).
- `shared/security` may query `app_user` directly (one indexed PK lookup per request to reject inactive users, ADR-022 D1/D6). That is the **only** sanctioned cross-module table read.
- A module must never use another module's entities, repositories, or tables. Cross-module references are plain `UUID` ids, never `@ManyToOne` across modules (the DB FK still exists).
- New cross-module needs mean a new port interface in the **owning** module's `application` package. Document it in the Tech Spec §1 dependency rules and in an ADR if it is a new edge.

## Code style

- Tabs for indentation. Import groups: project/third-party, then `java.*`, then static imports.
- Constructor injection with `private final` fields. No field `@Autowired` in main code, and no Lombok.
- Entities have a `protected` no-arg JPA constructor plus a validating public constructor (`Objects.requireNonNull`, guards that mirror DB CHECK constraints). State changes go through intention-revealing methods, not setters.
- Enums are persisted with `@Enumerated(EnumType.STRING)`. IDs are `UUID` (`GenerationType.UUID`).
- Javadoc explains the **why** and cites spec sections (`TS §2.3`, `DM §1.4`, `PRD P-3`, `ADR-010`). Keep that style for new public types and non-obvious logic.
- Business numbers: `MF-YYYYMM-XXXX` (contract), `PAY-YYYYMM-XXXX` (payment), generated by `shared.document.DocumentNumberGenerator`.
