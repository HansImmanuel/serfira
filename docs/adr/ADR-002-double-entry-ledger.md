# ADR-002: Double-Entry Ledger dari Hari Pertama

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** Hans (solo developer)

---

## Context

Sistem servicing pinjaman perlu mencatat mutasi uang: pembayaran masuk, alokasi ke pokok/bunga/denda, excess, settlement, write-off. Ada dua pendekatan utama:

1. **Simple balance tracking:** update kolom `paid_amount`, `outstanding` per installment. Mudah diimplementasikan, tapi tidak bisa self-audit dan sulit direkonsiliasi.
2. **Double-entry ledger:** setiap mutasi uang menghasilkan minimal dua baris jurnal (debit = kredit). Standar industri akuntansi. Audit trail penuh, rekonsiliasi otomatis possible.

Portofolio ini bertujuan mendemonstrasikan kemampuan desain sistem finansial yang matang. Double-entry adalah sinyal kuat bahwa developer memahami domain finansial.

---

## Decision

**Implementasikan double-entry ledger sejak Fase 1**, bersamaan dengan domain utama, bukan ditambahkan belakangan.

**Struktur:**
```sql
accounts        (code, name, type: ASSET|LIABILITY|EQUITY|INCOME|EXPENSE)
journal_entries (id, entry_date, ref_type, ref_id, description, reversal_of_id, posted_at)
journal_lines   (id, journal_entry_id, account_code, debit, credit, contract_id)
```

**Chart of Accounts (minimal):**
| Code | Tipe | Keterangan |
|---|---|---|
| KAS | ASSET | Cash masuk |
| PIUTANG_POKOK | ASSET | Receivable pokok |
| PIUTANG_BUNGA | ASSET | Receivable bunga (setelah billing/recognition) |
| PIUTANG_DENDA | ASSET | Receivable denda (setelah accrual) |
| TITIPAN_NASABAH | LIABILITY | Excess payment / saldo kredit nasabah |
| PENDAPATAN_BUNGA | INCOME | Revenue bunga |
| PENDAPATAN_DENDA | INCOME | Revenue denda |
| PENDAPATAN_ADMIN | INCOME | Admin fee settlement |
| DISKON_PELUNASAN | EXPENSE | Rebate settlement (contra-receivable) |
| BIAYA_PENGHAPUSAN_PIUTANG | EXPENSE | Write-off |

**Invariant yang dienforce:**
1. `Σ debit = Σ kredit` per `journal_entry` — diperiksa via DB constraint/trigger + service layer + test.
2. Journal immutable: tidak ada `UPDATE`/`DELETE` pada `journal_entries` dan `journal_lines`. Koreksi via reversal journal baru dengan `reversal_of_id`.
3. Setiap mutasi uang (payment, settlement, write-off, accrual) **wajib** menghasilkan journal entry — tidak ada "silent update" balance.

**Recognition timing yang eksplisit:**
- Bunga: recognized saat due date (billing step), bukan saat jadwal dibuat.
- Denda: recognized incremental harian (penalty job).
- Bunga berjalan settlement: recognized saat settlement execution, sebelum cash collection.
- Future scheduled interest: **tidak pernah** menjadi receivable sebelum billed.

---

## Consequences

**Positif:**
- Self-auditing: `Σ debit = Σ kredit` selalu benar → bug finansial tidak bisa tersembunyi lama.
- Reconciliation otomatis possible: bandingkan saldo `PIUTANG_*` dengan outstanding installment.
- Demonstrasi kompetensi domain finansial yang kuat saat interview.
- Write-off, reversal, dan settlement mempunyai jejak audit yang lengkap dan bisa ditelusuri.
- Consistency check job (L-3) menjadi straight-forward karena ledger adalah source of truth.

**Negatif / Tradeoff:**
- Lebih mahal di awal: setiap event butuh posting rule yang didefinisikan dan di-test.
- Complexity bertambah: developer harus memahami double-entry accounting.
- Lebih banyak tabel dan baris di DB dibanding simple balance tracking.

**Mitigasi:**
- Semua posting rules didokumentasikan di Tech Spec §3 (tabel posting rules).
- Integration test memverifikasi ledger balance setelah setiap skenario.
- `ledger` module adalah modul paling dasar, tidak boleh bergantung ke modul lain.

---

## Alternatives Considered

| Option | Alasan Ditolak |
|---|---|
| Simple balance tracking (update kolom) | Tidak bisa self-audit; reconciliation manual; tidak demonstrable untuk sistem finansial |
| Single-entry ledger | Tidak standar; tidak bisa mendeteksi inkonsistensi internal |
| Tambahkan ledger belakangan | Retrofit ledger ke sistem yang sudah berjalan sangat mahal; bug finansial lama mungkin sudah terjadi |
| Event sourcing sebagai pengganti ledger | Terlalu kompleks; ledger double-entry lebih dikenal di domain finansial tradisional |

---

## References

- Tech Spec §2: Design Ledger (double-entry)
- Tech Spec §3: Posting rules tabel
- PRD §3: Definisi Bisnis — Ledger
- [Patterns of Enterprise Application Architecture — Fowler] — Accounting patterns
