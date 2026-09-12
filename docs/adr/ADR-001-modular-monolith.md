# ADR-001: Modular Monolith Architecture

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** Hans (solo developer)

---

## Context

Sistem ini adalah proyek portofolio yang mendemonstrasikan kemampuan desain sistem finansial kompleks. Ada tradeoff antara:

- **Microservices:** scalability baik, tapi overhead infrastruktur (service discovery, distributed tracing, inter-service auth, eventual consistency) sangat besar untuk tim 1 orang dan tidak menambah nilai demonstrasi pada tahap ini.
- **Modular Monolith:** satu deployment unit, tapi modul dipisah secara ketat berdasarkan bounded context. Semua komunikasi antar modul via interface (application service), bukan direct query antar tabel.
- **Big Ball of Mud:** paling cepat, tapi tidak demonstrable untuk interview dan tidak scalable.

Sistem ini fokus pada **servicing domain** dengan 6 bounded context yang jelas: `contract`, `payment`, `penalty`, `settlement`, `ledger`, `reporting`.

---

## Decision

**Gunakan Modular Monolith** dengan satu aplikasi Spring Boot dan satu database PostgreSQL.

Package structure:
```
com.serfira
├── contract/     → Contract, Customer, Asset, activation, jadwal
├── payment/      → Payment, alokasi, idempotency, channel
├── penalty/      → Denda, aging job
├── settlement/   → Quote & eksekusi pelunasan
├── ledger/       → Journal, JournalLine, chart of accounts, posting
├── reporting/    → Read model / query service
└── shared/       → Money, AuditFields, exception, config
```

**Aturan dependency yang di-enforce via code review / ArchUnit test:**
- `payment`, `penalty`, `settlement` boleh bergantung ke `ledger` (posting jurnal).
- `ledger` tidak boleh bergantung ke modul lain (paling dasar, tidak ada circular dependency).
- `reporting` boleh baca semua tabel secara read-only.
- Satu modul **tidak boleh** query tabel domain milik modul lain secara langsung — harus via interface.

---

## Consequences

**Positif:**
- Deployment sederhana: satu JAR, satu docker-compose service.
- Tidak ada distributed transaction, tidak ada network failure antar service.
- Seam antar modul sudah berbasis interface → split ke microservice di masa depan tidak memerlukan refactor besar, hanya ganti in-process call dengan HTTP/gRPC.
- Testing jauh lebih mudah: integration test dengan satu Testcontainers PostgreSQL mencakup seluruh flow.
- Mendemonstrasikan kemampuan desain domain yang baik tanpa infra overhead.

**Negatif / Tradeoff:**
- Tidak bisa scale modul secara independen (misal, penalty job berat tidak bisa di-scale terpisah).
- Jika modul tumbuh besar, coupling bisa meningkat kalau aturan dependency tidak di-enforce ketat.
- Single point of failure pada deployment.

**Mitigasi:**
- ArchUnit test atau custom rule untuk enforce dependency direction.
- Kafka/outbox pattern sudah ada sejak awal (tabel `outbox_events`) agar event-driven split lebih mudah di masa depan.
- Jika perlu split, interface antar modul sudah siap menjadi API boundary.

---

## Alternatives Considered

| Option | Alasan Ditolak |
|---|---|
| Full Microservices dari awal | Overhead infra (K8s, service mesh, distributed tracing) tidak proporsional untuk tim 1 orang; tidak menambah nilai demo portofolio |
| Simple layered monolith tanpa modul | Tidak mendemonstrasikan kemampuan bounded context; coupling tinggi, refactor mahal |
| Event-sourcing + CQRS | Terlalu kompleks untuk scope portofolio ini; double-entry ledger sudah memberikan audit trail yang cukup |

---

## References

- Tech Spec §1: Arsitektur Modular Monolith
- [Building Microservices — Sam Newman] — Chapter on Monolith First
