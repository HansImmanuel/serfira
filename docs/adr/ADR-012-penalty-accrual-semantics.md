# ADR-012: Semantik Accrual Denda Harian (D1)

- **Status:** Accepted
- **Date:** 2026-09-24
- **Deciders:** Hans (solo developer)

---

## Context

Story D1 (`05_SPRINT_PLAN.md` §5 Sprint 4) membuat **denda menjadi receivable**: TS §4.3 sudah menetapkan
formulanya, tetapi belum ada keputusan tentang bagaimana formula itu diterjemahkan menjadi baris
`penalty_accrual`, siapa yang memiliki tabelnya, dan bagaimana catch-up berperilaku saat job tidak berjalan
setiap hari. Yang belum diputuskan:

1. **Kepemilikan data & seam.** `penalty_accrual` adalah tabel Epic D (DM §1.9), tetapi
   `installment.penalty_amount` dimiliki aggregate `contract` — dan antar-module dilarang menyentuh tabel
   satu sama lain (`.clinerules/10-architecture`). Modul `penalty` belum ada.
2. **Definisi "saldo tagihan pokok+bunga yang belum dibayar" (TS §4.3, PRD D-1).** Rincian komponen alokasi
   (berapa dari pembayaran yang membayar pokok vs bunga vs denda) **tidak** tersimpan di `installment`;
   ia hidup di `payment_allocation`/`settlement_allocation` milik modul lain.
3. **Granularitas baris accrual dan jurnalnya.**
4. **Perilaku catch-up** saat job tidak jalan beberapa hari, dan — yang paling penting — perilaku saat
   **base turun** karena pembayaran sebagian: nilai accrual yang sudah diakui tidak boleh menekan accrual
   harian berikutnya yang sebenarnya masih berhak ditagih.
5. **Kapan step berjalan** (job vs jalur pembayaran) dan siapa yang menandai `OVERDUE`.

---

## Decision

1. **Modul baru `com.serfira.penalty` memiliki `penalty_accrual`.** `installment` tetap milik `contract`,
   jadi kenaikan `penalty_amount` lewat seam baru `contract.application.InstallmentPenaltyPort`
   (`loadPenaltySnapshot(contractId)` + `applyPenaltyAccrual(contractId, perInstallment)`), diimplementasikan
   `contract.application.InstallmentPenaltyService` — resep yang sama dengan ADR-010 (payment→contract) dan
   ADR-011 (billing). Edge baru: `penalty → contract (application interface)` dan `penalty → ledger`; TS §1
   diperbarui. Port membaca snapshot **dan** menerapkan delta di transaksi pemanggil (implementasinya sengaja
   tidak `@Transactional`, seperti `InstallmentReceivableService`).
2. **Base denda = `max(0, principal_amount + recognized_interest_amount − paid_amount − settled_amount −
   written_off_amount)`** (`InstallmentBalance.penaltyBase`). Ini bacaan paling dekat dari TS §4.3
   ("pokok+bunga yang belum dibayar") yang bisa dihitung **tanpa** membaca tabel modul lain:
   bunga yang dihitung adalah bunga yang **sudah diakui/billed** (PRD §5C), dan seluruh resolusi
   (payment/settlement/write-off) mengurangi base. Konsekuensi yang didokumentasikan: karena waterfall
   membayar denda lebih dulu, denda yang sudah dibayar ikut mengurangi base untuk hari-hari berikutnya —
   konservatif, selisihnya paling besar sebesar denda yang sudah dibayar.
3. **Satu baris `penalty_accrual` per hari yang masih berhak ditagih, bukan "cumulative snapshot".**
   Untuk business date `D`, step menagih setiap tanggal `x` dengan `due_date + grace + 1 ≤ x ≤ D` yang
   **belum punya baris accrual**: `amount = round(base × penalty_rate_daily, HALF_EVEN, 2)`,
   `accrual_date = x`, `days_late = hariTelat(x) = max(0, x − due_date − grace)` (TS §4.3). Ini bacaan
   harfiah TS §4.3 (`recognized_penalty = Σ denda_harian`) dan DM §1.9 ("`amount` adalah delta hari itu").
4. **Kelayakan bersifat per tanggal, tidak pernah kumulatif.** Step tidak pernah membandingkan
   `base × rate × hariTelat` dengan nilai yang sudah diakui, sehingga tidak ada cabang aritmetika yang bisa
   bernilai negatif/nol dan **menekan** accrual hari berikutnya: base yang turun hanya membuat hari
   berikutnya lebih murah (`73.333,33 × 0.0010 = 73,33/hari`), bukan berhenti. Jendela dihitung dari
   **himpunan tanggal yang sudah ada**, bukan dari `max(accrual_date)`: tanggal yang dulu dilewati (base 0)
   tetap bisa ditagih kembali setelah base pulih (mis. setelah void).
5. **Hanya nilai positif yang pernah ditulis.** `amount > 0` (V1 `ck_penalty_accrual_amount`),
   `days_late ≥ 1`; penurunan denda adalah alur `penalty_adjustment` (WAIVE/REDUCE) milik E5 yang append-only
   dan berjurnal — step accrual tidak pernah menulis pembalik.
6. **Satu jurnal `PENALTY_ACCRUAL` per baris accrual** — `ref_id = penalty_accrual.id`,
   `entry_date = accrual_date` (pukul 00:00 Asia/Jakarta, konvensi ADR-008/ADR-011), debit `PIUTANG_DENDA` =
   kredit `PENDAPATAN_DENDA`, `contract_id` di kedua baris. Alternatif `ref_id = installment.id` ditolak:
   guard `(ref_type, ref_id)` C1 akan memblokir accrual hari berikutnya (jebakan yang sama dengan billing
   agregat di ADR-011); idempotensi utama tetap unique `(installment_id, accrual_date)` V1.
7. **Catch-up Addendum §6 direalisasikan oleh mekanisme yang sama.** E4 (void) cukup memanggil step ini lagi
   untuk business date-nya: tanggal yang belum berbaris dan kembali layak akan ditagih dengan base yang sudah
   pulih, dan setiap baris membawa `entry_date` tanggalnya sendiri — persis konvensi "materialisasi tanggal
   bisnis yang sudah lewat" yang sudah dipakai billing (`entry_date = due_date`). Tidak ada baris accrual yang
   pernah di-update/di-delete, sehingga "recalc tidak mengubah histori accrual" tetap benar.
8. **Step berdiri sendiri dan `@Transactional` (`REQUIRED`)**, dipanggil siapa pun yang memilikinya:
   job harian D2 (yang membungkus ShedLock + `job_run`) dan E4. **Tidak** dipanggil dari
   `PaymentApplicationService` pada D1 — PRD D-1/Addendum §12 menyebut "job harian", dan menambahkan lazy
   trigger berarti mengubah tanggal bisnis & angka harapan seluruh suite payment IT. Pertanyaan lazy trigger
   dan penandaan `OVERDUE`/aging dipindahkan eksplisit ke **D2** dengan ADR sendiri. D1 **tidak menulis
   status installment** (`InstallmentStatus` tetap urusan job aging).
9. **Amandemen dokumen, bukan deviasi diam.** PRD §5 skenario 2 ("telat 10 hari — denda 10 hari terhitung")
   bertentangan dengan TS §4.3 + PRD D-3 (`grace 3 hari tanpa denda`): dengan grace 3, hari yang ditagih
   adalah 7. TS §4.3 adalah otoritas perhitungan, jadi PRD skenario 2 diperjelas (7 hari dengan grace 3;
   10 hari bila grace 0).

---

## Alternatives Considered

1. **`contract` menulis `penalty_accrual` sendiri** (tanpa modul `penalty`). Ditolak: pelanggaran batas modul
   dan kepemilikan tabel Epic D; kebijakan grace/rate/backlog jadi berada di modul yang salah.
2. **`penalty` menulis `installment` langsung (JPA/native).** Ditolak: menyentuh tabel modul lain; seam
   `InstallmentPenaltyPort` konsisten dengan ADR-010/ADR-011.
3. **Delta terhadap "expected gross" kumulatif** (`expectedGross = round(base × rate × hariTelat)`, akui bila
   `expectedGross > recognized`). Inilah rencana awal, **ditolak setelah diperiksa**: pada fixture demo,
   pembayaran sebagian menurunkan base `1.573.333,33 → 73.333,33`, sehingga `expectedGross` jatuh di bawah
   nilai yang sudah diakui dan **semua accrual harian berikutnya tertekan sampai ±43 hari** — hak tagih
   perusahaan hilang hanya karena urutan aritmetika. Digantikan keputusan 3–4.
4. **Base = `recognized_total − resolved` (termasuk denda belum dibayar).** Ditolak: denda berbunga di atas
   denda, bertentangan dengan base eksplisit PRD D-1 ("pokok+bunga").
5. **Membaca `payment_allocation`/`settlement_allocation` demi base pokok/bunga eksak.** Ditolak: arah
   dependensi salah (`penalty`/`contract` membaca tabel `payment`), demi presisi yang selisihnya paling besar
   sebesar denda yang sudah dibayar.
6. **Menambah kolom `paid_principal`/`paid_interest`/`paid_penalty` di `installment`.** Ditolak untuk D1:
   migrasi V8 + menulis ulang resolusi C3 + trigger V3 demi presisi yang sama dengan keputusan 2.
7. **Jendela berbasis `max(accrual_date)`.** Ditolak: tanggal yang dilewati (base 0) akan "tersegel" selamanya
   sehingga catch-up §6 kehilangan hari yang seharusnya ditagih setelah void.
8. **Menjalankan accrual dari jalur pembayaran (lazy) di D1.** Ditunda ke D2, bukan ditolak: sisa risiko yang
   terdokumentasi hanya pembayaran yang datang **sebelum** run harian untuk installment yang langsung lunas
   (base menjadi 0 sehingga hari itu tidak tertagih). D2 memiliki scheduler + `job_run` dan dapat memutuskan
   lazy trigger beserta konsekuensi test-nya.

---

## Consequences

- **Test:** +31 (`PenaltyCalculatorTest` 14 unit murni, `InstallmentPenaltyAccrualTest` 6 unit domain,
  `PenaltyAccrualIT` 11 IT) dan `PaymentApiIT` kehilangan seed SQL `accruePenalty` — skenario 2 PRD kini
  dijalankan oleh step D1 yang nyata (28 baris accrual = `44.053,24`, pembayaran `1.617.386,57`).
  Golden test, `InstallmentBalanceIT`, dan `InstallmentBillingIT` tidak disentuh.
- **Invariant:** `installment.penalty_amount = Σ penalty_accrual.amount` per installment (diuji);
  invariant 8 (satu accrual per `(installment_id, accrual_date)`) ditegakkan DB; V3 (`Σ PENALTY allocation ≤
  penalty_amount − Σ adjustment`) tetap otoritatif dan accrual hanya **melonggarkan** batas itu.
- **Tanpa migrasi** dan tanpa perubahan API/OpenAPI: tabel, index, constraint, COA, dan `ref_type`
  `PENALTY_ACCRUAL` sudah ada di V1.
- **(−) Beban per run:** catch-up setelah job lama mati menulis satu baris + satu jurnal per hari
  (± `DPD` maksimum). Diterima: job D2 berjalan harian sehingga backlog normal hanya beberapa baris, dan
  `job_run.records_processed` membuat backlog terlihat.
- **(−) Base catch-up memakai base saat run**, bukan base historis tiap hari; arah kesalahannya selalu
  konservatif (menagih lebih sedikit), tidak pernah menagih lebih.
- **Risiko residual:** (i) pembayaran sebelum run harian pada installment yang langsung lunas membuat hari itu
  tidak tertagih — calon utama lazy trigger di D2; (ii) E2 (settlement) harus menjalankan step ini untuk
  tanggal bisnisnya sebelum menghitung `penaltyOutstanding` (TS §4.4), jika tidak hari-hari yang belum
  ter-accrual tidak ikut masuk quote — dicatat di sprint plan story E2.

**Referensi:** DM §1.4/§1.9/§1.10, §3 invariant 8/9/10; PRD D-1/D-3, §5 skenario 2, §5A, §5C; TS §2.1/§2.3/§3/
§4.3/§4.4/§5/§6; Addendum §6/§10/§12/§16.4/§18.1; V1 (`penalty_accrual`, `penalty_adjustment`, `accounts`),
V3 (cap alokasi deferred); ADR-008/ADR-010/ADR-011.
