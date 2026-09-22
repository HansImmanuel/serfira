# ADR-011: Billing/Recognition Bunga dan Maturity Close (C4)

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** Hans (solo developer)

---

## Context

Story C4 (`05_SPRINT_PLAN.md` §5 Sprint 4) adalah langkah pertama yang membuat **bunga menjadi receivable**:
per Addendum §12, pada setiap due date yang sudah tercapai interest diakui ke `PIUTANG_BUNGA / PENDAPATAN_BUNGA`.
Sejak C3, jalur pembayaran hanya mengalokasikan receivable yang **sudah** diakui (ADR-009 keputusan 3), jadi sampai
C4 kontrak baru punya kapasitas pokok saja dan PRD skenario 1 diuji dengan SQL seed. Yang belum diputuskan:

1. **Kapan billing berjalan.** Addendum §12 menyebut "MVP boleh menjadi step dalam daily scheduler", tetapi PRD
   skenario 1 menuntut pembayaran **pada** due date menemukan bunga yang sudah receivable.
2. **Perlakuan installment yang sudah `PAID`** sebelum bunganya di-bill — keadaan yang bisa lahir dari data pra-C4
   dan dari test seed.
3. **Bentuk dan granularitas jurnal** `BILLING`.
4. **Pemilik maturity close** (exit criterion Sprint 4: "final regular payment dapat auto-close contract") dan
   dasarnya: status atau outstanding.
5. **Boundary transaksi** untuk pemanggil kedua (job D2), karena `LedgerPostingService.post` berjalan
   `Propagation.MANDATORY`.
6. **Guard idempotensi**: `recognized == 0`, atau selisih `interest − recognized`.
7. **Konflik dokumen.** `05_SPRINT_PLAN.md` baris justifikasi C4 (3 poin) menyebut billing "menyentuh scheduler",
   sedangkan scheduler ShedLock + `job_run` adalah scope D2.

---

## Decision

1. **Seam baru `contract.application.InstallmentBillingPort`** (`billDueInterest(contractId, businessDate)`),
   diimplementasikan `InstallmentBillingService` di modul yang sama, **terpisah** dari
   `InstallmentReceivablePort`. Alasan: port receivable menyelesaikan **uang**, port ini mencatat **fakta
   akuntansi atas jadwal**; dan satu port harus punya satu implementasi, karena dua bean pada satu interface
   membuat context gagal start (`NoUniqueBeanDefinitionException`) — `payment` menyuntik keduanya.
2. **Lazy billing di jalur pembayaran, plus step mandiri.** `PaymentApplicationService.receive` memanggil billing
   **sebelum** `loadReceivableSnapshot`, di dalam transaksi pembayaran yang sama. Entry point billing
   `@Transactional` (`REQUIRED`): ia ikut transaksi pemanggil bila ada, dan membuka sendiri bila dipanggil job
   (D2). Alternatif "hanya job harian" ditolak: ada race antara tengah malam dan jam job, dan pembayaran tepat
   waktu menjadi EXCESS penuh — nondeterministik dan customer-hostile.
3. **Billing mengakui delta, idempotent secara state.** Untuk setiap installment kontrak ACTIVE: lewati bila
   `due_date > businessDate`; lewati bila status `SETTLED`/`WRITTEN_OFF`; lewati bila
   `interest_amount − recognized_interest_amount <= 0`; selain itu akui selisihnya lewat
   `Installment.recognizeInterest(delta)`. Konsekuensinya: re-run gratis (guard state, bukan hanya guard
   `(ref_type, ref_id)` C1), periode berbunga nol tidak menghasilkan jurnal (V1 `ck_journal_line_not_zero`), dan
   penulis `recognized_interest_amount` lain (mis. settlement) tidak pernah ter-bill dua kali.
4. **Satu jurnal `BILLING` per installment** — `ref_id = installment.id`, `entry_date = due_date` pukul 00:00
   Asia/Jakarta (konvensi yang sama dengan `start_date` aktivasi, ADR-008), debit `PIUTANG_BUNGA` = kredit
   `PENDAPATAN_BUNGA` = delta, `contract_id` di kedua baris (TS §3, DM §1.13). Alternatif satu entry agregat per
   kontrak-hari ditolak: guard `(ref_type, ref_id)` C1 akan memblokir run pada tanggal lain dan membuat re-run
   parsial per installment mustahil.
5. **Billing setelah `PAID` tidak mengubah status.** `recognizeInterest` hanya menaikkan
   `recognized_interest_amount`; `PAID` adalah marker kas, bukan marker settlement, dan DM §1.4 tidak mengenal
   transisi `PAID →`. Kelebihan bayar nasabah tetap liability `TITIPAN_NASABAH` yang dipakai E3. Catatan penting:
   sejak C4, keadaan "`PAID` dengan bunga belum di-bill" **tidak dapat lahir dari jalur tulis C4** (billing selalu
   berjalan lebih dulu), sehingga aturan ini bersifat defensif untuk data pra-C4 — dan diuji lewat SQL seed.
6. **Maturity close dimiliki modul `contract`**, dijalankan `InstallmentReceivableService.applyPaymentResolution`
   setelah seluruh resolusi diterapkan: bila **semua** installment `PAID` dan jadwal tidak kosong →
   `Contract.close(MATURITY, paidAt)`. Satu row update mengisi `status` + `closed_at` + `closed_reason` sekaligus
   (V4 `ck_contract_active_coherence` / `ck_contract_closed_coherence`), dan `close` idempotent: kontrak yang
   sudah CLOSED tidak menulis apa pun dan mengembalikan `false`. Dasar keputusan adalah **status**, bukan
   outstanding: Addendum §13 eksplisit membolehkan close dengan AVAILABLE credit tersisa. `SETTLED`/`WRITTEN_OFF`
   **tidak** memicu `MATURITY` — E2 (settlement) dan F5 (write-off) memutuskan `closed_reason`-nya sendiri, dan
   auto-close ini tidak boleh menulis ulang keputusan mereka. `closed_at` memakai instant event resolusi
   (`paidAt`), bukan pemanggilan clock terpisah, supaya close dan resolusi berbagi satu business instant.
7. **Kontrak non-ACTIVE ditolak (404/409), bukan dilewati**, memakai vocabulary yang sama dengan port receivable.
   Job D2 memilih kontrak ACTIVE; melewati diam-diam akan menyembunyikan bug pemanggil.
8. **Tanpa migrasi.** Kolom `recognized_interest_amount`, COA (`PIUTANG_BUNGA`/`PENDAPATAN_BUNGA`),
   `ref_type BILLING` (VARCHAR(40) tanpa CHECK), dan `job_run` sudah ada di V1. Catatan untuk D2: tabel `shedlock`
   **belum** ada di V1, jadi D2 memerlukan migrasinya sendiri.
9. **Konflik sprint plan diselesaikan dengan amandemen dokumen**, bukan deviasi diam: §Sprint 4 diperbarui —
   3 poin C4 = seam billing + lazy trigger + maturity close; ShedLock + `job_run` tetap milik D2, dan exit
   "automated billing/recognition … berjalan" baru tertutup setelah D2. Ini konsisten dengan Addendum §12
   ("**boleh** menjadi step dalam daily scheduler" — bukan wajib) dan §3.3 (job in-process dengan principal
   `SYSTEM`).

---

## Alternatives Considered

1. **Hanya job harian (tanpa lazy billing).** Ditolak: race tengah malam vs jam job, PRD skenario 1 menjadi
   nondeterministik, dan pembayaran tepat waktu ter-booking sebagai credit.
2. **Satu entry agregat per kontrak per hari.** Ditolak: guard `(ref_type, ref_id)` C1 memblokir run pada tanggal
   berikutnya dan re-run parsial per installment menjadi mustahil (installment jatuh tempo di tanggal berbeda).
3. **Method billing di `InstallmentReceivablePort`.** Ditolak: dua implementasi pada satu interface membuat
   inject by type ambigu (`NoUniqueBeanDefinitionException`), dan ukuran `@Primary` menyembunyikan pilihan itu.
4. **Auto-close di `PaymentApplicationService`.** Ditolak: kepemilikan state machine ada di pemilik data
   (ADR-010 keputusan 2); `payment` tidak boleh memutuskan `ContractStatus`.
5. **Auto-close saat outstanding = 0.** Ditolak: menutup kontrak yang masih menyisakan piutang bunga (kasus data
   pra-C4) dan bertentangan dengan §13 (credit tersisa tetap liability).
6. **Scheduler ShedLock + `job_run` di dalam C4.** Ditolak: menduplikasi D2 dan mencampur dua cerita; sebagai
   gantinya keputusan sprint diubah eksplisit (keputusan 9).
7. **Guard `recognized == 0`.** Ditolak: melewatkan periode berbunga nol, dan mengikat urutan penulis kolom
   `recognized_interest_amount` — padahal V3 sengaja membuat invariant nominal deferred justru agar urutan bebas.

---

## Consequences

- **Test:** 357 hijau / 0 gagal (baseline 343 → +13: `InstallmentRecognitionTest` 6 unit, `InstallmentBillingIT` 7
  IT, dan 1 end-to-end maturity close di `PaymentApiIT`). Golden test dan `InstallmentBalanceIT` tidak disentuh;
  `PaymentApiIT` kehilangan seluruh resep `billInterest(...)` SQL (tersisa `accruePenalty` untuk D1).
- **Invariant 10/13 tetap terjaga:** `recognized <= interest` dan cap resolusi dievaluasi V3 pada COMMIT, dan
  billing hanya **melonggarkan** kedua batas itu, jadi urutan statement tidak relevan.
- **PRD skenario 1 benar tanpa seeding:** pembayaran pada due date mengalokasikan bunga lebih dulu, dan
  `PaymentApiIT` memverifikasi jurnal `BILLING` (akun, `entry_date = due_date`, `ref_id = installment`).
- **(−) Beban per transaksi:** satu pembayaran dapat membawa backlog billing seluruh due window kontrak
  (maksimum `tenor` entry). Diterima untuk MVP; job harian D2 akan mengosongkan backlog sebelum pembayaran tiba.
- **(−) Billing yang gagal berakhir 500 `INTERNAL_ERROR`**, bukan skip (mis. `BILLING` untuk installment itu sudah
  pernah diposting padahal `recognized` belum naik — hanya mungkin dari data di luar jalur C4). Disengaja agar
  penyimpangan data terlihat.
- **Risiko residual:** installment `SETTLED`/`WRITTEN_OFF` yang belum di-bill tidak di-bill C4; alur settlement
  (E2) dan write-off (F5) mengakui bunganya sendiri, dan reconciliation F1 menjadi backstop-nya.

**Referensi:** DM §1.3/§1.4/§1.13, §3 invariant 10/13/17; Addendum §3.3/§5/§7.1/§12/§13; TS §1/§2.0/§3/§5;
V1 (`installment`, `accounts`, `journal_*`), V3 (nominal deferred), V4 (state coherence); ADR-008/ADR-009/ADR-010.

