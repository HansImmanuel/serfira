# ADR-009: Semantik Allocation Engine (C2)

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** Hans (solo developer)

---

## Context

Story C2 (`05_SPRINT_PLAN.md` §5 Sprint 3) membangun allocation engine: mesin murni yang memutuskan berapa
dari satu pembayaran masuk ke denda, bunga, pokok, dan berapa yang menjadi excess. Skema (`payment`,
`payment_allocation`), trigger deferred V3, vocabulary `PENALTY|INTEREST|PRINCIPAL|EXCESS`, dan definisi
receivable (`InstallmentBalance`, DM §1.4) sudah ada sejak V1/C1. Yang belum pernah diputuskan:

1. **Window kelayakan alokasi**: apakah engine hanya boleh menyelesaikan angsuran yang **sudah jatuh tempo**,
   atau semua angsuran dari yang tertua (termasuk prepayment pokok masa depan)?
2. **Granularitas waterfall**: per angsuran (`PENALTY→INTEREST→PRINCIPAL` di dalam satu angsuran) atau
   per komponen lintas angsuran (semua denda dahulu, lalu semua bunga, …)?
3. **Seam antar-modul**: engine ada di modul `payment`, sedangkan datanya milik `contract` (DM §1.4).
   TS §1 tidak memberi edge `payment → contract.domain`, sementara `10-architecture` mewajibkan cross-module
   lewat application-service interface.
4. **Siapa yang menurunkan `InstallmentStatus`/`paid_at`** setelah alokasi diterapkan.
5. **Bentuk EXCESS**: satu baris atau per angsuran; dan bagaimana baris bernilai nol diperlakukan
   (`ck_payment_allocation_amount CHECK (amount > 0)`, invariant 6).
6. **Semantik error**: mana yang 4xx (klien bisa memperbaiki) dan mana yang 500 (pelanggaran internal).
7. **Scope C2**: engine murni saja, atau termasuk entity + repository + integration test write path.
8. **Ketergantungan ke C4 (billing/recognition)**: bunga baru menjadi receivable saat due date di-bill
   (TS §3, Addendum §12), jadi PRD skenario 1 (lunas tepat waktu) belum bisa menghasilkan `PAID` penuh
   sebelum C4 ada.

---

## Decision

1. **Window kelayakan = angsuran yang sudah jatuh tempo** (`due_date <= business date` pembayaran). Engine
   memfilter sendiri (aturan hidup di engine, bukan di caller), lalu mengurutkan `(due_date asc, period_no asc)`.
   Sisanya — termasuk pembayaran yang datang **sebelum due date pertama** — menjadi **satu** baris EXCESS
   (`installment_id IS NULL` → credit `TITIPAN_NASABAH`, PRD P-4).
   Dasar: PRD §3 "angsuran paling jatuh tempo dahulu", P-4 "lebih dari **tagihan**", PRD skenario 4
   ("excess … **tidak otomatis lunasi angsuran berikutnya**"), dan Addendum §2.1/§2.4 ("bila installment
   berikutnya belum memiliki recognized receivable, credit tetap AVAILABLE sampai receivable tersebut diakui").
2. **Waterfall per angsuran**: angsuran tertua dahulu; di dalam satu angsuran `PENALTY → INTEREST → PRINCIPAL`;
   pindah ke angsuran berikutnya hanya setelah kapasitas angsuran tersebut habis.
3. **Kapasitas mengikuti definisi receivable DM §1.4 dan cap trigger V3 (invariant 3, 6, 9):**
   - `headroom(PENALTY)   = penalty_amount − Σ penalty_adjustment − Σ active PENALTY allocation`
   - `headroom(INTEREST)  = recognized_interest_amount − Σ active INTEREST allocation` — bunga terjadwal yang
     belum recognized **tidak pernah** dialokasikan
   - `headroom(PRINCIPAL) = principal_amount − Σ active PRINCIPAL allocation`
   - kapasitas per angsuran = `recognized_total − resolved_amount` (`= Σ headroom − settled − written_off`),
     sehingga `paid + settled + written_off <= recognized_total` tetap benar saat commit.
   Headroom negatif = data korup → exception internal, bukan alokasi yang "diperbaiki" diam-diam.
4. **Engine menerima value record milik modul `payment`** (`InstallmentAllocationInput`) berisi angka komponen
   apa adanya (nama field mengikuti kolom DM §1.4). `com.serfira.payment.*` tidak pernah meng-import
   `com.serfira.contract.*`; adapter C3 (lewat application service modul `contract`) yang membaca baris
   installment dan membangun record ini. Formula cap dipin oleh unit test ke DM §1.4/§3 dan trigger V3,
   sehingga tidak ada definisi receivable kedua yang dibiarkan melenceng (`InstallmentBalance` tetap rujukan).
5. **Engine hanya mengembalikan angka**: baris alokasi + excess + total. Engine tidak mengenal
   `InstallmentStatus`/`paid_at` (vocabulary `contract.domain`, DM §1.4); angsuran yang sudah selesai cukup
   "dilewati" karena kapasitasnya nol. `PAID`/`PARTIALLY_PAID` diturunkan modul `contract` di C3.
6. **Bentuk hasil**: baris bernilai nol tidak pernah dibuat; EXCESS hanya dibuat bila sisa > 0 dan maksimal
   satu baris dengan `installmentRef = null` — persis `ck_payment_allocation_amount` dan
   `ck_payment_allocation_excess`. `Σ seluruh baris = payment amount` (invariant 6) dijaga di domain.
7. **Tanpa rounding di engine.** Semua input uang skala 2; engine hanya membandingkan, mem-`min`, dan
   mengurangkan, lalu menormalkan output dengan `setScale(2, RoundingMode.UNNECESSARY)` (preseden
   `LedgerPosting`, C1). Uang yang tak bisa direpresentasikan (mis. 3 desimal) gagal keras, tidak dibulatkan.
8. **Semantik error:** pelanggaran kontrak engine (`null`, amount ≤ 0, headroom negatif, duplikat
   `(due_date, period_no)` atau duplikat installment, uang sub-sen) → `IllegalArgumentException` /
   `NullPointerException` (preseden `ScheduleEngine`), yaitu 500 `INTERNAL_ERROR` karena tidak ada klien yang
   bisa memperbaikinya. Penolakan yang **bisa** diperbaiki klien (kontrak tidak dikenal, DRAFT tanpa jadwal,
   kontrak tidak pay-able) adalah tanggung jawab lapisan aplikasi C3 dengan `SerfiraException` 4xx — engine
   sengaja memperlakukan jadwal kosong sebagai "semua EXCESS" dan mendokumentasikan bahwa caller tidak boleh
   memanggilnya untuk kontrak yang tidak boleh menerima pembayaran.
9. **Scope C2 = engine murni + unit test.** Tanpa entity, repository, migrasi, atau integration test write
   path: C2 tidak menyentuh DB, dan sisi DB invariant 6 sudah teruji independen di `AccountingInvariantsIT`.
   Entity `Payment`/`PaymentAllocation`, transaksi, `Idempotency-Key`, dan posting jurnal adalah C3.

---

## Alternatives Considered

- **Alokasi lintas semua angsuran tanpa melihat due date (prepay pokok masa depan).** Ditolak: bertentangan
  langsung dengan PRD skenario 4 ("tidak otomatis lunasi angsuran berikutnya") dan Addendum §2.1; selain itu
  pembayaran tepat sebelum due date akan melunasi pokok angsuran berikutnya sementara bunganya yang belum
  recognized terdorong ke EXCESS — satu angsuran berakhir `PARTIALLY_PAID` dengan credit untuk bunganya sendiri.
- **Window = due date **atau** angsuran unresolved paling awal (menerima pembayaran angsuran lebih awal).**
  Ditolak karena menghasilkan percampuran komponen yang sama seperti di atas, dan tidak ada dasarnya di dokumen.
- **Waterfall per komponen lintas angsuran** (semua denda tertua dahulu, lalu semua bunga, …). Ditolak:
  PRD §3 menempelkan urutan itu "pada angsuran … dahulu", dan invariant 9 mendefinisikan penalty outstanding
  per angsuran; alokasi per komponen akan melunasi denda periode berikutnya sementara pokok periode tertua
  masih terbuka.
- **Engine menerima entity `Installment`/`InstallmentBalance` milik `contract`.** Ditolak: menciptakan edge
  `payment → contract.domain` yang tidak diberikan TS §1, dan mengikat engine murni ke state JPA.
- **Engine menurunkan status installment.** Ditolak: duplikasi vocabulary & state machine DM §1.4 di modul
  `payment`.
- **C2 sekalian membuat entity + repository + IT write path.** Ditolak (scope 5 pts): write path tanpa
  transaksi pembayaran, idempotency key, dan posting jurnal hanya akan menjadi jalur tulis kedua yang
  setengah jadi; C3 adalah story yang memilikinya.
- **Membungkus input engine dalam `AllocationRequest`.** Ditolak untuk sekarang: tiga parameter eksplisit
  (`paymentAmount`, `businessDate`, `installments`) mengikuti preseden `ScheduleEngine.generate(...)`;
  wrapper dibuat saat ada caller kedua yang benar-benar membutuhkannya.
- **Rounding `HALF_EVEN` di dalam engine.** Ditolak: output hanya `min`/pengurangan dari input skala 2;
  rounding akan menyembunyikan caller yang mengirim uang sub-sen.

---

## Consequences

**Positif**
- Engine deterministik dan murni: tanpa Spring, DB, Docker — 20+ skenario P-2/P-3 diuji sebagai unit test.
- Cap engine identik dengan trigger V3, sehingga alokasi yang diterima engine tidak mungkin gagal di COMMIT
  karena invariant 3/9.
- EXCESS mustahil menaikkan `installment.paid_amount` secara konstruksi (`installmentRef = null`), dan
  `Σ baris = payment amount` (invariant 6) dijaga di domain sebelum menyentuh DB.
- Status installment tetap satu sumber di modul `contract`; engine bisa dipakai ulang oleh settlement dan
  credit application tanpa menyeret vocabulary status.

**Negatif / Tradeoff**
- Modul `payment` memiliki record cap sendiri yang harus dijaga sinkron dengan DM §1.4/`InstallmentBalance`;
  mitigasinya unit test + pointer Javadoc, bukan tipe bersama (yang akan membalik arah dependency).
- Pembayaran lebih awal (sebelum due date pertama) menjadi credit, bukan pelunasan angsuran lebih awal;
  konsumsi credit menunggu E3 dan hanya boleh terhadap receivable yang sudah recognized.
- PRD skenario 1 (lunas tepat waktu) baru utuh setelah C4: di Sprint 3, `recognized_interest_amount` dan
  `penalty_amount` di-seed lewat SQL di integration test (resep `InstallmentBalanceIT`), atau C4 dikerjakan
  sebelum C3.

**Risiko yang dicatat (belum diselesaikan)**
- Trigger V3 menjumlahkan **semua** baris `payment_allocation` (append-only) tanpa melihat status payment,
  sedangkan DM invariant 7 / Addendum §6 menghitung hanya alokasi **aktif**. Setelah void (E4), alokasi ulang
  uang yang sama ke installment yang sama akan bertabrakan dengan cap DB. Penyelesaiannya (mis. cap difilter
  `payment.status = 'POSTED'`, atau alokasi void digantikan reversal) adalah keputusan E4 — dicatat di sini
  supaya tidak ditemukan mendadak saat commit.
- `allocatedPenalty/Interest/Principal` pada input engine berarti alokasi **aktif** (payment POSTED); adapter
  C3 wajib memfilter status, bukan menjumlahkan semua baris.
- Untuk payment yang besar, C3 sebaiknya menerapkan alokasi sebagai update `paid_amount` per installment
  (bukan satu UPDATE massal) agar optimistic locking (`@Version`) pada `Installment` tetap bermakna.

**Mitigasi**
- `PaymentAllocationEngineTest` (20+ skenario) + test value object untuk bentuk baris dan guard input.
- Golden angka demo (Addendum §18.1: 1.333.333,33 / 240.000,00) dipakai sebagai expected value.
- C3 memakai `TransactionTemplate` untuk menguji perilaku commit-time V3 (preseden `LedgerPostingIT`).

---

## References

- `01_PRD.md` §3 (aturan alokasi), §4.3 (P-2, P-3, P-4), §5 skenario 1–4
- `02_TECH_SPEC.md` §1 (dependency modul), §2.1 (uang), §4.3 (denda), §5 (constraint), §6 (testing)
- `03_DOMAIN_MODEL.md` §1.4 (installment + derived), §1.7–1.8 (payment, payment_allocation), §3 invariant 3, 6, 7, 9, 13
- `04_GAPS_ADDENDUM.md` §2 (credit policy), §6 (void → recalc), §10A (payment date policy), §12 (recognition), §16.5 (semantik alokasi), §18.1 (golden demo)
- `05_SPRINT_PLAN.md` §5 Sprint 3 (C2), §3 (DoD)
- ADR-002 (double-entry), ADR-008 (posting semantics)
- Implementasi: `PaymentAllocationEngine`, `InstallmentAllocationInput`, `AllocationLine`, `AllocationResult`

