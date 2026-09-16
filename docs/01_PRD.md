# PRD — Multifinance Loan Servicing Core System

> Project portofolio: core system untuk perusahaan multifinance, fokus fase **servicing**
> (setelah kontrak aktif sampai lunas). Origination hanya disimulasikan.

- Versi: 0.3 (reconciled + final pre-code review)
- Status: Approved for development
- Stack: Java 21 + Spring Boot 4.1.1 + PostgreSQL 16 + Next.js (frontend)

---

## 1. Latar Belakang & Tujuan

### 1.1 Konteks
Perusahaan multifinance (pembiayaan motor/mobil/elektronik) membutuhkan sistem yang mengelola
siklus hidup kontrak pembiayaan setelah kontrak disetujui: generate jadwal angsuran, menerima
pembayaran, menghitung denda, menangani pelunasan dipercepat, dan menghasilkan laporan.

### 1.2 Goals
1. Menyediakan engine perhitungan angsuran yang akurat dan teruji (flat & efektif/anuitas).
2. Mencatat seluruh mutasi uang secara double-entry sehingga sistem audit-able.
3. Menangani semua skenario pembayaran: lunas, sebagian, lebih, telat, lebih awal.
4. Menyediakan dashboard operasional dan laporan manajemen.
5. Menjadi portofolio yang mendemonstrasikan kemampuan desain sistem finansial yang kompleks.

### 1.3 Non-Goals (eksplisit di luar scope)
- Origination end-to-end (scoring, SLIK/e-KYC, approval workflow) — hanya simulasi input kontrak.
- Distribusi dana / funding / treasury.
- Collection & field visit (disamakan dengan modul aging saja).
- Integrasi payment gateway sungguhan (VA, e-wallet) — simulasi via API internal + webhook stub.
- Multi-currency, multi-branch (design diperbolehkan, implementasi fase lanjut).

---

## 2. Pengguna & Role

| Role | Deskripsi | Akses utama |
|---|---|---|
| Admin Operasional | Input kontrak, input pembayaran manual (teller), koreksi | Semua modul operasional |
| Finance/Accounting | Lihat ledger, jurnal, rekap mutasi | Ledger + reporting |
| Manajemen | Lihat dashboard & laporan | Reporting read-only |
| System | Job scheduler (aging, denda) | Internal only |

Auth: username/password + JWT, role-based access control. Fase 1 cukup 1 role admin + read-only.

---

## 3. Definisi Bisnis Utama

- **Kontrak (Contract):** perjanjian pembiayaan atas satu asset untuk satu nasabah.
- **Harga aset (asset_price):** nilai transaksi aset yang menjadi snapshot kontrak.
- **Plafon (Principal):** jumlah pembiayaan setelah DP dikurangi; MVP menetapkan `asset_price = principal + down_payment`.
- **Tenor:** jumlah periode angsuran (bulanan).
- **Suku bunga:** flat (% dari plafon awal per bulan) atau efektif (% dari outstanding per bulan). Semua rate disimpan sebagai fraction desimal di DB; contoh 1.5% = `0.0150`.
- **Angsuran (Installment):** kewajiban per periode, terdiri dari pokok + bunga + denda efektif yang sudah diakui. Status `SETTLED` dan `WRITTEN_OFF` membedakan penyelesaian non-payment dari `PAID`.
- **Denda (Penalty):** biaya keterlambatan, dihitung per hari dari angsuran tertunggak.
- **Aging:** klasifikasi keterlambatan (Current, 1–30, 31–60, 61–90, >90 hari).
- **Pelunasan dipercepat (Early Settlement):** pembayaran seluruh outstanding sebelum tenor habis. Quote tidak menagih bunga terjadwal masa depan; yang ditagih adalah pokok outstanding, tagihan bunga yang sudah jatuh tempo/belum terbayar, bunga berjalan periode aktif sampai tanggal settlement, denda outstanding, dikurangi rebate lalu ditambah admin fee.
- **Ledger:** pencatatan double-entry; setiap mutasi uang = jurnal minimal 2 baris (debit = kredit). Bunga dan denda hanya menjadi receivable setelah event recognition/billing; future scheduled interest belum menjadi receivable.

### Aturan alokasi pembayaran sebagian (default, konfigurabel)
Alokasi berurutan: **denda → bunga → pokok** pada angsuran paling jatuh tempo dahulu. Settlement bukan Payment biasa: ia mempunyai record, snapshot quote, settlement allocations, dan journal sendiri.

---

## 4. Functional Requirements

### 4.1 Modul Kontrak (Contract)
| ID | Requirement | Prioritas |
|---|---|---|
| C-1 | Create kontrak: nasabah, asset, plafon, DP, tenor, skema bunga (FLAT/EFFECTIVE), suku bunga, tanggal mulai | P0 |
| C-2 | Generate jadwal angsuran otomatis saat kontrak aktivasi | P0 |
| C-3 | Status kontrak: DRAFT → ACTIVE → CLOSED / TERMINATED; CLOSED dapat terjadi saat maturity penuh atau settlement | P0 |
| C-4 | List & detail kontrak, sisa outstanding per kontrak | P0 |
| C-5 | Update data kontrak hanya saat DRAFT | P1 |

### 4.2 Modul Jadwal Angsuran (Schedule)
| ID | Requirement | Prioritas |
|---|---|---|
| S-1 | Perhitungan FLAT: bunga = plafon × rate × tenor; angsuran pokok = plafon / tenor (sama tiap bulan) | P0 |
| S-2 | Perhitungan EFFECTIVE (anuitas): angsuran sama tiap bulan, komposisi pokok naik seiring waktu | P0 |
| S-3 | Penentuan due date tiap installment (tanggal mulai + n bulan, dengan month-end clamp; weekend/holiday tidak digeser pada MVP) | P0 |
| S-4 | Status installment: PENDING/PARTIALLY_PAID → PAID / OVERDUE / SETTLED / WRITTEN_OFF sesuai event | P0 |
| S-5 | Reschedule/restructuring (ubah jadwal outstanding) | P2 |

### 4.3 Modul Pembayaran (Payment)
| ID | Requirement | Prioritas |
|---|---|---|
| P-1 | Terima pembayaran (tunai/transfer manual); idempotent via Idempotency-Key; `paid_at` tidak boleh future dan backdate di luar policy MVP ditolak | P0 |
| P-2 | Alokasi otomatis: denda → bunga → pokok, angsuran paling lama dahulu | P0 |
| P-3 | Pembayaran sebagian (partial) → installment jadi PARTIALLY_PAID | P0 |
| P-4 | Pembayaran lebih dari tagihan → excess sebagai saldo kredit kontrak; kredit tidak otomatis diterapkan dan punya histori application terpisah | P1 |
| P-5 | Void/reversal pembayaran (dengan jurnal pembalik, bukan hapus) | P1 |
| P-6 | Simulasi webhook payment gateway (endpoint stub untuk demo) | P2 |

### 4.4 Modul Denda & Aging (Penalty)
| ID | Requirement | Prioritas |
|---|---|---|
| D-1 | Hitung denda otomatis per hari keterlambatan (job harian): denda = rate_harian × hari_telat × saldo tagihan tertunggak (pokok+bunga yang belum dibayar) | P0 |
| D-2 | Aging report per kontrak & portofolio | P0 |
| D-3 | Grace period konfigurabel (misal 3 hari tanpa denda) | P1 |
| D-4 | Waive/reduksi denda (dengan approval note) | P1 |

### 4.5 Modul Pelunasan (Settlement)
| ID | Requirement | Prioritas |
|---|---|---|
| E-1 | Kalkulasi pelunasan dipercepat dengan quote snapshot: pokok outstanding + bunga tertunggak/belum terbayar + bunga berjalan + denda outstanding − rebate + biaya admin; future scheduled interest tidak ditagihkan | P1 |
| E-2 | Eksekusi settlement berdasarkan quote yang masih valid → Settlement record + allocation + jurnal → kontrak CLOSED | P1 |
| E-3 | Simulasi settlement (quote) sebelum eksekusi | P1 |

### 4.6 Modul Ledger
| ID | Requirement | Prioritas |
|---|---|---|
| L-1 | Semua mutasi uang tercatat sebagai jurnal double-entry; total debit = kredit per jurnal | P0 |
| L-2 | Ledger tak bisa dihapus/diubah — koreksi via jurnal pembalik (reversal) | P0 |
| L-3 | Rekonsiliasi payment-vs-cash, allocation-vs-payment, dan receivable-vs-installment (consistency check job) | P1 |
| L-4 | Chart of accounts sederhana (minimal: KAS, PIUTANG_POKOK, PIUTANG_BUNGA, PIUTANG_DENDA, PENDAPATAN_BUNGA, PENDAPATAN_DENDA) | P0 |

### 4.7 Modul Write-off
| ID | Requirement | Prioritas |
|---|---|---|
| W-1 | Catat keputusan write-off yang sudah disetujui di luar sistem → Contract TERMINATED, installment tersisa WRITTEN_OFF, + jurnal penghapusan piutang | P1 |

Write-off hanya mencatat keputusan bisnis yang sudah disetujui; workflow collection/approval penuh tetap di luar scope.

### 4.8 Modul Reporting
| ID | Requirement | Prioritas |
|---|---|---|
| R-1 | Dashboard: outstanding, AR, NPL %, collection rate dengan definisi matematis tertulis di spec | P1 |
| R-2 | Laporan aging per periode | P1 |
| R-3 | Laporan penerimaan (cash-in per periode, per channel) | P2 |
| R-4 | Export CSV | P2 |

---

## 5. Skenario Bisnis Kritis (harus ditangani benar)

1. **Angsuran normal tepat waktu** — alokasi penuh, installment PAID.
2. **Telat 10 hari** — denda 10 hari terhitung; bayar → denda duluan baru sisanya ke bunga/pokok.
3. **Bayar sebagian** — installment PARTIALLY_PAID, sisa tetap tertagih.
4. **Bayar lebih** — excess jadi prepayment, tidak otomatis lunasi angsuran berikutnya (kebijakan konfigurabel).
5. **Pelunasan dipercepat bulan ke-6 dari 11** — quote benar, kontrak CLOSED, ledger balance.
6. **Pembayaran dobel (retry)** — idempotency key menolak duplikat, saldo tidak dobel.
7. **Void pembayaran** — jurnal pembalik, status installment & denda menyesuaikan ulang.
8. **Tanggal jatuh tempo 31 di bulan pendek** — due date jatuh ke tanggal terakhir bulan tsb.
9. **Bulan Februari, leap year** — due date tetap valid.
10. **Write-off approved** — contract menjadi TERMINATED, sisa piutang dipindahkan ke biaya penghapusan via jurnal.
11. **Credit partial application** — satu excess credit dapat diaplikasikan sebagian ke beberapa installment, tanpa menghapus histori.
12. **Settlement quote stale** — execution ditolak bila quote expired atau contract version berubah.
13. **Settlement closes installments** — sisa kewajiban menjadi `SETTLED`, bukan `PAID`.
14. **Write-off closes installments** — sisa kewajiban menjadi `WRITTEN_OFF`, bukan `PAID`.

---

## 5A. Definisi Reporting

- **Outstanding:** principal residual + recognized interest residual + effective penalty residual setelah mempertimbangkan payment, settlement, dan write-off. Future scheduled interest yang belum recognized tidak termasuk.
- **AR:** jumlah outstanding dari installment yang sudah jatuh tempo pada as-of date.
- **NPL %:** outstanding dari kontrak dengan DPD > 90 / total active outstanding pada as-of date.
- **Collection rate:** cash dari regular payment yang diaplikasikan ke installment yang jatuh tempo pada periode / recognized receivable installment yang jatuh tempo pada periode. Settlement tidak masuk numerator/denominator metric ini; tampilkan settlement cash sebagai metric terpisah.

## 5B. Settlement Quote Validity

Quote membawa `quote_id`, `quoted_at`, `valid_until`, snapshot `contract.version`, dan semua component snapshot. Execution wajib mengirim `quote_id`; server memvalidasi expiry, state/version, lalu **recalculate current settlement components**. Bila snapshot berbeda (payment, penalty, credit, config, atau component lain berubah), server menolak dengan `STALE_SETTLEMENT_QUOTE` dan client harus membuat quote baru. Untuk settlement, seluruh `AVAILABLE` credit harus dikonsumsi oleh settlement; bila credit lebih besar dari gross settlement, settlement ditolak dan refund berada di luar MVP. Maturity close karena final regular payment boleh meninggalkan AVAILABLE credit sebagai liability terpisah.

## 5C. Ledger Recognition Timing

- Bunga installment di-recognize sebagai receivable saat installment mencapai due date melalui billing/recognition step; settlement current-period dapat menambahkan accrued interest yang belum billed.
- Denda di-recognize incrementally oleh penalty job setelah grace period.
- Bunga berjalan settlement yang belum pernah billed di-recognize pada saat settlement sebelum cash collection.
- Future scheduled interest yang belum recognized tidak menjadi receivable.

## 6. Roadmap

| Fase | Isi | Exit criteria |
|---|---|---|
| Fase 1 (MVP) | Domain model, kontrak, jadwal FLAT+EFFECTIVE, pembayaran + alokasi, ledger, denda + aging job | Skenario 1–4 & 8–9 lolos test; demo operasional berjalan end-to-end |
| Fase 2 | Settlement, contract credit, void/reversal, waive denda, consistency check, write-off, auth + RBAC | Skenario 5–7 & 10–14 lolos; exception rekonsiliasi bisa di-resolve |
| Fase 3 | UI dashboard Next.js, reporting lengkap, outbox event/publisher, export | Portofolio demo-ready; dashboard & report definitions terdokumentasi |
| Fase 4 (opsional) | Restrukturisasi, gateway stub, maker-checker penuh, split microservice demonstrasi | — |

## 7. Ukuran Kesuksesan (portofolio)
- Engine perhitungan tercover unit test > 90% (flat, efektif, denda, alokasi, edge date).
- Reconciliation payment, allocation, dan receivable tidak memiliki exception untuk data normal.
- Settlement dan write-off mempunyai transaction record + allocation/closure audit yang dapat ditelusuri dari kontrak ke journal.
- Demo end-to-end 5 menit: buat/aktivasi kontrak → pembayaran normal/telat/partial/excess → settlement atau void → lihat statement, aging, outstanding, dan ledger.
- README menjelaskan arsitektur, keputusan desain, dan cara menjalankan (docker compose).
