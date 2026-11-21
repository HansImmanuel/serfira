# Technical Specification — Multifinance Loan Servicing Core System

- Versi: 0.3 (reconciled + final pre-code review)
- Stack: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Gradle, Testcontainers, Kafka (opsional Fase 3), Next.js 16 (frontend)

---

## 1. Arsitektur: Modular Monolith

Satu aplikasi Spring Boot, satu database, module dipisah berdasarkan bounded context.
Module berkomunikasi via interface (application service) milik module lain — **tidak boleh**
satu module query tabel domain module lain secara langsung.

```
com.serfira
├── contract/     → Contract, Customer, Asset, activation, jadwal
├── payment/      → Payment, alokasi, idempotency, channel
├── penalty/      → Denda, aging job, consistency-check step
├── settlement/   → Quote & eksekusi pelunasan
├── ledger/       → Journal, JournalLine, chart of accounts, posting
├── reporting/    → Read model / query service
├── auth/         → AppUser, RefreshToken, login, penerbitan token
└── shared/       → Money, AuditFields, exception, config, security, observability, idempotency
```

Aturan dependency:

- `payment`, `penalty`, `settlement` boleh bergantung ke `ledger` (posting jurnal).
- `ledger` tidak boleh bergantung ke module lain (paling dasar).
- `auth` bergantung ke `shared` (envelope, security, audit) dan memiliki tabel `app_user`/`refresh_token`.
  `auth` **tidak** dipanggil modul bisnis mana pun: modul lain tidak perlu tahu siapa yang login, karena
  identitas mengalir lewat `shared/security` (`AuditContext`, claim JWT). Satu-satunya pembacaan `app_user`
  di luar `auth` adalah pemeriksaan user aktif di decoder (lihat butir `shared` di bawah).
- `payment` juga boleh bergantung ke `contract` melalui **application interface**-nya (ADR-010, ADR-011):
  `InstallmentReceivablePort` (`contract.application`) adalah satu-satunya jalur bagi write path pembayaran
  untuk membaca receivable angsuran dan menerapkan hasil resolusinya, dan `InstallmentBillingPort` adalah
  satu-satunya jalur untuk billing/recognition bunga due date sebelum alokasi dihitung. Sejak T14 (E3)
  ditambah `ContractCreditPort` (`contract.application`): saat sebuah pembayaran menghasilkan alokasi
  `EXCESS`, write path `payment` memesan satu baris `contract_credit` (sub-ledger liability `TITIPAN_NASABAH`,
  bukan jurnal kedua) lewat seam ini — kepemilikan tabel `contract_credit`/`contract_credit_application` tetap
  di `contract`. Ketiganya satu arah (`payment` → port `contract`); `contract` tidak tahu modul `payment`,
  dan `payment` tetap dilarang menyentuh tabel/entity `contract`/`installment`/`contract_credit` langsung.
  Jurnal `CREDIT_APPLICATION` (E3) sengaja tidak masuk `uq_journal_entry_event`; service guard
  `LedgerPostingService` menegakkan satu entry non-reversal per `(ref_type, ref_id)`, konsisten dengan
  `PAYMENT` (ADR-008 d5).
- Sejak T4, `payment` boleh bergantung ke `penalty` hanya melalui
  `penalty.application.PenaltyAccrualPort` (ADR-014). Edge ini satu arah (`payment` → port `penalty`):
  `penalty` tidak tahu modul `payment`, dan `payment` dilarang membaca/menulis repository, entity, atau tabel
  `penalty_accrual`/persistence denda secara langsung. Kepemilikan accrual tetap di modul `penalty`; perubahan
  aggregate installment tetap melalui port milik `contract`.
- `contract` juga boleh bergantung ke `ledger` (ADR-008): aktivasi mem-posting jurnal disbursement di transaksi
  yang sama, sehingga piutang yang dibuat jadwal langsung tercatat sebagai receivable; sejak C4 langkah billing
  mem-posting jurnal `BILLING` per installment lewat service yang sama (ADR-011). Edge ini satu arah —
  `ledger` tetap tidak tahu modul lain.
- `penalty` menyentuh `installment` hanya lewat **application interface** milik `contract`
  (`InstallmentPenaltyPort`, ADR-012): step harian membaca due date, penalty base dan status, lalu menaikkan
  `penalty_amount` lewat seam itu, sementara tabel `penalty_accrual` tetap milik `penalty`. Job T3 juga
  memperoleh halaman keyset id/status kontrak ACTIVE hanya lewat `ActiveContractListingPort` (sejak T26
  keyset paging, bukan satu list penuh), dan step aging T6
  menandai `OVERDUE` hanya lewat `InstallmentAgingPort` (transisi tetap diputuskan `contract`); repository/entity
  `contract` tidak pernah keluar dari modul pemiliknya. `penalty_accrual` (D1) dan `penalty_adjustment` (E5)
  adalah tabel modul `penalty`; `penalty` dilarang menyentuh tabel/entity `contract`/`installment` langsung.
- `contract` boleh bergantung ke `penalty` lewat **application interface** `EffectivePenaltyPort`
  (`penalty.application`, ADR-019 D1, T15): satu-satunya sumber effective penalty
  (`effective = max(0, penalty_amount − Σ penalty_adjustment − Σ paid penalty)`, invariant 9) untuk
  `InstallmentReceivableService`,
  `ContractCreditCommandService`, `InstallmentStatusRecomputeService`, dan — sejak T30 — read-path jadwal
  `ContractQueryService` serta laporan aging `AgingReportSourceService`. Ini **menggantikan** pembacaan native `penalty_adjustment` di `contract`
  (seam sementara ADR-010, kini **dipensiunkan**). Akibatnya hubungan `contract` ↔ `penalty` menjadi
  dua arah tetapi **hanya lewat port** (`penalty → contract` via `InstallmentPenaltyPort`, `contract → penalty`
  via `EffectivePenaltyPort`), tetap patuh ADR-001 (port, bukan entity/tabel). `EffectivePenaltyService`
  membaca gross `penalty_amount` lewat `InstallmentPenaltyPort` yang sudah ada. Catatan: agregat per-kontrak
  `ContractInstallmentTotals` masih mengabaikan adjustment (follow-up ADR-019; dicatat di T15).
- Sejak T15 (E5, ADR-019, review PR #7) `penalty` menambah dua edge satu arah, keduanya `MANDATORY`
  (join transaksi caller), lewat port milik modul pemilik data:
  - `penalty → payment` lewat `payment.application.PenaltyAllocationPort`: `penalty` membaca Σ alokasi
    `PENALTY` yang POSTED per installment untuk melengkapi formula effective penalty
    (`gross − Σ adjustment − Σ paid penalty`, ADR-019 Context); `payment` tetap pemilik `payment_allocation`
    dan `penalty` dilarang membaca tabel/entity itu langsung.
  - `penalty → contract` lewat `contract.application.InstallmentStatusRecomputePort`: setelah waiver,
    `penalty` meminta `contract` me-recompute status resolusi installment dan menutup kontrak `MATURITY`
    bila sudah lunas; `contract` tetap pemilik `installment` dan melakukan penulisan baris ter-version itu.
- `reporting` boleh baca semua (read-only). Sejak T8, laporan aging membaca receivable angsuran kontrak
  ACTIVE hanya lewat `contract.application.AgingReportSourcePort` (satu arah, `reporting` → port `contract`),
  bukan menyentuh tabel/entity `contract`/`installment` langsung. Alasannya: definisi DPD
  (`contract.domain.InstallmentAging`) dan formula outstanding (`contract.domain.InstallmentBalance`) adalah
  aturan domain milik `contract`; port mengekspos snapshot per installment yang sudah terhitung sehingga
  tidak ada formula kedua (ADR-013 A-7). Bucketing dan agregasi portofolio dilakukan di `reporting`.
- Sejak T12 (E1, ADR-018) modul `settlement` baru boleh bergantung ke `contract` dan `penalty`, keduanya
  satu arah lewat **application interface** modul pemilik data (`contract` dan `penalty` tidak tahu modul
  `settlement`):
  - `settlement → contract` lewat `contract.application.SettlementReceivablePort` (baca jadwal untuk harga
    quote; mengekspos juga bunga terjadwal `interestAmount` yang disembunyikan `InstallmentReceivablePort`
    agar future interest bisa dihitung, ADR-018 D2) dan `ContractCreditPort.availableCredit` (snapshot saldo
    kredit AVAILABLE; T12 belum mengonsumsinya — konsumsi di T13). `settlement` dilarang menyentuh
    tabel/entity `contract`/`installment`/`contract_credit` langsung.
  - `settlement → penalty` lewat `penalty.application.EffectivePenaltyPort` (effective penalty untuk
    `penalty_outstanding`, invariant 9, ADR-018 D8).
    T12 (quote) **tidak** mem-posting jurnal apa pun. Accrue-before-resolve: `settlement` memanggil
    `InstallmentBillingPort` dan `PenaltyAccrualPort` sebelum pricing, di transaksi yang sama (ADR-013,
    ADR-018 D8).
- Sejak T13 (E2, ADR-018) eksekusi settlement menambah tiga seam, semuanya satu arah dan `MANDATORY`
  (join transaksi caller):
  - `settlement → ledger`: eksekusi mem-posting **satu** entry `SETTLEMENT` yang balance lewat
    `LedgerPostingService` (ADR-018 D1/D4). `SETTLEMENT` kini masuk `uq_journal_entry_event` (migrasi V17),
    jadi satu entry non-reversal per settlement ditegakkan di DB, bukan hanya oleh service guard.
  - `settlement → contract` lewat `contract.application.SettlementClosePort` (baru): menutup kontrak
    `SETTLEMENT` dan memindahkan setiap installment terbuka ke `SETTLED` (`settled_amount`/`settled_at`)
    lewat jalur milik `contract` sendiri — **bukan** jalur resolusi pembayaran `InstallmentReceivablePort`
    yang hanya menutup `MATURITY` (ADR-018 D10). `contract` tetap pemilik `installment`/`contract`.
  - `settlement → contract` lewat `ContractCreditPort.consumeAllAvailableCredit` (baru): mengonsumsi
    **seluruh** kredit AVAILABLE kontrak (ADR-018 D10), membalik `contract_credit` AVAILABLE→APPLIED dan
    mengembalikan rincian per sumber; baris `settlement_credit_application` ditulis oleh modul `settlement`
    (pemilik tabelnya). Migrasi V17 mengajari guard saldo/status V14 menghitung `settlement_credit_application`
    agar flip ke APPLIED tetap koheren lintas jalur apply reguler (E3) dan settlement.
    `settlement` tetap dilarang menyentuh tabel/entity `contract`/`installment`/`contract_credit` langsung.
- Sejak T16 (E4 void, ADR-020) write path `payment` menambah dua seam ke `contract` (keduanya satu arah,
  join transaksi caller) dan menggunakan kembali `ledger`. `payment` tetap pemilik `payment`/
  `payment_allocation` dan tidak menyentuh tabel/entity `contract`/`installment`/`contract_credit` langsung:
  - `payment → contract` lewat `contract.application.PaymentVoidPort` (baru): `payment` menghitung berapa
    yang masih di-resolve oleh pembayaran POSTED yang tersisa (dari `payment_allocation`-nya sendiri) lalu
    meminta `contract` (a) mengecek kelayakan void atas setiap installment terkait (D5) dan (b) me-recompute
    `paid_amount`/status installment ke nilai absolut itu — demosi `PAID → PARTIALLY_PAID → PENDING` — serta
    membuka kembali kontrak dari auto-close `MATURITY` ke `ACTIVE` bila void membatalkan penutupan itu (D1.3).
    Berbeda dari `InstallmentReceivablePort.applyPaymentResolution` yang hanya _menaikkan_ paid dan hanya
    _menutup_ kontrak, sehingga void memakai mutator demosi + reopen milik `contract`.
  - `payment → contract` lewat `ContractCreditPort.reverseAvailableCreditFromAllocations` (baru): membalik
    kredit `TITIPAN_NASABAH` yang dibuat EXCESS pembayaran yang di-void **hanya bila masih sepenuhnya
    AVAILABLE** (lock baris `FOR UPDATE` sebelum baca saldo, ADR-020 D4); bila sudah di-apply/dikonsumsi
    settlement → 409 `PAYMENT_CREDIT_CONSUMED` dan tidak menulis apa pun.
  - `payment → ledger` lewat `LedgerPostingService.postReversal` (baru): mem-posting **satu** entry reversal
    yang mencerminkan baris entry PAYMENT asli dengan debit/credit ditukar (`reversal_of_id` di-set, reuse
    `(ref_type=PAYMENT, ref_id)`), sehingga index non-reversal-only `uq_journal_entry_event` (V12) tetap
    mengizinkannya (ADR-002, ADR-020 D1.2). Tidak ada `LedgerRefType` baru. Migrasi V18 melebarkan
    `uq_payment_idempotency` ke semua status agar key pembayaran yang di-void tetap "terpakai" setelah job
    pembersihan T18 (ADR-020 D3, ADR-017).
- Sejak T17 (F1 consistency check, Addendum §7.1/§7.3) langkah keempat job harian — di host daily-job modul
  `penalty` — mereka-reconcile tiga invariant uang per kontrak secara **read-only** dan mencatat drift ke
  `reconciliation_exception` (tanpa koreksi). Ia membaca tiap sisi check hanya lewat port modul pemilik data,
  masing-masing satu arah dan read-only; tidak ada modul yang query tabel modul lain:
  - `penalty → payment` lewat `payment.application.PaymentReconciliationPort` (baru): `Σ` amount payment
    POSTED dan `Σ` amount alokasi POSTED per kontrak (check A `PAYMENT_CASH`, check B `ALLOCATION_PAYMENT`).
    Menambah satu konsumer ke edge `penalty → payment` yang sudah ada (sejak T15 `PenaltyAllocationPort`);
    `payment` tetap pemilik `payment`/`payment_allocation`.
  - `penalty → ledger` lewat `ledger.application.ContractLedgerBalancePort` (baru): saldo net
    `Σ(debit − credit)` `KAS` dari entry `PAYMENT` (check A) dan net `PIUTANG_POKOK + PIUTANG_BUNGA +
PIUTANG_DENDA` (check C `RECEIVABLE_INSTALLMENT`). `ledger` tidak bergantung ke modul lain; ini seam baca
    tambahan di `ledger.application`, dan `penalty` sudah bergantung ke `ledger` untuk posting.
  - `penalty → contract` lewat `contract.application.ReceivableReconciliationPort` (baru): residual
    receivable kontrak (`Σ` principal + bunga recognized + denda effective residual), memakai kembali formula
    "outstanding" tunggal `InstallmentBalance` yang di-net adjustment aktif lewat `EffectivePenaltyPort` —
    tanpa formula kedua (ADR-019 D4). Arahnya sama dengan seam `contract` lain yang sudah dipakai job
    (`ActiveContractListingPort`, `InstallmentPenaltyPort`); `contract` tetap pemilik `installment`.
    Perbandingan expected-vs-actual ada di engine murni `ConsistencyCheckEngine`; tidak ada migrasi (tabel +
    CHECK `ck_recon_exception_type` sudah di V1) dan tidak ada endpoint HTTP (job in-process `SYSTEM`;
    `GET /reports/reconciliation-exceptions` ditunda ke modul `reporting`).
- `shared/security` (bukan modul domain) membaca tabel `app_user` lewat satu query PK terindeks per request
  terautentikasi, untuk menolak token yang `sub`-nya bukan user aktif (ADR-022 D1/D6). Ini **satu-satunya
  pengecualian** yang disahkan terhadap aturan "tidak ada modul yang query tabel modul lain": modul `auth`
  akan membuat `shared` bergantung ke `auth` (arah terbalik), sedangkan `auth` sendiri bergantung ke
  `shared`. `shared/observability` menyediakan `X-Request-Id`/log ECS, `shared/idempotency` menyediakan
  klaim idempotensi; keduanya tidak memiliki tabel domain.

Jika suatu saat di-split microservice, seam sudah siap di interface antar-module.

## 1.1 Business-facing number generation

- `contract_no`, `payment_no`, `quote_no`, dan `settlement_no` memakai format readable dan unique; generator harus transactional dan concurrency-safe.
- MVP memakai tabel `document_number_counter(counter_key, last_value)` dengan row lock/atomic increment, bukan counter di memory.
- Format: `MF-YYYYMM-XXXX` (contract), `PAY-YYYYMM-XXXX` (payment), `Q-YYYYMMDD-XXXX` (quote), `SET-YYYYMM-XXXX` (settlement).
- Number generation boleh consume angka saat transaksi rollback; uniqueness lebih penting daripada gapless numbering.

## 2.0 Business Date / Server Time

- `paid_at`, `quoted_at`, `executed_at`, dan timestamp audit berasal dari server/application clock.
- Payment `paid_at` tidak boleh future. Backdated payment tidak didukung pada MVP; request yang mencoba backdate di luar business date policy ditolak.
- Semua business date menggunakan injected `Clock` Asia/Jakarta agar deterministic di test.
- Timestamp yang di-serialisasi ke wire memakai timezone bisnis yang sama
  (`spring.jackson.time-zone=Asia/Jakarta`, ADR-010) sehingga respons pertama dan respons hasil replay
  `response_json` (§2.5) identik — Jackson merender `OffsetDateTime` pada zone mapper, bukan pada offset aslinya.

## 2. Konvensi Teknis

### 2.1 Uang

- Gunakan `BigDecimal` dengan skala eksplisit 2. **Dilarang float/double.**
- DB: `NUMERIC(19,2)` untuk amount; rate: `NUMERIC(7,4)`. Semua rate memakai fraction desimal: 1.5% disimpan sebagai `0.0150`.
- Magnitude money/rate dari request divalidasi terhadap domain kolom (`NUMERIC(19,2)` / `NUMERIC(7,4)`) **sebelum** rescale apa pun, memakai `precision()`/`scale()` saja (tanpa meng-expand nilai), via `shared.money.DecimalBounds`. Nilai di luar domain → 400 `VALIDATION_ERROR`. Mencegah amplifikasi scientific notation seperti `1e100000000` (ADR-016, CWE-400).
- Semua perhitungan rounding kecuali final: gunakan `RoundingMode.HALF_EVEN` (banker's rounding) dan dokumentasikan.
- **FLAT:** round periodic principal/interest ke 2 desimal untuk periode 1..n-1; periode terakhir menyerap residual agar Σ principal = plafon dan Σ interest = bungaTotal.
- **EFFECTIVE:** hitung payment/bunga dengan precision tinggi; round interest & principal tiap periode 1..n-1, lalu periode terakhir menyerap residual principal. Last installment amount dapat berbeda sedikit dari payment nominal agar Σ principal = plafon dan semua rounded components balance.

### 2.2 API

- REST, base `/api/v1/...`.
- Semua endpoint mutasi yang dapat di-retry menerima header `Idempotency-Key`; minimal wajib pada POST payment, settlement, dan credit application. Key bersifat endpoint-scoped. **B5 (ADR-007): POST `/contracts` juga mewajibkan header ini** — create kontrak tidak boleh menghasilkan kontrak kedua pada retry. Aktivasi tidak memakai key karena idempotent secara state machine (ADR-006).
- Request/response envelope konsisten:
  ```json
  { "data": ..., "error": null }
  { "data": null, "error": { "code": "PAYMENT_NOT_FOUND", "message": "..." } }
  ```
- Pagination: `page`, `size`, `sort`. **B5:** token `sort` memakai nama field wire (snake_case): `created_at` (default, `desc`), `contract_no`, `status`; token lain ditolak `400 VALIDATION_ERROR`. Envelope halaman: `content`, `page`, `size`, `total_elements`, `total_pages`.
- Penamaan JSON di wire adalah **snake_case** (`spring.jackson.property-naming-strategy=SNAKE_CASE`, ADR-006) agar konsisten dengan dokumen ini dan frontend spec. Nilai uang dikirim sebagai desimal biasa (`20000000.00`), bukan notasi ilmiah.
- Error codes enum per-module.

### 2.3 Konkurensi & Transaksi

- Optimistic locking: kolom `version` (@Version) pada semua entity yang bisa dikonfirmasi bersamaan
  (Contract, Installment, Payment).
- Transaksi boundary di application service (`@Transactional`), bukan di controller.
- Semua write path mengikuti pola: validasi → hitung → tulis aggregate → tulis jurnal → commit.
- Khusus `POST /api/v1/payments` sejak T4 (ADR-014), supplier idempotensi menangkap satu business date lalu
  menjalankan satu transaksi dengan urutan keputusan finansial **billing → penalty accrual → snapshot
  receivable → allocation → resolution → jurnal `PAYMENT`**. Persistensi `payment`/`payment_allocation`
  dilakukan dari hasil allocation sebelum resolution. Billing dan accrual memakai tanggal yang sama dan
  bergabung lewat propagasi `REQUIRED`; claim idempotensi, jurnal `BILLING`/`PENALTY_ACCRUAL`/`PAYMENT`,
  accrual, payment, allocation, serta perubahan installment commit atau rollback bersama. Replay key yang
  sudah `COMPLETED` mengembalikan respons tersimpan tanpa mengeksekusi supplier atau menangkap tanggal baru.
  Sejak T5, race unique `(installment_id, accrual_date)` job-versus-payment dan konflik optimistic-lock
  di-retry oleh `PaymentRetryingService` di luar batas `@Transactional` milik
  `PaymentApplicationService.create`: setiap percobaan membuka transaksi baru dan mengambil claim
  idempotensi baru (maksimum 3 percobaan dengan jeda 50 lalu 150 ms, Addendum §5), sehingga percobaan yang gagal
  tidak pernah mengonsumsi key. Percobaan yang habis mengembalikan 409 `CONCURRENT_MODIFICATION`; kegagalan
  validasi/state bisnis tidak di-retry.
- Daily servicing T3 memakai ShedLock JDBC dengan DB time dan keep-alive lease; cron dan explicit backfill
  masuk lewat locked public facade yang sama, sehingga invocation kedua dengan nama lock yang sama dilewati.
  Cron property berjalan di Asia/Jakarta dan bernilai `-` di test agar tidak menyentuh fixture secara
  asinkron.
- Batch orchestrator tidak transactional. Setiap contract diproses melalui bean terpisah dengan satu
  transaksi baru berurutan **billing → penalty**; kedua langkah/jurnal commit atau rollback bersama, sedangkan
  contract lain tetap independen. Optimistic-lock dan unique-key conflict (SQLSTATE `23505`) di-retry paling
  banyak 5 total attempts; error deterministik tidak di-retry. Contract yang menjadi non-ACTIVE saat menunggu
  gilirannya dilewati. Antar attempt orchestrator menjeda pada urutan tetap **50/150/400/1000 ms** (empat
  jeda di antara lima attempt) lewat `shared.concurrency.Sleeper` (CR-09, Addendum §5).
- Daftar kontrak ACTIVE dibaca secara **keyset (seek) paging** lewat `ActiveContractListingPort`
  (`findActiveContractIdsAfter`), bukan satu `List<UUID>` penuh; ukuran halaman dari
  `serfira.jobs.daily-servicing.batch-size` (default 500). Orchestrator iterasi halaman demi halaman sampai
  halaman lebih pendek dari batch-size, memproses setiap contract tepat sekali (CR-08).
- Robustness `job_run` (CR-07): di awal setiap run yang memegang lock, row `RUNNING` sisa crash
  (billing/penalty-accrual/aging) ditandai `ABANDONED`; bila loop melempar, ketiga row diselesaikan `FAILED`
  sebelum exception di-rethrow. `ABANDONED` adalah status terminal ketiga khusus crash recovery (V13).
- Sejak T6, langkah ketiga **aging** berjalan untuk setiap contract di loop yang sama, **setelah** transaksi
  billing → penalty, dalam transaksinya sendiri (`DailyServicingContractProcessor.age` →
  `contract.application.InstallmentAgingPort`, `Propagation.MANDATORY`). Aging tetap dijalankan walau
  billing → penalty contract itu gagal (kondisi `OVERDUE` tidak bergantung pada denda), punya retry 5
  attempts sendiri dengan classifier yang sama, dan tidak pernah menulis uang. Transisi dan formulanya milik
  modul `contract` (ADR-013 A-4/A-5 dan implementation note T6).
- Lifecycle `job_run` ditulis `REQUIRES_NEW`, terpisah dari transaksi finansial per contract, dengan actor
  `SYSTEM`. V10 menyimpan `business_date` eksplisit agar backfill dapat dibedakan dari waktu eksekusinya.

### 2.4 Audit

- Semua tabel punya `created_at`, `created_by`, `updated_at`, `updated_by` (envers dibolehkan, tapi
  ledger cukup dengan jurnal pembalik — jangan pernah UPDATE/DELETE journal_lines).

### 2.5 Idempotency

- Tabel `idempotency_keys(key, endpoint, request_hash, response_json, status, created_at, expires_at, request_id)`.
- `request_hash` = digest keyed (HMAC-SHA-256 atas canonical JSON request + endpoint scope). Dokumen ini semula menyebut "SHA-256"; karena payload dapat memuat PII, implementasi B5 memakai HMAC keyed dari ADR-004 (ADR-007) — semantik kesamaan identik dan nilainya tidak reversible.
- Request sama + key sama → return response tersimpan tanpa eksekusi ulang.
- **Implementasi B5 (ADR-007):** claim dilakukan dengan `INSERT … ON CONFLICT (endpoint, key)
DO NOTHING` di dalam transaksi bisnis (`Propagation.MANDATORY`), `status` bernilai `COMPLETED` setelah respons
  tersimpan, `expires_at` = waktu claim + `IDEMPOTENCY_KEY_RETENTION_DAYS` (config), dan mekanisme hidup di
  `com.serfira.shared.idempotency` supaya C3 (payment/settlement/credit application) memakainya ulang. Cleanup
  baris kedaluwarsa diimplementasikan sebagai langkah job harian di T18 (V21); dibahas di butir lifecycle
  di bawah.
- **Lifecycle key (T24, ADR-017, mengubah ADR-007 keputusan 9).** Key bersifat **sekali pakai selamanya**
  per endpoint; retention hanya membatasi berapa lama respons tersimpan di-replay. Di dalam window: retry
  identik → replay respons tersimpan; body berbeda dengan key sama → 409 `CONFLICT`. Setelah window lewat:
  reuse key ditolak **409 `IDEMPOTENCY_KEY_EXPIRED` sebelum supplier dijalankan**, tanpa billing/accrual/tulis
  bisnis apa pun — tidak ada takeover dan operasi tidak pernah dieksekusi ulang. Setelah cleanup F4 (T18)
  menghapus baris `idempotency_keys`, backstop permanen (`uq_contract_idempotency`, `uq_payment_idempotency`)
  mengembalikan kode yang sama. `PaymentConflictClassifier` tidak me-retry `uq_payment_idempotency` (hanya
  `uk_penalty_accrual` dan optimistic-lock yang retryable).
- **Implementasi C3 (ADR-010):** `POST /api/v1/payments` memakai endpoint scope `POST /api/v1/payments` dengan
  canonical JSON dari field request (amount dinormalkan ke skala 2). Claim berjalan di dalam `@Transactional`
  application service, jadi kegagalan operasi me-rollback claim dan retry dengan key yang sama tetap sah
  memperbaiki body; retry identik mengembalikan `response_json` tersimpan tanpa eksekusi ulang (tanpa baris
  `payment`/jurnal kedua).

### 2.6 Keamanan & Autentikasi

- **Login (T21a, ADR-022).** `POST /api/v1/auth/login` `{username, password}` → `{access_token, refresh_token}`
  dalam envelope standar. Password diverifikasi dengan Argon2id; `SYSTEM` (nilai `{disabled}` V8) tidak pernah
  bisa login. Semua kegagalan kredensial adalah **401** generik yang sama (anti-enumerasi), dan hash tidak
  pernah di-log atau dikembalikan.
- **Kapasitas verifikasi Argon2 (ADR-022 D9).** Verifikasi password dibatasi `Semaphore` berukuran
  `serfira.auth.argon2.max-concurrent-verifications` (default 2, `>= 1`). Bila permit habis, login gagal cepat
  dengan **503 `AUTHENTICATION_CAPACITY_EXCEEDED`**, bukan mengantre KDF 16 MiB tanpa batas.
- **Roles & authorization (T7, ADR-015).** Authority berasal dari claim `roles` berupa array string
  (case-sensitive, sesuai `ck_app_user_role`); claim non-array dianggap tanpa role. Token yang memuat
  `SYSTEM` ditolak seluruhnya (403) — `SYSTEM` hanya untuk job in-process. Matrix Addendum §3.4 ditegakkan di
  satu tabel matcher pada `SecurityFilterChain` yang diakhiri `denyAll()`; endpoint yang tidak terdaftar
  otomatis 403. Presedens status: token tidak ada / tidak valid (signature salah, `alg: none`, kedaluwarsa,
  tanpa `exp`, `sub` hilang atau bukan UUID, atau `sub` bukan `app_user` aktif — ADR-022 D6) → 401
  `UNAUTHORIZED`; permintaan login yang melewati batas kapasitas Argon2 → 503
  `AUTHENTICATION_CAPACITY_EXCEEDED`; login ke akun yang sedang terkunci → 423 `ACCOUNT_LOCKED` (ADR-022 D12);
  token valid tanpa role yang diizinkan → 403 `FORBIDDEN`; request anonim ke path tak terdaftar → 401. Request
  yang ditolak tidak menulis apa pun.
- Password hash: Argon2id, prefix wajib `$argon2id$`. Jangan simpan password plaintext.
- Access token JWT short-lived (15 menit), HS256, `sub` = `app_user.id` (ADR-022 D4).
- **Refresh token (T21b, ADR-022 D11).** Random 128-bit, disimpan hashed (`token_hash`) dan **rotated on use**:
  `POST /api/v1/auth/refresh` menerbitkan access + refresh baru, mengisi `revoked_at` baris lama dan
  menautkannya ke pengganti lewat `replaced_by_id`. Rotasi diamankan dengan update bersyarat
  (`… WHERE revoked_at IS NULL`) sehingga dari dua refresh paralel tepat satu yang menang; token yang
  diputar ulang (replay), kedaluwarsa, sudah di-logout, atau milik `app_user` non-aktif ditolak dengan 401
  yang sama dan tidak menerbitkan apa pun. Masa berlaku 7 hari dan **sliding** (di-reset setiap rotasi); tidak
  ada session cap absolut. `POST /api/v1/auth/logout` me-revoke token yang dikirim dan **idempoten** (token
  tak dikenal / sudah di-revoke tetap 200 dengan `data: null`).
- **Brute-force lockout (T21b, ADR-022 D12).** Login gagal menambah `failed_login_attempts`; mencapai
  threshold mengisi `locked_until` = waktu kegagalan yang melewati ambang + window. Threshold dan window
  adalah konfigurasi (`serfira.auth.lockout.max-attempts`, default 5; `serfira.auth.lockout.window`, default
  `PT15M`). Akun terkunci menjawab **423 `ACCOUNT_LOCKED`** dengan sisa menit, bahkan bila password benar, dan
  tidak membayar verifikasi Argon2; login sukses mereset counter dan lock. Lock yang sudah lewat dianggap
  selesai: kegagalan berikutnya memulai hitungan baru dari 1, dan kegagalan yang datang saat lock masih aktif
  tidak memperpanjang `locked_until`.
  `AuthService.login` sengaja **tidak** berjalan dalam satu transaksi: lookup user singkat, verifikasi Argon2
  tanpa memegang koneksi DB, lalu dua transaksi independen — `LoginAttemptRecorder` (`REQUIRES_NEW`, lock baris
  `PESSIMISTIC_WRITE`) untuk kegagalan dan `SuccessfulLoginRecorder` untuk sukses. Recorder sukses memegang
  lock baris yang sama, memeriksa ulang lock/aktif/interaktif, mereset counter, dan menyimpan refresh token
  dalam satu commit; access token baru dicetak setelah commit tersebut. Dengan begitu kegagalan paralel
  terserialisasi (tidak ada lost update pada counter) dan request sukses yang kalah balapan tidak pernah
  menerbitkan token untuk akun yang sudah terkunci.
- **Transport refresh token (X-14, ADR-022 D10).** Token dikembalikan di **body respons**
  (`{access_token, refresh_token}`) untuk login maupun refresh; tidak ada cookie, sehingga tidak ada CSRF
  handling maupun CORS ber-kredensial. Frontend menyimpan keduanya di memory. Cookie `HttpOnly` ditunda
  sampai ada browser client nyata (G2).
- **Browser boundary (T35, ADR-023).** Browser memanggil API **langsung** (tanpa BFF/proxy). CORS
  didefinisikan sekali di `ResourceServerSecurityConfiguration`: origin persis dari
  `serfira.security.cors.allowed-origins` (default kosong = tidak ada origin; divalidasi saat startup,
  wildcard dan penulisan non-kanonik termasuk IPv6 non-kanonik ditolak), method `GET,POST`, header
  `Authorization`/`Content-Type`/`Idempotency-Key`/`X-Request-Id`, `X-Request-Id` di-expose,
  `allowCredentials=false`, preflight cache 30 menit. Preflight
  dijawab sebelum matriks otorisasi; origin non-allowlist ditolak 403 tanpa header CORS. CORS bukan
  mekanisme otorisasi — matriks peran tetap penegaknya.
- **Dev-key production guard (T21b, ADR-022 D13).** Saat profil `prod`/`production` aktif (termasuk lewat
  `spring.profiles.default`), aplikasi gagal start bila `serfira.security.jwt.secret-base64`,
  `serfira.security.pii.encryption-key`, atau `serfira.security.pii.hmac-key` masih bernilai default publik
  `docker-compose.yml`. Perbandingan dilakukan pada byte hasil decode Base64, sehingga nilai yang hanya beda
  padding tetap tertangkap; nilai konfigurasi yang bukan Base64 valid dianggap bukan default. Pesan menyebut
  nama properti (tidak pernah nilainya). Profil dev/test/demo tetap berjalan dengan nilai default tersebut.
- Access token disimpan di memory oleh frontend, bukan localStorage.
- `created_by`/`updated_by` untuk user request berasal dari JWT principal; job memakai seeded non-interactive user `SYSTEM`. Semua audit actor menggunakan UUID `app_user.id`.

### 2.7 Outbox Pattern

- Event domain (`PaymentReceived`, `ContractClosed`, dll) ditulis ke tabel `outbox_events`
  dalam transaksi yang sama dengan bisnis.
- Publisher terpisah mengirim ke Kafka lalu mark SENT.
- Tabel `outbox_events` boleh dibuat sejak foundation, tetapi publisher/Kafka tetap ditunda ke Fase 3. Fase 1–2 tidak membutuhkan Kafka.

## 3. Design Ledger (double-entry)

```
accounts        (code, name, type: ASSET|LIABILITY|EQUITY|INCOME|EXPENSE)
# seed tambahan: TITIPAN_NASABAH (LIABILITY), BIAYA_PENGHAPUSAN_PIUTANG (EXPENSE), DISKON_PELUNASAN (EXPENSE/contra-receivable), PENDAPATAN_ADMIN (INCOME)
# `DISKON_PELUNASAN` adalah contra-receivable (disajikan sebagai expense untuk simplifikasi portofolio).
journal_entries (id, entry_date, ref_type, ref_id, description, reversal_of_id nullable, posted_at)
journal_lines   (id, journal_entry_id, account_code, debit, credit, contract_id nullable)
```

Invariant (dienforce DB + test):

1. Σ debit = Σ kredit per journal_entry (check constraint via trigger atau validated di service layer + test).
2. Setiap journal_lines.account_code merujuk accounts yang ada (FK).
3. Tidak ada UPDATE/DELETE pada journal_entries & journal_lines — koreksi = journal baru
   dengan `reversal_of_id` mengacu ke jurnal asli.

Posting rules (contoh):
| Event | Debit | Kredit |
|---|---|---|
| Aktivasi kontrak (disburse) | PIUTANG_POKOK | KAS |
| Billing bunga installment | PIUTANG_BUNGA | PENDAPATAN_BUNGA |
| Penalty accrual harian | PIUTANG_DENDA | PENDAPATAN_DENDA |
| Penalty catch-up setelah void | PIUTANG_DENDA | PENDAPATAN_DENDA |
| Terima payment regular | KAS | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA / TITIPAN_NASABAH |
| Waive/reduce denda (E5, ADR-019 D3) | BEBAN_WAIVER_DENDA | PIUTANG_DENDA |
| Settlement (E2, ADR-018 D4) — **satu** entry | KAS + TITIPAN_NASABAH | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA + PENDAPATAN_BUNGA + PENDAPATAN_ADMIN |
| Excess payment diterima | KAS | TITIPAN_NASABAH |
| Credit diaplikasikan ke installment | TITIPAN_NASABAH | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |

**C3 (ADR-010):** "Terima payment regular" direalisasikan sebagai **satu** `journal_entry` per pembayaran
(`ref_type = PAYMENT`, `ref_id = payment.id`, `entry_date = paid_at`): satu baris debit `KAS` sebesar jumlah
pembayaran, lalu baris kredit diagregasi per komponen dengan urutan tetap
`PIUTANG_DENDA → PIUTANG_BUNGA → PIUTANG_POKOK → TITIPAN_NASABAH` (komponen bernilai nol dilewati). Setiap
baris membawa `contract_id` sehingga rekonsiliasi per kontrak tidak perlu join ke `payment`. Credit application
(E3) dan void (E4) menyusul sebagai jurnal terpisah, bukan sebagai UPDATE jurnal pembayaran ini.

**C4 (ADR-011):** "Billing bunga installment" direalisasikan sebagai **satu** `journal_entry` per installment —
`ref_type = BILLING`, `ref_id = installment.id`, `entry_date = due_date` (pukul 00:00 Asia/Jakarta) — debit
`PIUTANG_BUNGA` dan kredit `PENDAPATAN_BUNGA` sebesar **selisih** `interest_amount − recognized_interest_amount`,
dengan `contract_id` di kedua baris. Billing dijalankan (a) di dalam transaksi `POST /payments` sebelum alokasi
dihitung, sehingga pembayaran **pada** due date menemukan bunga yang sudah receivable, dan (b) sebagai step
mandiri `InstallmentBillingPort.billDueInterest(contractId, businessDate)` yang siap dibungkus job harian (D2).
Installment dengan `due_date > business date`, `SETTLED`/`WRITTEN_OFF`, kontrak non-ACTIVE, dan periode berbunga
nol tidak pernah di-bill — sehingga re-run tidak mem-posting apa pun.

**D1 (ADR-012):** "Penalty accrual harian" direalisasikan sebagai **satu** `journal_entry` per **hari terlambat** —
`ref_type = PENALTY_ACCRUAL`, `ref_id = penalty_accrual.id`, `entry_date = accrual_date` (pukul 00:00 Asia/Jakarta)
— debit `PIUTANG_DENDA` = kredit `PENDAPATAN_DENDA`, dengan `contract_id` di kedua baris. Untuk business date `D`,
step `PenaltyAccrualPort.accrueDuePenalty(contractId, businessDate)` menagih setiap tanggal `x` dengan
`due_date + grace + 1 ≤ x ≤ D` yang **belum punya baris accrual**, sebesar
`round((pokok + bunga yang sudah di-bill − yang sudah dibayar/settled/write-off) × penalty_rate_daily, HALF_EVEN, 2)`
dan `days_late = max(0, x − due_date − grace)` (TS §4.3). Kelayakan bersifat **per tanggal**, bukan kumulatif:
re-run dan backfill gratis, base yang turun karena pembayaran sebagian tidak menekan accrual hari berikutnya, dan
hari yang belum tertagih tetap bisa ditagih setelah void (Addendum §6). Installment `SETTLED`/`WRITTEN_OFF` dan
hari di dalam grace tidak pernah ditagih; denda tidak berbunga di atas denda.

**E5 (ADR-019 D2/D3, T15):** "Waive/reduce denda" lewat `POST /api/v1/penalty-adjustments` (ADMIN_OPERASIONAL)
menambah satu baris append-only `penalty_adjustment` (WAIVE/REDUCE, immutable via trigger V1) dan **satu**
`journal_entry` koreksi — `ref_type = PENALTY_WAIVER`, `ref_id = penalty_adjustment.id` — debit
`BEBAN_WAIVER_DENDA` = kredit `PIUTANG_DENDA` sebesar amount, dengan `contract_id` di kedua baris. Koreksi ini
membalik **receivable** denda, bukan histori accrual (baris `penalty_accrual` tetap append-only). Effective
penalty tidak pernah < 0 (invariant 9): service menolak over-waive dengan 409 `CONFLICT` sebelum menulis apa pun,
dan cap trigger V15 (`Σ penalty_adjustment.amount ≤ installment.penalty_amount`) adalah backstop DB-nya. Approver
(`approved_by`) berasal dari JWT `sub`, bukan body. `BEBAN_WAIVER_DENDA` adalah akun EXPENSE baru (di-seed V15),
dipilih alih-alih `DISKON_PELUNASAN` yang punya makna settlement-rebate berbeda (ADR-018 D3).

| Write-off piutang | BIAYA_PENGHAPUSAN_PIUTANG | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |

**E2 (ADR-018 D1/D3/D4, T13):** settlement wajib membuat transaction record (`settlement`) + allocation
(`settlement_allocation`, hanya resolusi receivable PENALTY/INTEREST/PRINCIPAL, D5) + **satu** `journal_entry`
yang balance (`ref_type = SETTLEMENT`, `ref_id = settlement.id`). Entry itu: debit `KAS` (cash) + `TITIPAN_NASABAH`
(kredit yang dikonsumsi); kredit `PIUTANG_POKOK`/`PIUTANG_BUNGA`/`PIUTANG_DENDA` (receivable yang di-clear) +
`PENDAPATAN_BUNGA` (bunga berjalan + future interest yang di-charge, D2) + `PENDAPATAN_ADMIN` (admin fee).
Komponen bernilai nol tidak menghasilkan baris. Rebate atas future interest yang belum billed **tidak**
di-recognize dan **tidak** memunculkan baris `DISKON_PELUNASAN` di jalur normal (D3) — tidak ada receivable
`PIUTANG_BUNGA` untuk di-discount, karena future interest tidak pernah di-recognize sebelum billed. Admin fee
diakui ke `PENDAPATAN_ADMIN`.

Karena ini servicing-saja (bukan full finance), revenue recognition disederhanakan: bunga scheduled di-recognize saat billing pada due date; bunga berjalan settlement yang belum billed di-recognize saat settlement. Future scheduled interest tidak pernah di-recognize sebagai receivable sebelum billed.

## 4. Perhitungan Inti

### 4.1 Flat

```
bungaTotal    = plafon × ratePerBulan × tenor
pokokPerBln   = plafon / tenor
angsuranPerBln= pokokPerBulan + bungaTotal/tenor
```

### 4.2 Efektif (anuitas)

```
i = ratePerBulan
A = plafon × i × (1+i)^n / ((1+i)^n − 1)
bungaBulanKeK = outstanding_{k−1} × i
pokokBulanKeK = A − bungaBulanKeK
```

Guard: i = 0 → A = plafon/n. Guard: (1+i)^n overflow → gunakan `BigDecimal#pow` aman atau loop.

### 4.3 Denda

```
hariTelat = max(0, today − dueDate − gracePeriod)
denda_harian = rateHarian × saldo tagihan pokok+bunga yang belum dibayar
recognized_penalty = Σ denda_harian yang sudah diakui
penalty_amount pada Installment = recognized_penalty (gross, sebelum payment/adjustment). `effective_penalty = penalty_amount − active penalty allocations − penalty adjustments`.
```

Denda dihitung job harian dan **diakui incremental** sebagai `PenaltyAccrual.amount` per tanggal. Sejak T4,
jalur pembayaran juga menjalankan lazy accrual melalui tanggal bisnis yang sama sebelum mengambil snapshot dan
menerapkan resolusi (ADR-014); ini bukan implementasi formula kedua. `PenaltyAccrual.amount` adalah delta hari
itu, **bukan cumulative snapshot**, sehingga tidak double-count saat dijumlah. Job atau jalur pembayaran
mem-posting delta ke ledger dan update gross `penalty_amount`. Recalc setelah void hanya menambah catch-up
accrual bila expected recognized penalty lebih besar dari nilai yang sudah diakui.

**Invariant accrue-before-resolve (ADR-013):** setiap alur yang menurunkan base denda sebuah installment
(pembayaran, settlement, write-off) wajib membilling dan meng-accrue denda melalui tanggal bisnisnya di
transaksi yang sama, sebelum resolusi itu diterapkan. Ini menjamin tanggal `D` selalu ditagih dengan base
yang berlaku pada awal `D` (A-1), karena tidak ada resolusi yang menyisip sebelum accrual berjalan. Void
adalah satu-satunya alur yang menaikkan base retroaktif, dan tidak memicu re-pricing tanggal yang sudah
ter-accrual (Addendum §6, ADR-012 keputusan 7, dipertegas ADR-013).

### 4.4 Settlement quote

Quote harus menghindari future scheduled interest yang belum menjadi tagihan. Snapshot minimal:

```
outstandingPokok      = Σ pokok residual semua installment aktif
unpaidBilledInterest  = Σ bunga yang sudah billed tetapi belum terbayar
bungaBerjalan         = bunga periode aktif sejak period start sampai settlement, ACT/30, cap 30 hari
penaltyOutstanding    = gross penalty recognized − active penalty payment − penalty adjustment
rebateBase            = unpaidBilledInterest + bungaBerjalan
rebate                = konfigurasi rate/amount, dibatasi maksimal rebateBase
adminFee              = konfigurasi
availableCredit       = saldo AVAILABLE contract credit
grossSettlement       = outstandingPokok + unpaidBilledInterest + bungaBerjalan + penaltyOutstanding − rebate + adminFee
cashDue               = grossSettlement − availableCreditUsed
```

`bungaBerjalan` tidak boleh menghitung ulang bunga yang sudah recognized/billed. `availableCredit` tidak otomatis diaplikasikan ke installment regular, tetapi **boleh dipakai eksplisit dalam settlement** dan dicatat sebagai consume-credit journal. Quote mempunyai `quote_id`, `quoted_at`, `valid_until`, semua component snapshot, dan snapshot contract version. `credit_used` default 0 dan hanya boleh <= available credit; settlement dapat memakai credit secara eksplisit, sedangkan regular credit application hanya boleh mengurangi recognized receivable. Execution wajib recalculate current components; mismatch atau expired quote → `STALE_SETTLEMENT_QUOTE`; jika contract tidak lagi ACTIVE, gunakan error state contract yang sesuai.

## 5. Database & Migration

- PostgreSQL 16, Flyway migration per rilis, **tidak ada** perubahan skema manual.
- Index: installments(contract_id, due_date), payments(contract_id), journal_lines(account_code, entry_date), penalty_accrual(installment_id, accrual_date), settlement(contract_id, executed_at), idempotency_keys(endpoint, key).
- Constraint penting: amount >= 0, `paid_amount + settled_amount + written_off_amount <= recognized_total`, unique(contract_id, period_no), unique(installment_id, accrual_date), contract-credit application + settlement-credit consume <= source credit available, dan allocation total untuk setiap POSTED payment harus sama dengan payment amount. Excess wajib masuk `TITIPAN_NASABAH`.

## 6. Testing Strategy

1. **Unit test engine** (prioritas tertinggi, target >90% coverage): pure Java tanpa Spring.
   - Tabel angsuran flat & efektif dibandingkan expected values (hitung pakai spreadsheet, hardcode expected).
   - Edge: i=0, n=1, due date 31→28/29 Feb, leap year, rounding, last-installment adjustment.
   - Payment/settlement/write-off resolution: paid vs settled vs written-off tidak tercampur.
   - Alokasi pembayaran: 20+ skenario (sebagian, lebih, telat, denda beda periode).
2. **Integration test** dengan Testcontainers (PostgreSQL nyata):
   - Repositories, transaksi, constraint.
   - Idempotency double-post.
   - Settlement stale quote / contract-version mismatch.
   - Ledger balance invariant setiap transaksi uang, termasuk billing, penalty accrual, settlement, credit consume, dan write-off.
3. **API test** (MockMvc / RestAssured): happy path + error contract.
4. **Consistency check test**: setiap skenario pembayaran/settlement/write-off menguji tiga rekonsiliasi: payment-vs-cash journal, allocation-vs-payment, dan receivable-vs-installment state.
5. **PII test**: NIK ciphertext tidak menentukan uniqueness; `nik_hash` unik dan NIK tidak muncul di structured log.

## 7. Struktur Repo

```
serfira-core/
├── docs/               ← PRD, tech spec, domain model (file ini)
├── src/main/java/com/multifinance/{contract,payment,penalty,settlement,ledger,reporting,shared}
├── src/main/resources/db/migration/   ← Flyway
├── src/test/java/...   ← mirror package
├── docker-compose.yml  ← app + postgres (dev)
└── README.md           ← cara run, desain, demo script
```

## 8. Keputusan yang Sengaja Dibuat (untuk ditanyakan saat interview)

1. Modular monolith dulu, seam siap microservices.
2. Double-entry ledger sejak hari 1 — mahal dikit, tapi menyelamatkan dari bug finansial tersembunyi.
3. Denda disimpan, bukan dihitung on-the-fly — keputusan konsistensi ledger vs simplicity.
4. Reversal, bukan delete — jejak audit penuh.
5. Kafka ditunda — outbox pattern tetap ada supaya transisi mudah.
