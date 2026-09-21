# ADR-008: Semantik Posting Ledger (C1)

- **Status:** Accepted
- **Date:** 2026-09-21
- **Deciders:** Hans (solo developer)

---

## Context

Story C1 (`05_SPRINT_PLAN.md` §5 Sprint 3) membangun modul `ledger` pertama: entity + posting service +
balance invariant test. Skema sudah lengkap sejak V1 (`accounts`, `journal_entry`, `journal_line`),
immutability trigger sudah ada di V1, deferred balance trigger di V3, dan COA 10 akun sudah di-seed.
Yang belum pernah diputuskan adalah perilakunya:

1. **Siapa yang mem-posting jurnal aktivasi/disburse.** TS §3 memuat baris "Aktivasi kontrak (disburse) |
   Debit PIUTANG_POKOK | Kredit KAS"; ADR-006 menyatakan posting aktivasi adalah pekerjaan story **C1**;
   tabel Epic C di sprint plan menaruh "aktivasi/disburse" di **C4**. B5 sengaja tidak mem-posting apa pun,
   sehingga ledger kosong sementara kontrak ACTIVE sudah punya piutang.
2. **Aturan dependency.** TS §1 menyebut `payment`, `penalty`, `settlement` boleh bergantung ke `ledger`;
   `contract` tidak disebut, padahal `10-architecture` mewajibkan cross-module lewat application-service
   interface. Tanpa keputusan eksplisit, modul `contract` tidak boleh memanggil `ledger`.
3. **`ref_type` vocabulary** (`CONTRACT_ACTIVATION`, `PAYMENT`, `BILLING`, …) belum pernah didefinisikan,
   padahal `idx_journal_entry_ref` dan reconciliation job (L-3, Addendum §7.1) bergantung padanya.
4. **Granularitas posting**: satu jurnal per event finansial vs batch harian.
5. **Arti `entry_date` vs `posted_at`**, dan kapan nilainya final.
6. **Idempotensi posting**: apakah perlu unique index DB per `(ref_type, ref_id)`.
7. **Arti kredit `KAS`** pada aktivasi ketika PRD §1.3 menyatakan distribusi dana / funding sebagai non-goal.

---

## Decision

1. **Aktivasi kontrak mem-posting jurnal disbursement-nya di C1**, di dalam transaksi aktivasi yang sudah
   ada, sehingga piutang yang direpresentasikan jadwal langsung ada di ledger sejak detik pertama ia
   collectible (invariant 13 harus selalu benar begitu ledger hidup). TS §3 sudah memuat aturannya, jadi
   tidak ada design risk. Untuk itu **edge `contract → ledger` diresmikan**: `ContractCommandService`
   memanggil `LedgerPostingService` (application-service interface, bukan repository/tabel), dan TS §1
   diperbarui satu baris. `ledger` tetap tidak bergantung pada modul mana pun.
2. **Satu journal entry per event finansial**, minimal 2 baris, jumlah baris bebas (TS §3 "satu atau
   beberapa journal entry" untuk settlement dipenuhi oleh banyak **baris** dalam satu entry balance).
   Reversal adalah entry tambahan dengan `reversal_of_id` terisi dan `ref_type`/`ref_id` **tetap sama**
   seperti event aslinya, sehingga kedua sisi pengoreksian mengelompok pada satu `(ref_type, ref_id)`.
3. **`ref_type` memakai enum `LedgerRefType`** (8 nilai: `CONTRACT_ACTIVATION`, `PAYMENT`, `BILLING`,
   `PENALTY_ACCRUAL`, `PENALTY_WAIVER`, `CREDIT_APPLICATION`, `SETTLEMENT`, `WRITE_OFF`). Kolom tetap
   VARCHAR(40) tanpa CHECK, jadi menambah nilai tidak butuh migrasi.
4. **`entry_date` = tanggal bisnis event**, dikirim caller dalam zone Asia/Jakarta (aktivasi: `start_date`
   pukul 00:00; payment: `paid_at`; billing: `due_date`; penalty: `accrual_date`). **`posted_at` =
   `clock.now()`** dan di-set oleh posting service. Keduanya final saat insert (trigger V1 menolak UPDATE
   pada `journal_entry`), dan `journal_line.entry_date` didenormalisasi dari entry (sesuai komentar V1).
5. **Guard idempotensi di service:** maksimal satu entry **non-reversal** per `(ref_type, ref_id)`.
   **Tidak ada unique index baru di C1**, karena: (a) setiap event sudah punya guard duplicate sendiri di
   level DB (Idempotency-Key + `uq_payment_idempotency`, `contract.version`, `(installment_id, accrual_date)`,
   settlement key), (b) TS §3 masih membuka kemungkinan beberapa journal entry per settlement, sehingga
   index partial `(ref_type, ref_id) WHERE reversal_of_id IS NULL` akan memutus keputusan E2 sebelum
   dibahas, dan (c) alasan V3 memakai backstop DB adalah baris immutable membuat **balance** salah tidak
   bisa diperbaiki; duplicate entry tidak sekelas itu karena bisa dikoreksi dengan reversal journal.
   Index tersebut ditambahkan hanya bila ada story yang benar-benar butuh defense-in-depth DB-level.
6. **Posting tidak pernah membulatkan:** amount harus tepat skala 2 (`RoundingMode.UNNECESSARY`), nilai yang
   butuh pembulatan ditolak. Pembulatan adalah tanggung jawab kalkulasi yang menghasilkan angkanya (sama
   seperti `InstallmentBalance`).
7. **Transaksi `MANDATORY`**: `LedgerPostingService.post` memakai `@Transactional(propagation = MANDATORY)`,
   jadi jurnal dan business write-nya commit atau rollback bersama (ADR-002). Posting di luar transaksi
   ditolak, bukan dibuatkan transaksi sendiri.
8. **Kode akun type-safe:** posting memakai enum `LedgerAccount` (10 akun DM §1.12) sebagai pengganti string
   literal, sehingga typo tidak bisa sampai ke DB; FK `journal_line.account_code` tetap backstop, dan tidak
   ada perubahan COA (tidak ada migrasi).
9. **`KAS` adalah counterparty resmi disbursement.** PRD §1.3 menyatakan funding/treasury non-goal, jadi
   tidak ada pergerakan kas bank yang dimodelkan; konsekuensinya saldo `KAS` menjadi negatif sampai demo
   seed (story G5) menyediakan saldo awal kas. Alternatif lain (akun treasury/liability baru) ditolak
   karena TS §3 sudah menetapkan daftar akunnya.
10. **Tidak ada API surface di C1.** Posting bersifat internal: pemanggilnya payment (C3) dan billing (C4).
11. **Kegagalan posting adalah 500**, bukan 4xx: `LedgerPostingException` sengaja bukan `SerfiraException`
    karena tidak ada client yang bisa memperbaiki jurnal tidak balance.

---

## Alternatives Considered

- **Menunda posting aktivasi ke C4.** Ditolak: ledger tanpa producer membuat `PIUTANG_POKOK` menjadi negatif
  begitu C3 mem-posting pembayaran (kredit piutang atas piutang yang tidak pernah didebit), dan invariant 13
  gagal untuk setiap kontrak ACTIVE sampai Sprint 4. Edge `contract → ledger` juga tetap dibutuhkan C4
  (billing mengubah `recognized_interest_amount` pada installment milik contract), jadi menundanya hanya
  memindahkan pekerjaan, bukan menghindarinya.
- **In-process event / Spring `@TransactionalEventListener` sebagai ganti pemanggilan langsung.** Ditolak:
  listener di `ledger` harus tahu tipe event milik `contract`, sehingga `ledger` bergantung pada modul lain —
  melanggar TS §1 ("`ledger` tidak boleh bergantung ke module lain").
- **Outbox + publisher (TS §2.7) untuk posting.** Ditolak untuk Fase 1–2: TS §2.7 menyatakan Kafka/publisher
  ditunda ke Fase 3, dan posting harus berada di transaksi bisnis yang sama, bukan asinkron.
- **Batch posting harian (satu jurnal per hari).** Ditolak: PRD §3 ("setiap mutasi uang = jurnal minimal 2
  baris") dan reconciliation L-3 membutuhkan level event, bukan agregat harian.
- **Unique index partial `(ref_type, ref_id) WHERE reversal_of_id IS NULL` di C1.** Ditunda (Decision 5).
- **Membiarkan `journal_entry` memakai `Auditable` (dengan `updated_at`/`updated_by`).** Ditolak: kolom
  tersebut memang NULL untuk tabel append-only (komentar V1), dan menulis audit update pada baris yang tidak
  mungkin di-update akan menyesatkan pembaca schema. Dipakai `ImmutableAuditable` (shared) — **khusus
  `journal_entry`/`journal_line`**, satu-satunya tabel yang di V1 punya `updated_at TIMESTAMPTZ NULL`.
- **Klaim awal C1 bahwa tabel append-only lain juga butuh `ImmutableAuditable` — dikoreksi saat C2.** Tabel
  `payment_allocation` dan `penalty_adjustment` (juga `settlement_allocation`,
  `contract_credit_application`) memakai `updated_at TIMESTAMPTZ **NOT NULL**` di V1, sehingga entity-nya
  **wajib** extends `Auditable` (yang mengisi `updated_at`/`updated_by` saat persist). `ImmutableAuditable`
  tidak pernah mengisi kolom itu, jadi insert-nya akan ditolak database. "Append-only" pada tabel-tabel
  tersebut berarti tidak ada UPDATE/DELETE secara bisnis (histori tetap tersimpan), bukan berarti tidak
  memiliki kolom audit update seperti `journal_*`.
- **Kode akun sebagai string biasa di posting rule.** Ditolak: typo hanya ketahuan sebagai FK error di commit;
  enum membuat kesalahan akun mustahil secara compile-time.
- **Membuat entity `Account` + repository di C1.** Ditolak: belum ada yang membacanya (reporting/reconciliation
  menyusul), jadi akan menjadi dead code. Parity COA diuji lewat IT.

---

## Consequences

**Positif**
- Ledger lengkap untuk write path pertama: aktivasi mem-posting `PIUTANG_POKOK`/`KAS` di transaksi yang sama,
  jadi reconciliation check C (Addendum §7.1) tidak lahir dengan exception bawaan.
- Invariant Σ debit = Σ kredit terjaga di tiga lapis: `LedgerPosting` (domain, sebelum insert), service dengan
  `entry_date`/`posted_at` eksplisit, dan trigger deferred V3 di COMMIT.
- Guard `(ref_type, ref_id)` membuat replay/retry tidak bisa double-book event yang sama.
- `ref_type` + `reversal_of_id` memberi vocabulary yang cukup untuk reconciliation dan reversal (E4) tanpa migrasi.

**Negatif / Tradeoff**
- `contract` kini bergantung pada `ledger`; TS §1 harus menyebutnya (dan edge ini tidak boleh dibalik).
- Guard duplicate bersifat application-level: raw SQL yang melewati service tidak dicegah (hanya balance yang
  dijamin DB). Diterima karena semua jalur tulis aplikasi lewat `LedgerPostingService`.
- `KAS` akan negatif sampai demo seed menyediakan saldo awal kas (non-goal funding).
- Satu entry per event membatasi settlement bila nanti benar-benar butuh beberapa entry terpisah; itu harus
  diselesaikan di E2 (mis. `ref_type` berbeda per komponen), bukan dengan melonggarkan guard tanpa keputusan.

**Mitigasi**
- `LedgerPostingIT` menguji commit-time behaviour (termasuk rollback bersama dan posting tanpa transaksi).
- `AccountingInvariantsIT` tetap menjadi backstop independen untuk balance trigger lewat raw SQL.
- Guard dan vocabulary didokumentasikan di `03_DOMAIN_MODEL.md` §1.13.

---

## References

- `02_TECH_SPEC.md` §1 (dependency modul), §3 (ledger + posting rules), §5 (constraint penting)
- `03_DOMAIN_MODEL.md` §1.12 (COA), §1.13 (JournalEntry/JournalLine), §3 invariant 1, 12, 13
- `04_GAPS_ADDENDUM.md` §7.1 (reconciliation), §12 (recognition timing)
- `05_SPRINT_PLAN.md` §5 Sprint 3 (C1)
- ADR-002 (double-entry ledger), ADR-006 (creation & activation), ADR-007 (idempotent creation)
- Implementasi: `LedgerPostingService`, `LedgerPosting`, `JournalEntry`, `LedgerPostingIT`
