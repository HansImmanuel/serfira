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
- [x] T23 — Penyelarasan dependensi Boot 4: ShedLock `7.10.1` dan springdoc `3.1.1`. Skema tabel `shedlock` V9 sama dengan DDL PostgreSQL 7.10.1, jadi tanpa migrasi. `OpenApiSmokeIT` baru membuktikan `/v3/api-docs` dan Swagger UI publik tanpa token. `OpenApiSmokeIT` + `DailyServicing*IT` 10 pass, full test 463 pass, `check` pass
- [x] T7 — RBAC + identitas JWT fail-closed (ADR-015): authority dari claim `roles` (array string, `AppRole` cermin `ck_app_user_role`), token ber-`SYSTEM` ditolak seluruhnya. Validator decoder mewajibkan `exp` dan `sub` berupa UUID, jadi token tanpa aktor → 401 sebelum terautentikasi (tutup CR-01). Matrix Addendum §3.4 di satu tabel matcher diakhiri `denyAll()`, plus `dispatcherTypeMatchers(ERROR).permitAll()` agar error dispatch mempertahankan statusnya. Probe `AuditedAssetTestController` dipensiunkan; atribusi `created_by` pindah ke `POST /contracts`. Helper token tes jadi `support/TestJwts`. Tes baru: `RolesClaimAuthoritiesConverterTest` (12), `EndpointRoleMatrixIT` (38 sel + deny-by-default + denied-writes-store-nothing), `JwtAuthenticationIT` (16), `ErrorDispatchSecurityIT` (4, server `RANDOM_PORT`). Full test 529 pass, `check` pass. Tanpa perubahan skema/dependensi
- [x] T8 — Aging report (ADR-013 impl note T8): modul `reporting` read-only baru, `GET /api/v1/reports/aging`
      mengembalikan bucket Current/1–30/31–60/61–90/>90 sebagai total portofolio dan per kontrak (basis
      per-installment, ACTIVE saja, `as_of` hari-ini saja → 400 `INVALID_AS_OF_DATE`). DPD (`InstallmentAging`)
      dan outstanding (`InstallmentBalance`) tetap milik `contract`, dibaca lewat `AgingReportSourcePort` (edge
      satu arah `reporting` → `contract`, TS §1); penalty termasuk, penalty adjustment belum (belum ada E5).
      Σ bucket = outstanding dijaga by construction. Satu baris matcher RBAC baru (ADMIN/FINANCE/MANAJEMEN).
      Unit `AgingBucketTest`/`AgingBreakdownTest`/`AgingReportServiceTest` + Testcontainers `AgingReportIT` dan
      `EndpointRoleMatrixIT` pass; full test 72 suites / 570 tests pass, `check` pass. Tanpa migrasi/dependensi
- [x] Hardening DoS input (ADR-016): guard `shared.money.DecimalBounds` menolak money di luar `NUMERIC(19,2)` dan rate di luar `NUMERIC(7,4)` memakai `precision()`/`scale()` saja (tanpa expand), dipasang di `ContractCommandService.create` sebelum `canonicalize`, di `PaymentApplicationService.requireAmount` sebelum `setScale`, dan sebagai backstop di `CanonicalRequestJson.money`/`.rate`. Aktivasi menolak tanggal efektif lebih tua dari `today − 1 tahun` (`requireActivatableStartDate`) agar backlog denda tidak teramplifikasi. Menutup 4 temuan CWE-400 scan 2026-10-01 (temuan authz #1 sudah ditutup T7). `DecimalBoundsTest` (8) + `CanonicalRequestJsonTest` (8) pass, `compileJava compileTestJava` pass; regresi 400 level service (payment/contract/activation) via MockMvc/IT dijalankan di CI (butuh Docker)
- [x] T9 — Contract statement (ADR-013 impl note T9): `GET /api/v1/contracts/{id}/statement` mengembalikan
      baris jurnal kontrak kronologis dalam envelope `{data, error}`. Endpoint di `contract` (existence check →
      404 `CONTRACT_NOT_FOUND`); read lewat `ledger.application.ContractStatementPort` read-only baru (edge
      `contract → ledger` yang sudah ada, bukan edge baru). Ledger-literal (A-8): satu baris per `journal_line`,
      `debit`/`credit` nilai apa adanya, tanpa running balance; reversal muncul sebagai barisnya sendiri
      (`is_reversal`). Filter opsional `ref_type` + rentang `from`/`to` (Asia/Jakarta, inklusif), urutan
      kronologis, paging `page`/`size`; kontrak DRAFT → kosong. Role ADMIN_OPERASIONAL/FINANCE (MANAJEMEN →
      403). Migrasi V11 menambah index `journal_line (contract_id, entry_date)`. Tanpa `ErrorCode`/dependensi
      baru; `account_name` dibaca dari `LedgerAccount.displayName()` dan dikunci ke seed oleh
      `LedgerAccountNameIT`. Verifikasi: `compileJava compileTestJava` pass dan semua unit `*Test` pass; `*IT`
      (ContractStatementIT, LedgerAccountNameIT, EndpointRoleMatrixIT) **belum** dijalankan karena Docker
      tidak tersedia di mesin — perlu `./gradlew test`
      dan `./gradlew check` dengan Docker aktif. **Sprint 4c selesai**
- [x] T24 — Lifecycle idempotency key (ADR-017, mengubah ADR-007 d9, option A): key **sekali pakai selamanya**
      per endpoint; retensi hanya membatasi replay. `IdempotencyService` membuang takeover; reuse key setelah
      window → 409 `IDEMPOTENCY_KEY_EXPIRED` (ErrorCode baru) sebelum supplier dijalankan, apa pun body-nya —
      tanpa billing/accrual/tulis bisnis. Setelah F4/T18 menghapus baris, backstop permanen
      (`uq_contract_idempotency`, `uq_payment_idempotency`) memberi kode yang sama (`translateCreateViolation` + `translatePaymentViolation` baru). `PaymentConflictClassifier` kini sadar-constraint: hanya
      `uk_penalty_accrual` dan optimistic-lock yang di-retry, `uq_payment_idempotency` tidak (CR-04).
      `PaymentRetryingService.BACKOFF_MILLIS` jadi `{50, 150}` (slot 400 ms yang tak terjangkau dihapus,
      CR-13). Tanpa migrasi. **Catatan ADR: nomornya ADR-017, bukan ADR-016 — ADR-016 sudah dipakai untuk
      decimal-magnitude bounds.** Verifikasi: `compileJava compileTestJava` pass; unit `*Test` 43 suites / 335
      tests pass (termasuk `PaymentConflictClassifierTest` baru). `*IT` (IdempotencyRetentionIT yang ditulis
      ulang, kasus retensi baru di ContractIdempotencyIT/PaymentIdempotencyIT) **belum** dijalankan karena
      Docker tidak tersedia — perlu `./gradlew test` dan `./gradlew check` dengan Docker aktif
- [x] T25 — Backstop akunting DB (V12): parent-side deferred check (jurnal < 2 baris berimbang, payment yang Σ alokasinya ≠ amount), index unik partial `uq_journal_entry_event` pada `(ref_type, ref_id)` untuk empat ref type yang diposting hari ini (SETTLEMENT dibiarkan bebas untuk E2), dan `system_parameter` append-only (`block_modification()` UPDATE/DELETE). Tanpa perubahan Java — jalur tulis yang ada sudah memenuhi semua check; cleanup tes yang menghapus baris config memakai `support/AppendOnlyTestCleanup` (bypass trigger di satu koneksi, bukan melemahkannya)
- [x] T26 — Robustness job servicing harian (CR-07/CR-08/CR-09): **CR-07** status terminal baru `JobRunStatus.ABANDONED` + migrasi V13 melebarkan `ck_job_run_status`; run harian ber-lock menandai baris `RUNNING` sisa-crash sebagai `ABANDONED` saat start, dan jika loop per-kontrak melempar, ketiga baris di-`failHard` jadi `FAILED` lalu dilempar ulang (tidak ditelan). **CR-08** `ActiveContractListingPort` kini keyset paging (`findActiveContractIdsAfter`) dengan sentinel UUID nol untuk halaman pertama (hindari untyped-null X-13), orchestrator iterasi per halaman `serfira.jobs.daily-servicing.batch-size` (default 500). **CR-09** `runWithRetry` menyuntik `Sleeper` dan jeda `BACKOFF_MILLIS {50,150,400,1000}` (persis `MAX_ATTEMPTS - 1` entri, pelajaran T24/CR-13). Semantik A-9 dan kontrak tiga baris `job_run` tak berubah; tanpa perubahan money/ledger/HTTP/security. Dokumen: ADR-013 A-9 note T26, TS §2.3/§1, GAPS §5/§10. Verifikasi (Docker aktif): `compileJava compileTestJava` pass; unit baru + semua `*Test` pass; `DailyJobRobustnessIT` baru + `DailyServicingJobIT`/`LockIT`/`AgingJobIT`/`TransactionIT` tetap hijau. Full `check` = 621 tests, 5 gagal — **semuanya `ContractStatementIT`**, bug T9/statement yang **pre-existing di `main`** (X-13 → T29), bukan dari T26 (task ini tidak menyentuh jalur statement)
- [x] T29 — Perbaiki query statement kontrak di PostgreSQL (X-13): ketiga guard `is null` di `JournalLineRepository.findContractStatement` dan `countQuery`-nya dibungkus `cast(... as timestamp/string)`, sehingga tiap bind nullable punya tipe terdeklarasi dan PostgreSQL tak lagi melempar `42P18 could not determine data type of parameter` (sebelumnya setiap panggilan `/statement` jadi 500). Hanya guard yang di-cast; perbandingan sebenarnya (jendela `[from, to)` inklusif/eksklusif, exact-match `ref_type` opsional), urutan, dan paging tak berubah. Tanda tangan metode, `StatementLineProjection`, dan `ContractStatementService` tidak disentuh. Verifikasi (Docker aktif): `.\gradlew test --tests "com.serfira.contract.ContractStatementIT"` 9 test hijau; `*LedgerAccountNameIT` 2 test hijau; full `.\gradlew test` 621 test, 0 gagal (5 kegagalan `ContractStatementIT` yang pre-existing kini bersih); `.\gradlew check` hijau
- [x] T11 — Phase-1 test hardening + exit verification (hanya tes; tanpa perubahan kode produksi/migrasi).
      Tes immutability tabel anak `journal_line`/`payment_allocation`/`settlement_allocation`/`penalty_adjustment`
      (menolak UPDATE+DELETE dengan SQLSTATE `P0001` dan baris tetap bertahan; `penalty_accrual` tak diduplikasi
      karena sudah di bagian V9). Dua assert lemah `isNotNull()` di `ContractIdempotencyIT`/`PaymentIdempotencyIT`
      jadi `ConflictException` (ADR-017 D1), dua assert `RuntimeException` telanjang jadi SQLSTATE `P0001`.
      `Phase1ExitScenariosIT` baru menjalankan PRD §7 skenario 1–4 & 8–9 lewat HTTP + job (tanpa seeding SQL
      perilaku yang diuji): waterfall denda→bunga→pokok tertua dahulu, overpayment jadi `TITIPAN_NASABAH`
      (tidak auto-apply), clamp due-date akhir bulan/kabisat, Σ alokasi = amount, jurnal berimbang; nilai uang
      diturunkan dari `PaymentApiIT`. `PiiLoggingIT` baru membuktikan NIK/telepon mentah dan telepon ter-normalisasi
      `628…` tak pernah muncul di log lintas create/payment/job. Ekstensi cleanup TRUNCATE bersama ditunda (NO-GO,
      behaviour-neutral). Verifikasi (Docker aktif, di-rerun paksa oleh orchestrator): `.\gradlew check` pass dan
      full `.\gradlew test --rerun-tasks` = **80 suites / 633 tests / 0 gagal**. Review semantik: APPROVED (dua
      observasi non-blocking). Branch `t11-phase1-exit-verification` @ `a8fa1a6`. **Sprint 4d selesai → Sprint 4
      (Penalty, Aging & Phase-1 Close) selesai.**

Review eksternal 2026-09-30 (16 temuan, CR-01…CR-16) sudah diverifikasi terhadap kode dan dipetakan ke task di [tasks.md](tasks.md) → Planning Notes.

Sprint 5 (settlement, credit application, void, consistency check, write-off, frontend) — **berjalan**:

- [x] T14 — E3 Excess → `contract_credit` + credit-apply endpoint (ADR-009, Addendum §2). Overpayment kini
      memesan satu baris `contract_credit` AVAILABLE (sub-ledger liability `TITIPAN_NASABAH`, bukan jurnal
      kedua) lewat `ContractCreditPort` baru milik `contract` yang dipanggil write path `payment`
      (`uk_contract_credit_source` = satu credit per alokasi EXCESS). `POST /api/v1/contracts/{id}/credit/apply`
      (ADMIN_OPERASIONAL) mengaplikasikan saldo tersedia ke installment due tertua dengan recognized receivable
      memakai engine murni baru `CreditApplicationEngine` (PENALTY → INTEREST → PRINCIPAL, due_date lalu
      period_no), partial diperbolehkan, satu credit boleh mendanai banyak application, tidak pernah
      auto-apply; hanya mengurangi recognized receivable, resolusi lewat
      `InstallmentReceivablePort.applyPaymentResolution`. Satu jurnal per panggilan (`Dr TITIPAN_NASABAH /
      Cr PIUTANG_*`, `ref_type=CREDIT_APPLICATION`, `ref_id` = application pertama; mengandalkan service guard
      `LedgerPostingService`, ADR-008 d5). `GET /api/v1/contracts/{id}/credit` (ADMIN_OPERASIONAL + FINANCE)
      mengembalikan saldo + histori. Status flip AVAILABLE → APPLIED hanya saat saldo 0. Migrasi V14: trigger
      deferred cap `Σ application.amount ≤ amount` (invariant 11) + guard status, tanpa `CREATE TABLE`, tanpa
      menyentuh `uq_journal_entry_event`. Retry optimistic-lock `ContractCreditRetryingService` (3 percobaan,
      50/150 ms) mencerminkan jalur payment. Verifikasi (Docker aktif): `CreditApplicationEngineTest` (16) +
      `ContractCreditIT` (10) + `EndpointRoleMatrixIT` + `OpenApiSmokeIT` pass; full `.\gradlew test` = **665
      tests, 0 gagal**; `.\gradlew check` hijau. Branch `t14-contract-credit`.

- [x] T15 — E5 Penalty waive/reduce + `EffectivePenaltyPort` (ADR-019, Addendum §16.4). Writer pertama
      `penalty_adjustment`: entity `PenaltyAdjustment` baru di `penalty.domain` (extends `Auditable`,
      append-only via trigger V1). `PenaltyAdjustmentService` (`@Transactional`) mencatat WAIVE/REDUCE dan
      memposting satu jurnal koreksi **`Dr BEBAN_WAIVER_DENDA / Cr PIUTANG_DENDA`**
      (`ref_type=PENALTY_WAIVER`, `ref_id=penalty_adjustment.id`); `approved_by` dari JWT `sub` (bukan body).
      `POST /api/v1/penalty-adjustments` (ADMIN_OPERASIONAL). Akun ekspense = `BEBAN_WAIVER_DENDA` baru
      (di-seed V15), bukan reuse `DISKON_PELUNASAN` (makna settlement-rebate berbeda, ADR-018 D3).
      `EffectivePenaltyPort`/`EffectivePenaltyService` baru = satu-satunya sumber effective penalty
      (`max(0, penalty_amount − Σ adjustment)`, invariant 9, clamp ≥ 0); pembacaan native
      `InstallmentRepository.sumPenaltyAdjustmentsByInstallmentIds` di `contract` **dihapus**, dua call site
      (`InstallmentReceivableService`, `ContractCreditCommandService`) lewat port (edge `contract → penalty`,
      menutup CR-14). Over-waive → 409 `CONFLICT`, tidak menulis apa pun. Migrasi V15 (tanpa `CREATE TABLE`):
      seed `BEBAN_WAIVER_DENDA` + trigger deferred `assert_penalty_adjustment_cap`
      (`Σ penalty_adjustment.amount ≤ installment.penalty_amount`); `uq_journal_entry_event` **tidak** diperluas
      (service guard `LedgerPostingService` cukup, ADR-008 d5, seperti T14). `ContractInstallmentTotals` masih
      mengabaikan adjustment (follow-up ADR-019). Perubahan test: `PaymentApiIT`
      `anUnusableReceivableSnapshotIsRejectedAndRollsTheWholePaymentBack` → `anOverWaiverExceedingAccruedPenaltyIsRejectedByTheDbCap`
      (premis korupsi lama kini tak terjangkau karena cap V15 menolak seed over-waive saat commit; invariant 9
      berubah, jadi ini perubahan nilai harapan sesuai spec). Verifikasi (Docker aktif): `EffectivePenaltyFormulaTest`
      (5) + `PenaltyAdjustmentIT` (7, termasuk port-parity) + `EndpointRoleMatrixIT` + `LedgerPostingIT` +
      `LedgerAccountNameIT` pass; full `.\gradlew test` = **680 tests / 84 suites / 0 gagal / 0 error**;
      `.\gradlew check` hijau. Branch `t15-penalty-waive-effective-port`.

- [x] T15 fix-pass 2026-10-06 (review PR #7, temuan F2/F3/F4/F5). **F2** (money-balances): formula effective
      penalty kini `max(0, gross − Σ adjustment − Σ paid penalty)`; term bayar dibaca lewat port baru
      `payment.application.PenaltyAllocationPort` (Σ alokasi `PENALTY` POSTED per installment), dan migrasi
      **V16** menggantikan `assert_penalty_adjustment_cap()` agar cap DB ikut mengurangi Σ `PENALTY`
      `payment_allocation` (identik dengan cap PENALTY V3; V15 dibiarkan apa adanya, forward-only). **F3/F4**:
      waiver kini me-recompute status resolusi installment + aturan tutup `MATURITY` lewat port baru
      `contract.application.InstallmentStatusRecomputePort` (penulisan baris installment ter-version), dan
      `PenaltyAdjustmentRetryingService` (3 percobaan, 50/150 ms) men-serialize waiver konkuren di luar
      transaksi → tepat satu commit, sisanya 409 `CONCURRENT_MODIFICATION`. **F5** (doc): Javadoc/komentar
      `flush()` di `PenaltyAdjustmentService` dikoreksi — trigger cap V16 & balance V3 `DEFERRABLE INITIALLY
      DEFERRED` jadi fire saat COMMIT, bukan saat flush; 409 over-waive normal berasal dari pre-check in-memory.
      **F6** invalid (tanpa aturan WAIVE==full). **F1** di-defer ke T30 (read-path schedule/aging). Dua edge
      lintas-modul baru dicatat TS §1 + ADR-019 (implementation note). Verifikasi: lihat ringkasan FEAT-003.

- [x] T30 — Route schedule response + aging report lewat effective penalty via `EffectivePenaltyPort`
      (menutup ADR-019 D4 / temuan F1 PR #7, dan temuan F1–F4 review PR #8). Dua read-path `contract` yang
      masih melaporkan denda **gross** kini melaporkan denda efektif (sisa) per-installment lewat port yang
      sama dengan snapshot payment-receivable (bukan formula kedua, tanpa baca native `penalty_adjustment` di
      `contract`): `InstallmentResponse.from(Installment, Σ adjustment, effectivePenalty)` melaporkan nilai
      `effective` dari port apa adanya pada field `penalty_amount` (bentuk wire tetap; maknanya sisa denda
      setelah adjustment **dan** denda terbayar) sementara `outstanding` hanya mengurangi Σ adjustment via
      `InstallmentBalance.of(installment, Σ adjustment)` (paidAmount sudah mengurangi denda terbayar);
      `ContractQueryService.installments(...)` menyuntik `EffectivePenaltyPort` dan mengalirkan `effective`
      per-installment dengan cabang status — DRAFT → kosong, ACTIVE → `loadEffectivePenalty`, closed →
      `loadEffectivePenaltyAnyStatus` — sehingga semua status tetap 200 dan kontrak closed yang pernah di-waive
      tidak lagi menampilkan denda gross; `AgingReportSourceService` menyuntik port dan memanggilnya **sekali
      secara batch** (`loadEffectivePenalty(Collection<UUID>)`) untuk semua kontrak ACTIVE, bukan sekali per
      kontrak. Read status-agnostic dan batch adalah method baru pada interface port yang sudah ada
      (`EffectivePenaltyPort` / `InstallmentPenaltyPort`), tanpa edge cross-module baru dan satu helper
      `EffectivePenaltyService` berbagi formula tunggal. Edge `contract → penalty` sudah ada (TS §1); tanpa
      migrasi dan tanpa perubahan aturan dependency. IT `contract.EffectivePenaltyReadConsistencyIT`: waiver
      yang membersihkan denda menghapus period 1 dari jadwal/aging dan sepakat dengan receivable; pembayaran
      denda **parsial tanpa waiver** membuat `penalty_amount` jadwal = `gross − denda terbayar` (bukan gross);
      kontrak closed (SETTLEMENT) dengan waiver sebelumnya melaporkan denda net; endpoint tetap 200 untuk DRAFT
      dan CLOSED. Verifikasi (Docker aktif): narrowest `EffectivePenaltyReadConsistencyIT` +
      `PenaltyAdjustmentIT` + `AgingReportIT` + `ContractApiIT` + `EffectivePenaltyServiceTest` pass; full
      `.\gradlew test` = **87 suites / 697 tests / 0 gagal / 0 error**; `.\gradlew check` hijau. Branch
      `t30-effective-penalty-reads`.
