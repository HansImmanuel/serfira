# Serfira — Multifinance Loan Servicing Core

Sistem loan servicing multifinance (pembiayaan kendaraan) berbasis **modular monolith**
Spring Boot + PostgreSQL. Proyek portofolio: menekankan correctness aturan finansial,
audit trail penuh, dan arsitektur modular yang disiplin.

> Dokumen spesifikasi adalah sumber kebenaran: lihat [`docs/`](docs/).

## Status

Sprint 0 (fondasi) — **in progress**:

- [x] A2 — Injectable Clock, audit foundation, exception envelope
- [x] A3 — Flyway baseline schema, system parameters, COA seed
- [x] A4 — Contract number generator
- [x] A1 — Repository setup, CI, Docker Compose, README

Sprint 1+ (domain engine, API, ledger posting) belum dimulai.

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
| `shared`     | Clock, audit, envelope error, nomor dokumen     |

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
