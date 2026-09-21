# Technical Specification — Multifinance Loan Servicing Core System

- Versi: 0.3 (reconciled + final pre-code review)
- Stack: Java 21, Spring Boot 4.1.1, PostgreSQL 16, Flyway, Gradle, Testcontainers, Kafka (opsional Fase 3), Next.js 14 (frontend)

---

## 1. Arsitektur: Modular Monolith

Satu aplikasi Spring Boot, satu database, module dipisah berdasarkan bounded context.
Module berkomunikasi via interface (application service) milik module lain — **tidak boleh**
satu module query tabel domain module lain secara langsung.

```
com.serfira
├── contract/     → Contract, Customer, Asset, activation, jadwal
├── payment/      → Payment, alokasi, idempotency, channel
├── penalty/      → Denda, aging job
├── settlement/   → Quote & eksekusi pelunasan
├── ledger/       → Journal, JournalLine, chart of accounts, posting
├── reporting/    → Read model / query service
└── shared/       → Money, AuditFields, exception, config
```

Aturan dependency:
- `payment`, `penalty`, `settlement` boleh bergantung ke `ledger` (posting jurnal).
- `contract` juga boleh bergantung ke `ledger` (ADR-008): aktivasi mem-posting jurnal disbursement di transaksi
  yang sama, sehingga piutang yang dibuat jadwal langsung tercatat sebagai receivable. Edge ini satu arah —
  `ledger` tetap tidak tahu modul lain.
- `ledger` tidak boleh bergantung ke module lain (paling dasar).
- `reporting` boleh baca semua (read-only).

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

## 2. Konvensi Teknis

### 2.1 Uang
- Gunakan `BigDecimal` dengan skala eksplisit 2. **Dilarang float/double.**
- DB: `NUMERIC(19,2)` untuk amount; rate: `NUMERIC(7,4)`. Semua rate memakai fraction desimal: 1.5% disimpan sebagai `0.0150`.
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
- Job (denda harian, aging) pakai shedlock agar aman multi-instance.

### 2.4 Audit
- Semua tabel punya `created_at`, `created_by`, `updated_at`, `updated_by` (envers dibolehkan, tapi
  ledger cukup dengan jurnal pembalik — jangan pernah UPDATE/DELETE journal_lines).

### 2.5 Idempotency
- Tabel `idempotency_keys(key, endpoint, request_hash, response_json, status, created_at, expires_at, request_id)`.
- `request_hash` = digest keyed (HMAC-SHA-256 atas canonical JSON request + endpoint scope). Dokumen ini semula menyebut "SHA-256"; karena payload dapat memuat PII, implementasi B5 memakai HMAC keyed dari ADR-004 (ADR-007) — semantik kesamaan identik dan nilainya tidak reversible.
- Request sama + key sama → return response tersimpan tanpa eksekusi ulang.
- Request beda + key sama → 409 CONFLICT.
- Key expired hanya boleh dibersihkan setelah retention policy; cleanup tidak boleh membuat retry lama diam-diam mengeksekusi transaksi baru.
- **Implementasi B5 (ADR-007):** claim dilakukan dengan `INSERT … ON CONFLICT (endpoint, key) DO NOTHING` di dalam transaksi bisnis (`Propagation.MANDATORY`), `status` bernilai `COMPLETED` setelah respons tersimpan, `expires_at` = waktu claim + `IDEMPOTENCY_KEY_RETENTION_DAYS` (config), dan mekanisme hidup di `com.serfira.shared.idempotency` supaya C3 (payment/settlement/credit application) memakainya ulang. Cleanup baris kedaluwarsa belum diimplementasikan (menyusul C3).

### 2.6 Authentication & Session
- Password hash: Argon2id. Jangan simpan password plaintext.
- Access token JWT short-lived (15 menit).
- Refresh token random, disimpan hashed di DB, **rotated on use**; token lama langsung `revoked_at` diisi.
- Brute-force lockout: counter pada `app_user`, threshold dan lock window dikonfigurasi di application layer.
- Browser refresh token: `HttpOnly`, `Secure`, `SameSite=Lax` (atau `Strict` bila deployment memungkinkan).
- Access token disimpan di memory oleh frontend, bukan localStorage.
- Refresh cookie menggunakan `HttpOnly`, `Secure`, dan `SameSite=Lax` (atau `Strict` bila deployment memungkinkan); state-changing API tetap membutuhkan Authorization header sehingga CSRF exposure diminimalkan.
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
| Waive/reduce denda | PENDAPATAN_DENDA | PIUTANG_DENDA |
| Settlement - recognize accrued current interest | PIUTANG_BUNGA | PENDAPATAN_BUNGA |
| Settlement - cash for principal | KAS | PIUTANG_POKOK |
| Settlement - cash for billed/accrued interest | KAS | PIUTANG_BUNGA |
| Settlement - cash for penalty | KAS | PIUTANG_DENDA |
| Settlement - rebate | DISKON_PELUNASAN | PIUTANG_BUNGA |
| Settlement - admin fee | KAS | PENDAPATAN_ADMIN |
| Settlement - consume available credit | TITIPAN_NASABAH | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |
| Excess payment diterima | KAS | TITIPAN_NASABAH |
| Credit diaplikasikan ke installment | TITIPAN_NASABAH | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |
| Write-off piutang | BIAYA_PENGHAPUSAN_PIUTANG | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |

Settlement wajib membuat transaction record + allocation + satu atau beberapa journal entry yang seluruhnya balance. `rebate` diposting sebagai contra-receivable terhadap eligible interest, bukan sebagai penghapusan future interest yang belum billed. Admin fee diakui ke `PENDAPATAN_ADMIN`.

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
Denda dihitung job harian dan **diakui incremental** sebagai `PenaltyAccrual.amount` per tanggal. `PenaltyAccrual.amount` adalah delta hari itu, **bukan cumulative snapshot**, sehingga tidak double-count saat dijumlah. Job mem-posting delta ke ledger dan update gross `penalty_amount`. Recalc setelah void hanya menambah catch-up accrual bila expected recognized penalty lebih besar dari nilai yang sudah diakui.

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
