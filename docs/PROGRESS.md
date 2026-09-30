# Progress Log

Log sprint per sprint (dipindahkan dari README). Rencana lengkap ada di [Sprint Plan](05_SPRINT_PLAN.md)
dan [tasks.md](tasks.md).

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

Sprint 2 (contract API & activation) — **selesai** (B5; lihat [ADR-006](adr/ADR-006-contract-creation-and-activation.md) & [ADR-007](adr/ADR-007-idempotent-contract-creation.md)):

- [x] B5 — `POST /api/v1/contracts` (create DRAFT, `Idempotency-Key` wajib, customer/asset reuse by identity, duplicate-live-contract guard), `POST /api/v1/contracts/{id}/activate` (generate + persist jadwal tepat sekali, idempotent), `GET /api/v1/contracts` (paged, filter status, search `contract_no`/nama), `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`
- [x] V6 — `contract.planned_start_date` (tanggal mulai yang diotor saat drafting; `start_date` effective dipin saat aktivasi, default = `planned_start_date`)
- [x] V7 — `contract.idempotency_key` backstop + partial unique index `(customer_id, asset_id) WHERE status IN ('DRAFT','ACTIVE')` (invariant #18)
- [ ] C-5 — update contract saat DRAFT: **deferred**

Sprint 3 (payment & ledger) — **selesai** (lihat [ADR-008](adr/ADR-008-ledger-posting-semantics.md), [ADR-009](adr/ADR-009-allocation-engine-semantics.md), [ADR-010](adr/ADR-010-payment-contract-seam.md)):

- [x] C1 — Modul `ledger`: entity immutable `journal_entry`/`journal_line` (`ImmutableAuditable`), `LedgerPostingService` (`MANDATORY`, guard satu entry non-reversal per `(ref_type, ref_id)`), vocabulary `LedgerRefType`, akun type-safe `LedgerAccount`, dan jurnal disbursement (`PIUTANG_POKOK`/`KAS`) yang diposting di transaksi aktivasi kontrak
- [x] C2 — Allocation engine murni di modul `payment`: denda → bunga → pokok, angsuran jatuh tempo tertua dahulu (hanya `due_date <= business date`), cap identik trigger V3, satu baris `EXCESS` untuk kelebihan; test 20+ skenario + guard value object (lihat [ADR-009](adr/ADR-009-allocation-engine-semantics.md))
- [x] C3 — `POST /api/v1/payments` (`Idempotency-Key` wajib): alokasi lewat engine C2, resolusi `InstallmentStatus`/`paid_at` di modul `contract` lewat port `InstallmentReceivablePort`, satu jurnal double-entry per pembayaran (`KAS` vs `PIUTANG_DENDA`/`PIUTANG_BUNGA`/`PIUTANG_POKOK`/`TITIPAN_NASABAH`), replay idempotent tanpa double-post; excess hanya dicatat sebagai baris `EXCESS` (credit row milik E3)
- [ ] E3 — credit application (`contract_credit`), E4 — payment void: **deferred** ke Sprint 5

Sprint 4 (penalty, aging & phase-1 close) — **berjalan** (lihat [ADR-011](adr/ADR-011-billing-recognition-and-maturity-close.md), [ADR-012](adr/ADR-012-penalty-accrual-semantics.md), [ADR-013](adr/ADR-013-phase1-close-semantics.md), dan [ADR-014](adr/ADR-014-lazy-penalty-accrual-in-payment-path.md)):

- [x] C4 — Billing/recognition: port `InstallmentBillingPort` (`billDueInterest(contractId, businessDate)`) dengan **lazy billing** di transaksi `POST /payments` (bunga receivable pada due date — PRD skenario 1 tanpa seeding), satu jurnal `BILLING` per installment (`PIUTANG_BUNGA` debit / `PENDAPATAN_BUNGA` kredit, `entry_date = due_date`, `ref_id = installment.id`), dan **maturity auto-close** (`closed_reason=MATURITY`) ketika final regular payment melunasi seluruh installment (invariant 17)
- [x] D1 — Denda harian: modul `penalty` (`PenaltyCalculator` murni + `PenaltyAccrual` di tabel `penalty_accrual`), step `PenaltyAccrualPort.accrueDuePenalty(contractId, businessDate)` yang berdiri sendiri dan transactional, seam `InstallmentPenaltyPort` agar `penalty_amount` tetap milik aggregate `contract`, serta satu baris accrual + satu jurnal `PENALTY_ACCRUAL` per hari terlambat di luar grace
- [x] T3 — Scheduler harian billing → penalty: cron Jakarta dan explicit backfill memakai ShedLock renewable yang sama; setiap kontrak ACTIVE diproses atomik dengan retry/failure isolation dan step-level `job_run`
- [x] T4 — Lazy penalty accrual di `POST /payments`: edge satu arah `payment → PenaltyAccrualPort`; di dalam supplier idempotensi dan satu transaksi berjalan billing → accrual → snapshot → allocation → resolution → jurnal payment dengan satu business date. Replay tidak menambah accrual; kegagalan me-rollback seluruh write. Nilai teruji: 28 × `1.573,33` = `44.053,24`, total payment `1.617.386,57`; `compileJava compileTestJava` pass, `PaymentApiIT` 17 pass, full test 413 pass, dan `check` pass
- [x] T5 — Retry race job-versus-payment: `PaymentRetryingService` membungkus `PaymentApplicationService.create` di luar batas transaksinya, membuka transaksi + claim idempotensi baru per percobaan (maksimum 3 percobaan, jeda 50 lalu 150 ms). Optimistic-lock dan SQLSTATE `23505` di-retry; error validasi/state bisnis tidak. Percobaan habis → 409 `CONCURRENT_MODIFICATION`. IT memaksa race SQLSTATE `23505` nyata terhadap PostgreSQL (Testcontainers); `compileJava compileTestJava` pass, full test 420 pass, `check` pass
- [x] T6 — Langkah aging harian: setelah transaksi billing → penalty, transaksi aging per kontrak lewat `InstallmentAgingPort` menandai `PENDING`/`PARTIALLY_PAID` dengan outstanding > 0 menjadi `OVERDUE` mulai `due_date + grace + 1`; tanpa rewrite untuk yang sudah `OVERDUE`, `PAID`/`SETTLED`/`WRITTEN_OFF` tidak disentuh, row `job_run` `aging` sendiri. Full test 460 pass, `check` pass
- [ ] Sprint 4c — T23 penyelarasan dependensi Boot 4 (ShedLock 7.x, springdoc 3.x), T7 RBAC + identitas JWT fail-closed (rencana siap), T8 aging report, T9 statement
- [ ] Sprint 4d — T24 lifecycle idempotency key (A-13 diputuskan: key sekali pakai per endpoint selamanya, retensi hanya membatasi replay), T25 backstop DB (jurnal/payment tanpa child, uniqueness event ledger, `system_parameter` append-only), T26 robustness job harian, lalu T11 Phase-1 exit verification

Review eksternal 2026-09-30 (16 temuan, CR-01…CR-16) sudah diverifikasi terhadap kode dan dipetakan ke task di [tasks.md](tasks.md) → Planning Notes.

Sprint 5+ (settlement, credit application, void, consistency check, write-off, frontend) belum dimulai.
