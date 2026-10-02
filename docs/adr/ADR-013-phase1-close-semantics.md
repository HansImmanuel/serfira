# ADR-013: Semantik Penalty, Aging, Statement, dan Job untuk Phase-1 Close (T1)

- **Status:** Accepted
- **Date:** 2026-09-29
- **Deciders:** Hans (solo developer)
- **Amends:** ADR-012 (decisions 2, 7 dan risiko residual i–ii dipertegas, tidak diubah)

---

## Context

`docs/tasks.md` Sprint 4b/4c (T3–T10) tidak dapat dimulai sebelum sepuluh ambiguitas (A-1…A-10, katalog di
`tasks.md` §Planning Notes) diputuskan. Semuanya adalah pilihan di antara alternatif yang sudah
terdokumentasi, bukan aturan bisnis baru:

1. **A-1** — Apakah tanggal `D` ditagih dengan base pada **awal** `D`, sebelum resolusi yang jatuh pada `D`
   sendiri?
2. **A-2** — Apakah denda yang sudah dibayar ikut mengurangi base pokok+bunga?
3. **A-3** — Setelah void, apakah tanggal yang sudah ter-accrual dengan base tereduksi di-re-price, dan
   bagaimana caranya mengingat `uk_penalty_accrual` satu baris per `(installment, date)`?
4. **A-4/A-5** — Kapan DPD mulai dan apa yang membuat installment `OVERDUE`; apa yang terjadi pada
   `OVERDUE` + pembayaran sebagian?
5. **A-6** — Apakah `as_of` pada aging bersifat hari-ini saja atau rekonstruksi historis?
6. **A-7** — Basis bucket (per-installment atau per-kontrak) dan cakupan kontrak?
7. **A-8** — Arti kolom debit/credit pada statement, akun mana yang tampil, apakah ada running balance?
8. **A-9** — Granularitas `job_run`, satuan `records_processed`/`records_failed`, status setelah kegagalan
   parsial?
9. **A-10** — Konfirmasi tidak ada accrual denda setelah maturity close.

Sumber ketegangan dokumen per ambiguitas dan dampaknya ke task lain ada di `tasks.md` §Planning Notes
§Ambiguities; tidak diulang di sini.

---

## Decision

### 1. A-1 — Invariant accrue-before-resolve (diterima)

Setiap alur yang **menurunkan** base denda sebuah installment (pembayaran, settlement, write-off) harus
membilling dan meng-accrue denda melalui tanggal bisnisnya **di transaksi yang sama**, sebelum resolusi itu
diterapkan. Dengan invariant ini, base yang dipakai `PenaltyCalculator` pada saat run selalu sama dengan base
yang berlaku secara historis pada setiap tanggal yang belum ter-accrual — karena tidak ada resolusi yang
menyisip di antaranya. Ini persis usulan "Accrue-before-resolve invariant" di `tasks.md` §Planning Notes, dan
di sini dinaikkan dari usulan menjadi keputusan yang mengikat T3/T4/T12/T13/T16/T19.

Konsekuensi langsung: jalur pembayaran (T4) **wajib** memanggil `accrueDuePenalty` sebelum
`loadReceivableSnapshot`, persis seperti billing sudah memanggil `billDueInterest` lebih dulu (ADR-011
keputusan 2). Void (T16) adalah **satu-satunya** kasus sisa di mana base naik retroaktif — lihat A-3.

### 2. A-2 — Base tetap ADR-012 keputusan 2, tidak diubah

`penaltyBase = max(0, principal + recognizedInterest − paid − settled − writtenOff)` **tetap seperti
sekarang** (`InstallmentBalance.penaltyBase`). Denda yang sudah dibayar tidak dibaca sebagai komponen
terpisah di formula ini — efeknya sudah masuk lewat waterfall PENALTY→INTEREST→PRINCIPAL: pembayaran yang
melunasi denda dahulu baru menyisakan kapasitas untuk INTEREST/PRINCIPAL pada pembayaran **yang sama**, dan
baris itu (bukan denda yang dibayar) yang menurunkan base.

Alasan menolak precision fix (T10): selisihnya dibatasi ADR-012 selalu konservatif (menagih lebih sedikit,
tidak pernah lebih), dan mengejar presisi berarti melacak split komponen resolusi lintas modul (`payment` →
`contract`) — pelanggaran arah dependensi yang sudah ditolak ADR-012 alternatif 5. **T10 menjadi DEFERRED**,
bukan BLOCKED: tidak ada perubahan skema yang diperlukan sampai ada keputusan bisnis baru yang secara
eksplisit menuntut presisi komponen-eksak.

### 3. A-3 — Void: tidak ada re-pricing tanggal yang sudah ter-accrual

ADR-012 keputusan 7 dipertegas, tidak diubah: baris `penalty_accrual` yang sudah ada **tidak pernah**
di-update atau digantikan. Setelah void, catch-up hanya menagih tanggal yang **belum** punya baris
(`unique(installment_id, accrual_date)` tetap satu baris per tanggal selamanya). Tanggal yang sudah
ter-accrual dengan base yang lebih rendah (karena pembayaran yang kemudian di-void) tidak pernah ditagih
ulang dengan base yang lebih tinggi.

Ini secara sadar menerima **undercharge permanen** untuk tanggal-tanggal itu sebagai trade-off yang sama
dengan yang sudah diterima ADR-012 (arahnya konsisten: sistem tidak pernah menagih lebih dari yang seharusnya
tanpa jejak, tapi bisa menagih kurang). Alternatif "tambah representasi kedua per tanggal" (kolom histori base
atau versi baris) ditolak: menambah migrasi dan kompleksitas untuk kasus yang menurut catatan review hanya
muncul saat void mengikuti pembayaran yang menyentuh denda — kombinasi yang jarang, dan T16 (E4) belum
direncanakan sebelum Sprint 6. **Tidak ada perubahan skema.** T16 tetap TODO dengan catatan ini sebagai
input desainnya, bukan lagi "tergantung keputusan".

### 4. A-4/A-5 — Definisi aging

- **A-4 (kapan `OVERDUE`):** Sebuah installment `PENDING` atau `PARTIALLY_PAID` menjadi `OVERDUE` pada hari
  pertama yang **chargeable** menurut denda: `today >= due_date + grace_period_days + 1`
  (`PenaltyTerms.firstChargeableDate()`, kode yang sudah ada). Ini menyamakan definisi DPD-mulai dengan
  definisi hari pertama denda berjalan (TS §4.3), sehingga aging dan penalty selalu konsisten dan job aging
  (T6) bisa dipanggil tepat setelah step penalty tanpa formula kedua. `DPD` untuk installment pada tanggal
  bisnis `D` = `max(0, D − due_date − grace_period_days)`, nol sebelum hari itu (bucket "Current").
- **A-5 (`OVERDUE` + pembayaran sebagian):** Kode yang sudah ada (`Installment.applyPayment`) menang atas
  diagram DM §1.4: pembayaran sebagian pada installment mana pun — termasuk yang `OVERDUE` — menghasilkan
  `PARTIALLY_PAID`, dan job aging (T6) menandainya `OVERDUE` kembali pada run berikutnya bila `outstanding >
0` dan `DPD >= 1`. Diagram DM §1.4 diperbarui untuk menambahkan edge `OVERDUE → PARTIALLY_PAID` (X-4 di
  tabel discrepancy `tasks.md`), bukan sebaliknya: menulis ulang `applyPayment` untuk mempertahankan
  `OVERDUE` selama pembayaran sebagian akan menyembunyikan progres pembayaran dari operator, dan tidak ada
  laporan yang membaca status mentah untuk DPD (Implementation Note T6 sudah mensyaratkan laporan aging
  membaca `DPD` terhitung, bukan status).

### 5. A-6 — `as_of` hanya hari ini

Aging (T8) dan bucket-nya dihitung hanya untuk `as_of = clock.today()`, tidak ada rekonstruksi historis.
Alasan: state model saat ini (status + saldo terkini) tidak menyimpan snapshot historis harian, dan Addendum
§10A/TS §2.0 sudah menetapkan tidak ada pembayaran backdated — konsisten dengan tidak ada laporan
"as-of-tanggal-lampau" juga. `GET /reports/aging` **menolak** `as_of` selain hari ini dengan 400
`INVALID_AS_OF_DATE` (bukan diam-diam mengabaikan parameter). Rekonstruksi historis penuh (mis. tabel
snapshot harian) didorong ke Deferred; tidak ada task Phase-1/5/6 yang membutuhkannya.

### 6. A-7 — Basis bucket dan cakupan

Bucket dihitung **per installment**: setiap installment ACTIVE-contract dengan `outstanding > 0`
dikelompokkan ke bucket berdasarkan DPD installment itu sendiri (Current, 1–30, 31–60, 61–90, >90), dan
`amount` bucket = `outstanding` installment itu (PRD glossary; TS §4.3 formula outstanding, Addendum §14).
Installment yang belum jatuh tempo (`due_date > today`) selalu Current — belum tentu berarti `DPD = 0`
secara harfiah, tapi outstanding-nya bukan tunggakan. Level kontrak dan portofolio adalah **penjumlahan**
bucket seluruh installment miliknya/miliknya seluruh kontrak — bukan "bucket kontrak berdasarkan DPD
maksimum installment-nya". Ini memenuhi invariant Σ bucket = outstanding di setiap level tanpa kebutuhan
formula kedua. **Cakupan kontrak: ACTIVE saja.** `DRAFT` belum punya receivable, `CLOSED`/`TERMINATED` sudah
selesai dan `SETTLED`/`WRITTEN_OFF` installment individual sudah keluar dari outstanding lewat formula
`InstallmentBalance` — tidak perlu exclude eksplisit karena outstanding-nya sudah nol.

### 7. A-8 — Statement: literal ledger, tanpa running balance

Statement (T9) menampilkan setiap `journal_line` yang menyentuh `contract_id` tersebut, kolom `debit`/
`credit` **persis nilai `journal_line`** (bukan diarahkan ulang ke sudut pandang kontrak/nasabah). Kolom
tambahan: `entry_date`, `account_code` (dan nama akun dari `accounts`), `ref_type`/`ref_id`/`description`
dari `journal_entry` induk, serta flag `is_reversal` (`reversal_of_id IS NOT NULL`). **Tidak ada running
balance** di Phase 1 — dasar keputusan: akun yang tampil berbeda-beda (`PIUTANG_POKOK`, `PIUTANG_BUNGA`,
`PIUTANG_DENDA`, `KAS`, `PENDAPATAN_*`, `TITIPAN_NASABAH`), dan sebuah balance tunggal berjalan lintas akun
berbeda arti tidak mewakili apa pun yang berguna secara bisnis; agregat outstanding yang berarti sudah
tersedia lewat T8. `06_FRONTEND_SPEC.md §2.7` diperbarui: kolom "Debit / Kredit" berarti nilai jurnal
literal, bukan sum per entry (X-8 di `tasks.md` diselesaikan sebagai "ledger-literal", bukan "customer
balance view" — T9 §Risks disesuaikan: tidak lagi berisiko meluas ke running balance).

### 8. A-9 — `job_run`: satu baris per **step per invocation**, dengan business date eksplisit

Klarifikasi implementasi T3: `job_run` mencatat **satu baris per langkah untuk setiap invocation**, dan
setiap baris membawa `business_date` yang menjadi target invocation itu (V10). Contoh satu invocation untuk
`D`: satu baris `job_name = "billing"` dan satu baris `job_name = "penalty-accrual"`, bukan satu baris untuk
seluruh batch dan bukan satu baris per kontrak. Rerun idempotent untuk `D` yang sama menulis pasangan row
invocation baru — financial rows tetap tidak bertambah — sehingga histori percobaan dan stale `RUNNING`
tidak ditimpa. Ini konsisten dengan urutan Addendum §7.3 (billing → penalty → aging → consistency check),
di mana setiap langkah punya hasil sendiri yang layak diaudit terpisah.

- `records_processed` = jumlah **kontrak** yang berhasil diproses langkah ini (bukan jumlah baris
  accrual/billing yang ditulis — jumlah baris bisa nol untuk kontrak yang tetap "berhasil" karena tidak ada
  yang jatuh tempo).
- `records_failed` = jumlah kontrak yang gagal setelah retry habis (T3/T5).
- **Status akhir setelah kegagalan parsial** = `COMPLETED` bila `records_failed = 0`, selain itu `FAILED` —
  tidak ada status ketiga "partial success". `records_processed` dan `records_failed` bersama-sama sudah
  membedakan "gagal total" dari "gagal sebagian" tanpa status tambahan, dan ini menjaga aturan T3 "kontrak
  lain tetap commit" tetap terlihat lewat kedua counter itu, bukan lewat status.
- `finished_at` selalu diisi begitu langkah selesai, baik `COMPLETED` maupun `FAILED`; job yang crash sebelum
  menulis `finished_at` meninggalkan baris `RUNNING` yang stale. Run berikutnya menulis invocation baru;
  tidak ada resume atau UPDATE yang menyamarkan invocation lama.

### 9. A-10 — Tidak ada accrual setelah maturity close (dikonfirmasi)

Dikonfirmasi tanpa perubahan: `PenaltyAccrualPort`/`InstallmentPenaltyService` mensyaratkan kontrak `ACTIVE`
(perilaku port receivable yang sudah ada, ADR-010/ADR-011 keputusan 7 — kontrak non-ACTIVE ditolak, bukan
dilewati diam-diam). Job D2 (T3) hanya mengiterasi kontrak `ACTIVE`, sehingga kontrak yang sudah
`CLOSED`/`TERMINATED`/`SETTLED` tidak pernah masuk daftar kerja job. Tidak ada perubahan invariant DM §3
invariant 17 yang diperlukan.

---

## Alternatives Considered

1. **A-2: Melacak split komponen resolusi (pokok/bunga/denda) lintas modul untuk base eksak.** Ditolak —
   sama seperti ADR-012 alternatif 5/6, arah dependensi salah dan migrasi tambahan tidak dibutuhkan untuk
   perbedaan yang selalu konservatif.
2. **A-3: Menambah kolom/baris kedua per tanggal untuk merepresentasikan base historis dan re-price
   retroaktif.** Ditolak untuk sekarang — menambah migrasi tanpa kebutuhan bisnis yang mendesak di
   Sprint 4b/4c; didokumentasikan sebagai trade-off sadar yang bisa direvisit saat E4 (T16) benar-benar
   didesain.
3. **A-4/A-5: Menulis ulang `Installment.applyPayment` agar `OVERDUE` + partial tetap `OVERDUE`.** Ditolak —
   mengubah kode yang sudah diuji (golden path) tanpa keperluan bisnis yang terdokumentasi; diagram adalah
   yang salah, bukan implementasinya, karena tidak ada laporan/proses yang menuntut `OVERDUE` dipertahankan
   melewati pembayaran sebagian.
4. **A-6: Rekonstruksi historis `as_of` lewat penyimpanan snapshot harian.** Ditolak untuk Phase 1 — beban
   skema dan storage yang besar untuk kebutuhan yang tidak diminta PRD Phase 1 (FE §2.9 hanya menyebut
   filter `as_of`, tidak menuntut historis).
5. **A-7: Bucket kontrak berdasarkan DPD maksimum installment-nya (bukan penjumlahan per-installment).**
   Ditolak — merusak invariant Σ bucket = outstanding di level kontrak (installment dengan DPD berbeda dalam
   satu kontrak akan salah bucket), dan PRD glossary tidak secara eksplisit meminta agregasi per-kontrak
   dahulu.
6. **A-8: Statement sebagai customer-balance view dengan running balance dan pemilihan akun tersembunyi.**
   Ditolak untuk Phase 1 — menuntut definisi "sudut pandang nasabah" per akun (apakah `PIUTANG_*` debit
   berarti "nasabah berutang lebih" atau sebaliknya) yang tidak terdokumentasi di manapun dan berisiko salah
   tafsir akuntansi; ledger-literal tidak ambigu karena `journal_line` sudah balanced by construction.
7. **A-9: Satu baris `job_run` untuk seluruh batch harian (semua step).** Ditolak — membuat kegagalan satu
   step (mis. billing sukses, penalty gagal) tidak terlihat granular, dan bertentangan dengan urutan
   Addendum §7.3 yang memperlakukan setiap step sebagai unit terpisah.
8. **A-9: `records_processed`/`records_failed` dalam satuan baris accrual/billing, bukan kontrak.** Ditolak
   — kontrak tanpa hari jatuh tempo pada `D` akan terlihat seperti "0 diproses" walau job berhasil
   mengonfirmasi tidak ada yang harus ditagih; satuan kontrak selalu > 0 untuk run yang benar-benar
   mengiterasi sesuatu, lebih mudah dijadikan sinyal "job hidup" (Addendum §10 tujuan job metrics).

---

## Consequences

- **T10 berubah dari BLOCKED menjadi DEFERRED.** Tidak ada task kode yang dibutuhkan sampai ada permintaan
  bisnis baru untuk presisi komponen-eksak. `tasks.md` diperbarui.
- **T3/T4/T6/T8/T9 tidak lagi terhalang.** Implementasi masing-masing dapat memakai keputusan di atas tanpa
  menunggu keputusan lanjutan.
- **T16 (void) mewarisi keputusan A-3 tanpa syarat skema baru** — desainnya harus mengasumsikan
  undercharge-setelah-void-yang-menyusul-denda-terbayar sebagai kondisi yang diterima, bukan bug.
- **Dokumen turunan yang harus diperbarui bersama ADR ini** (per `80-documentation`, ADR saja tidak cukup):
  - `docs/02_TECH_SPEC.md` §4.3 — tambahkan catatan accrue-before-resolve dan rujukan ADR-013.
  - `docs/03_DOMAIN_MODEL.md` §1.4 — tambahkan edge `OVERDUE → PARTIALLY_PAID` pada state transition, dan
    definisi DPD/`OVERDUE` eksplisit.
  - `docs/04_GAPS_ADDENDUM.md` §6 — tegaskan A-3 (tidak ada re-pricing retroaktif) sudah final, bukan lagi
    open question.
  - `docs/04_GAPS_ADDENDUM.md` §10 — tambahkan definisi granularitas `job_run` (A-9).
  - `docs/04_GAPS_ADDENDUM.md` §14 — tambahkan definisi bucket/`as_of`/cakupan (A-6, A-7).
  - `docs/06_FRONTEND_SPEC.md` §2.7 dan §2.9 — perjelas arti kolom statement dan filter `as_of`.
  - `docs/05_SPRINT_PLAN.md` §5 Sprint 4 — tambahkan pointer ke `tasks.md` untuk pekerjaan yang dipindah ke
    Sprint 4b/4c (T1.e).
- **Tidak ada migrasi baru dari ADR ini sendiri.** T2 (V9) tetap scope migrasinya sendiri (lock table +
  immutability trigger `penalty_accrual` + `ck_penalty_accrual_days >= 1`), tidak berubah oleh keputusan di
  atas.
- **Risiko residual ADR-012 kini dibedakan statusnya:** risiko (i) untuk pembayaran ditutup oleh implementasi
  T4/ADR-014; risiko (ii) tetap terbuka sampai settlement T12/T13 menjalankan accrue-before-resolve pada
  tanggal quote/execution.

### Implementation Note — T4 (2026-09-30)

T4 telah mengimplementasikan kewajiban keputusan 1 untuk jalur pembayaran. `PaymentApplicationService`
sekarang memanggil `accrueDuePenalty` setelah billing dan sebelum `loadReceivableSnapshot`, semuanya di dalam
supplier idempotensi dan transaksi yang sama dengan allocation, resolution, dan jurnal pembayaran. Satu
business date dipakai oleh billing, accrual, dan allocation; replay tidak menjalankan supplier, sedangkan
kegagalan me-rollback claim serta seluruh write finansial.

Catatan ini tidak menghapus konteks keputusan D1 di ADR-012: lazy trigger memang belum ada saat D1 ditutup dan
baru diterima/diimplementasikan melalui ADR-014. Risiko race job-versus-payment pada unique
`(installment_id, accrual_date)` tetap diterima sementara dan menjadi T5; tidak ada klaim bahwa retry
fresh-transaction sudah tersedia.

### Implementation Note — T6 (2026-09-30)

T6 mengimplementasikan keputusan 4 (A-4/A-5) sebagai langkah ketiga job harian, dan memperluas keputusan 8
(A-9) dengan satu step baru:

- **Unit transaksi.** Aging berjalan per contract di loop yang sama, **setelah** transaksi billing → penalty,
  dalam transaksi **tersendiri** (`DailyServicingContractProcessor.age`). Alasannya: aging tidak menulis uang
  dan kondisinya (`D >= due_date + grace + 1`, `outstanding > 0`) tidak bergantung pada denda, sehingga
  kegagalan billing → penalty tidak boleh menghentikan aging, dan kegagalan aging tidak boleh me-rollback
  uang yang sudah diakui. Aging tetap dijalankan walau billing → penalty contract itu `FAILED`; contract yang
  teramati non-ACTIVE (`SKIPPED`) tidak di-aging karena status ACTIVE tidak pernah dimasuki kembali.
- **Retry dan `job_run`.** Aging memakai batas 5 attempts dan classifier konflik yang sama
  (optimistic-lock, SQLSTATE `23505`) secara terpisah dari billing → penalty. Setiap invocation kini menulis
  tiga row: `billing`, `penalty-accrual`, dan `aging`. Dua row pertama tetap berbagi counter (satu transaksi);
  row `aging` punya `records_processed`/`records_failed` sendiri dengan satuan kontrak dan aturan status
  `COMPLETED`/`FAILED` yang sama.
- **Kepemilikan dan idempotensi.** Transisi ada di `contract` (`Installment.markOverdue`, dipanggil lewat
  `InstallmentAgingPort`, `Propagation.MANDATORY` agar pemanggilan tanpa transaksi gagal alih-alih kehilangan
  perubahan). Aging hanya bergerak masuk ke `OVERDUE`: installment yang sudah `OVERDUE` tidak ditulis ulang
  (tanpa version bump), backfill untuk tanggal lebih awal tidak "meng-un-age", dan `PAID`/`SETTLED`/
  `WRITTEN_OFF` tidak pernah diubah.
- **Formula ganda yang dijaga test.** `contract.domain.InstallmentAging` memegang formula hari-mulai dan DPD
  karena `contract` tidak boleh bergantung pada `penalty`; `PenaltyTerms` tidak diubah. `AgingPenaltyParityTest`
  mengunci keduanya agar identik — ini tetap satu definisi bisnis sebagaimana keputusan 4, dengan dua
  representasi kode.

Tidak ada migrasi, perubahan API, atau dependency baru.

### Implementation Note — T8 (2026-10-02)

T8 mengimplementasikan keputusan 5 dan 6 (A-6/A-7) sebagai laporan aging read-only, tanpa membuat keputusan
baru:

- **Modul `reporting` baru.** `GET /api/v1/reports/aging` mengembalikan bucket Current/1–30/31–60/61–90/>90
  sebagai total portofolio dan per kontrak dalam envelope `{data, error}` standar. Bucketing dan agregasi ada
  di `reporting.domain` (`AgingBucket`, `AgingBreakdown`) dan `reporting.application` (`AgingReportService`).
- **Satu formula, lewat port.** Definisi DPD (`InstallmentAging.daysPastDue`) dan outstanding
  (`InstallmentBalance.of`) tetap milik `contract`; `reporting` membacanya lewat
  `contract.application.AgingReportSourcePort` (edge satu arah `reporting` → `contract`, dicatat di TS §1),
  yang mengeluarkan satu snapshot per installment ACTIVE dengan `outstanding > 0`. Tidak ada formula kedua
  (A-7). Penalty **termasuk** dalam outstanding; penalty adjustment belum dikurangi karena belum ada waiver
  flow (E5) — didokumentasikan di deskripsi OpenAPI endpoint.
- **Cakupan dan `as_of`.** Hanya kontrak ACTIVE (A-7); installment `SETTLED`/`WRITTEN_OFF`/`PAID` keluar
  sendiri karena outstanding-nya nol. `as_of` default hari ini dan **harus** hari ini; nilai lain ditolak
  `400 INVALID_AS_OF_DATE` (`ErrorCode` baru), bukan diam-diam diabaikan (A-6). Σ bucket = outstanding
  dijaga by construction di level kontrak dan portofolio (`AgingBreakdown`).
- **Otorisasi.** Satu baris matcher baru di `ResourceServerSecurityConfiguration`
  (`GET /api/v1/reports/aging` → ADMIN_OPERASIONAL/FINANCE/MANAJEMEN, Addendum §3.4), lahir dengan test
  matriks dan 401/403-nya (`EndpointRoleMatrixIT`, `AgingReportIT`).

Tidak ada migrasi atau dependency baru. Verifikasi: unit (`AgingBucketTest`, `AgingBreakdownTest`,
`AgingReportServiceTest`) dan Testcontainers `AgingReportIT` lulus; full `./gradlew test` = 72 suites /
570 tests / 0 gagal; `./gradlew check` lulus.

### Implementation Note — T9 (2026-10-02)

T9 mengimplementasikan keputusan 7 (A-8) sebagai statement kontrak (rekening koran) read-only, tanpa
membuat keputusan baru:

- **Endpoint di `contract`, read di `ledger`.** `GET /api/v1/contracts/{id}/statement` ada di
  `ContractController`. `contract` memvalidasi kontrak ada (404 `CONTRACT_NOT_FOUND` lewat
  `ContractNotFoundException`) lalu membaca baris jurnal lewat `ledger.application.ContractStatementPort` →
  `ContractStatementService` (`@Transactional(readOnly = true)`). Ini memakai edge yang **sudah** ada
  `contract → ledger` (aktivasi sudah posting lewat `LedgerPostingService`), bukan edge baru — `ledger`
  tetap tidak bergantung pada modul lain, jadi port tidak melakukan existence check dan id tak dikenal
  cukup menghasilkan page kosong (TS §1).
- **Ledger-literal, satu baris per `journal_line`.** Setiap baris adalah satu `journal_line` yang menyentuh
  `contract_id` tersebut; `debit`/`credit` adalah nilai baris apa adanya (bukan agregat, bukan sudut pandang
  nasabah). Kolom: `entry_date`, `account_code` (+ `account_name` dari `LedgerAccount.displayName()`),
  `description`/`ref_type`/`ref_id`/`reversal_of_id` dari `journal_entry` induk, dan flag `is_reversal`
  (`reversal_of_id IS NOT NULL`). **Tidak ada running balance** (keputusan 7). Entry reversal muncul sebagai
  barisnya sendiri — histori tidak pernah disembunyikan (ledger append-only, ADR-002).
- **Filter dan urutan.** Opsional `ref_type` (satu `LedgerRefType`) dan rentang tanggal bisnis `from`/`to`
  (Asia/Jakarta, keduanya inklusif) yang di-resolve ke jendela instant setengah-terbuka
  `[from 00:00, to+1 00:00)` karena `entry_date` adalah `timestamptz`. Urutan kronologis
  `entry_date asc, line.id asc` (tiebreak stabil untuk beberapa baris satu entry pada instant sama).
  Pagination `page`/`size` (size ≤ 100, TS §2.2) lewat `PageResponse`. Kontrak DRAFT belum posting apa pun →
  statement kosong yang valid.
- **Otorisasi.** Satu baris matcher baru di `ResourceServerSecurityConfiguration`
  (`GET /api/v1/contracts/*/statement` → ADMIN_OPERASIONAL/FINANCE; MANAJEMEN → 403, Addendum §3.4), lahir
  dengan test matriks dan 401/403-nya (`EndpointRoleMatrixIT`, `ContractStatementIT`).

- **Nama akun dari enum, dikunci ke seed.** `account_name` dibaca dari `LedgerAccount.displayName()` (label
  konstan pada enum) alih-alih join ke tabel `accounts`, agar `ledger` tetap mandiri dan read-nya satu query.
  `LedgerAccountNameIT` (Testcontainers) mengunci setiap `displayName()` ke `accounts.name` yang di-seed V1,
  sehingga rename akun lewat migrasi gagal di test alih-alih membuat statement menyimpang diam-diam dari
  chart of accounts.

Migrasi `V11__journal_line_contract_statement_index.sql` menambahkan index `(contract_id, entry_date)` pada
`journal_line` untuk melayani filter dan urutan (V1 tidak punya index pada `contract_id`). Tidak ada
`ErrorCode` baru (date-range `from > to`/paging → 400 `VALIDATION_ERROR`, `ref_type`/tanggal tak terparse →
400 lewat `GlobalExceptionHandler`). Tidak ada dependency baru dan tidak ada perubahan keputusan.

**Referensi:** `tasks.md` T1/T3/T4/T6/T8/T9/T10/T16 dan §Planning Notes; ADR-002/ADR-010/ADR-011/ADR-012; DM
§1.4/§1.9, §3 invariant 17; TS §2.0/§4.3; Addendum §6/§7.3/§10/§10A/§14; `06_FRONTEND_SPEC.md` §2.7/§2.9.
