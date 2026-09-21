# Serfira — Multifinance Loan Servicing Core

Sistem loan servicing multifinance (pembiayaan kendaraan) berbasis **modular monolith**
Spring Boot + PostgreSQL. Proyek portofolio: menekankan correctness aturan finansial,
audit trail penuh, dan arsitektur modular yang disiplin.

> Dokumen spesifikasi adalah sumber kebenaran: lihat [`docs/`](docs/).

## Status

Sprint 0 (fondasi) — **selesai**:

- [x] A1 — Repository setup, CI, Docker Compose, README
- [x] A2 — Injectable Clock, audit foundation, exception envelope
- [x] A3 — Flyway baseline schema, system parameters, COA seed
- [x] A4 — Contract number generator

Sprint 1 (schedule engine core) — **selesai** (golden FLAT/EFFECTIVE test hijau, `gradlew test` hijau):

- [x] B1 — JPA entity Contract/Customer/Asset/Installment + repositories
- [x] B2 — Schedule engine FLAT (pure Java) + golden test
- [x] B3 — Schedule engine EFFECTIVE/anuitas + edge guards (i=0, n=1)
- [x] B4 — Due date calc (31→Feb 28/29, leap year) + test matrix tanggal

Sprint 2 (contract API & activation) — **selesai** (B5; lihat [ADR-006](docs/adr/ADR-006-contract-creation-and-activation.md) & [ADR-007](docs/adr/ADR-007-idempotent-contract-creation.md)):

- [x] B5 — `POST /api/v1/contracts` (create DRAFT, `Idempotency-Key` wajib, customer/asset reuse by identity, duplicate-live-contract guard), `POST /api/v1/contracts/{id}/activate` (generate + persist jadwal tepat sekali, idempotent), `GET /api/v1/contracts` (paged, filter status, search `contract_no`/nama), `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`
- [x] V6 — `contract.planned_start_date` (tanggal mulai yang diotor saat drafting; `start_date` effective dipin saat aktivasi, default = `planned_start_date`)
- [x] V7 — `contract.idempotency_key` backstop + partial unique index `(customer_id, asset_id) WHERE status IN ('DRAFT','ACTIVE')` (invariant #18)
- [ ] C-5 — update contract saat DRAFT: **deferred**

Sprint 3 (payment & ledger) — **berjalan** (C1 selesai; lihat [ADR-008](docs/adr/ADR-008-ledger-posting-semantics.md)):

- [x] C1 — Modul `ledger`: entity immutable `journal_entry`/`journal_line` (`ImmutableAuditable`), `LedgerPostingService` (`MANDATORY`, guard satu entry non-reversal per `(ref_type, ref_id)`), vocabulary `LedgerRefType`, akun type-safe `LedgerAccount`, dan jurnal disbursement (`PIUTANG_POKOK`/`KAS`) yang diposting di transaksi aktivasi kontrak
- [ ] C2 — Allocation engine (denda → bunga → pokok, oldest first) + 20 skenario test
- [ ] C3 — `POST /api/v1/payments` + `Idempotency-Key` + double-post test

Sprint 4+ (billing/recognition, statement, penalty, settlement) belum dimulai.

## Arsitektur

Modular monolith, satu deployment unit Spring Boot, satu database PostgreSQL 16
(lihat [ADR-001](docs/adr/ADR-001-modular-monolith.md)):

| Modul        | Tanggung jawab                                  |
| ------------ | ----------------------------------------------- |
| `contract`   | Kontrak, nasabah, aset, aktivasi, jadwal        |
| `payment`    | Pembayaran, alokasi, idempotency                |
| `penalty`    | Denda, accrual job, penyesuaian                 |
| `settlement` | Quote & eksekusi pelunasan                      |
| `ledger`     | Double-entry journal, chart of accounts         |
| `reporting`  | Read-only query service                         |
| `shared`     | Clock, audit, envelope error, nomor dokumen, PII security  |

Paket mengikuti `com.serfira.<module>.{api, application, domain, infrastructure}`.

## Teknologi

- Java 21, Spring Boot 4 (Web, Data JPA, Security, Validation, Actuator)
- PostgreSQL 16, Flyway (schema sepenuhnya milik migrasi, `ddl-auto=validate`)
- Testcontainers untuk integration test
- Gradle 9.7.1, OpenAPI (springdoc), ShedLock untuk job multi-instance

## Memulai

Prasyarat: JDK 21, Docker, Gradle 9.7.1 (atau wrapper).

```bash
# Jalankan seluruh test suite (unit + integration via Testcontainers)
cd backend
gradle test

# Naikkan stack lokal (PostgreSQL + aplikasi)
docker compose up --build

# Aplikasi: http://localhost:8080  (OpenAPI UI: /swagger-ui.html)
```

Variabel `POSTGRES_PASSWORD` / `SPRING_DATASOURCE_PASSWORD` di `docker-compose.yml`
adalah kredensial **lokal-dev saja**; override lewat `.env` untuk lingkungan lain.

## Keamanan PII (ADR-004)

`Customer.nik` dan `Customer.phone` **tidak pernah tersimpan plaintext** di database:

- AES-256-GCM ciphertext (envelope `v1:<base64(IV‖ciphertext|tag)>`) ditulis oleh JPA
  `AttributeConverter` di lapisan aplikasi.
- Uniqueness & pencarian memakai kolom HMAC lookup: `nik_hash` dan `phone_lookup`
  (HMAC-SHA-256 dari nilai ternormalisasi; NIK digits-only, phone trunk `0…`→`62`).
  Pencarian by raw NIK/phone hanya lewat `CustomerSearchService` — raw value di-HMAC di
  memory, tidak pernah menyentuh SQL/log/URL. Endpoint REST-nya belum diekspos (menunggu RBAC).
- Kunci wajib disediakan via environment; tanpa kunci aplikasi **tidak mau start**:
  ```
  SERFIRA_SECURITY_PII_ENCRYPTION_KEY=<base64 32-byte key>
  SERFIRA_SECURITY_PII_HMAC_KEY=<base64 >=32-byte key>

Kunci signing JWT resource-server wajib juga via environment (HS256, default-deny,
lihat ADR-005):
  ```
  SERFIRA_SECURITY_JWT_SECRET_BASE64=<base64 >=32-byte HS256 signing key>
  ```
  ```
  Nilai dev/portofolio ada di `docker-compose.yml` (dan `src/test/resources/application.properties`
  untuk test) — **bukan production value**.

## Struktur Repositori

```
backend/   aplikasi Spring Boot (modul di atas)
docs/      PRD, tech spec, domain model, addendum, sprint plan, ADR
frontend/  (fase berikutnya — client Spring Boot API)
```

## Verification

- `cd backend && gradle test` — semua unit + integration test wajib hijau.
  Kelas integrasi `*IT` berjalan di task `test` yang sama (tidak ada source set / failsafe
  terpisah), jadi satu perintah mencakup keduanya.
- `docker compose up --build` — Flyway menerapkan baseline pada database bersih.

## Dokumentasi

1. [PRD](docs/01_PRD.md)
2. [Tech Spec](docs/02_TECH_SPEC.md)
3. [Domain Model](docs/03_DOMAIN_MODEL.md)
4. [Gaps Addendum](docs/04_GAPS_ADDENDUM.md)
5. [Sprint Plan](docs/05_SPRINT_PLAN.md)
6. [ADR](docs/adr/)
