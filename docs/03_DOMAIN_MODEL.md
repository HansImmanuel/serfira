# Domain Model Spec — Multifinance Loan Servicing Core System

Versi 0.3 (final pre-code review). Notasi: `field: type (constraint)`. Semua tabel punya `id UUID PK`,
`created_at`, `created_by`, `updated_at`, `updated_by` kecuali dinyatakan lain.

---

## 1. Entity & Relationship

```
Customer 1 ─── n Contract n ─── 1 Asset
Contract 1 ─── n Installment
Contract 1 ─── n Payment
Payment  1 ─── n PaymentAllocation
Installment 1 ─── n PenaltyAccrual
Installment 1 ─── n PenaltyAdjustment
Contract 1 ─── n SettlementQuote
Contract 1 ─── n Settlement
Settlement 1 ─── n SettlementAllocation
Contract 1 ─── n ContractCredit
ContractCredit 1 ─── n ContractCreditApplication
Settlement 1 ─── n SettlementCreditApplication
JournalEntry 1 ─── n JournalLine
```

### 1.1 Customer
| Field | Type | Keterangan |
|---|---|---|
| full_name | VARCHAR(120) | |
| nik | VARCHAR(16) | encrypted at rest; plaintext tidak dipakai untuk uniqueness |
| nik_hash | CHAR(64) | HMAC-SHA-256 normalized NIK using application secret, unique, index |
| phone | VARCHAR(20) | |
| address | TEXT | |

### 1.2 Asset (barang yang dibiayai)
| Field | Type | Keterangan |
|---|---|---|
| asset_type | ENUM(MOTORCYCLE, CAR, ELECTRONICS, OTHER) | |
| brand, model | VARCHAR(80) | |
| serial_no | VARCHAR(40) NULL | unique when present |
| plate_no | VARCHAR(20) NULL | unique when present |

### 1.3 Contract
| Field | Type | Keterangan |
|---|---|---|
| contract_no | VARCHAR(30) | unique, generated `MF-YYYYMM-XXXX` |
| customer_id | FK Customer | |
| asset_id | FK Asset | |
| asset_price | NUMERIC(19,2) | nilai transaksi aset, di-snapshot di kontrak |
| principal | NUMERIC(19,2) | plafon setelah DP |
| down_payment | NUMERIC(19,2) | default 0 |
| tenor_months | INT | > 0 |
| interest_scheme | ENUM(FLAT, EFFECTIVE) | |
| interest_rate | NUMERIC(7,4) | rate per bulan, fraction desimal (1.5% = 0.0150) |
| grace_period_days | INT | snapshot config saat aktivasi |
| penalty_rate_daily | NUMERIC(7,4) | snapshot config saat aktivasi, fraction desimal |
| start_date | DATE | tanggal aktivasi |
| status | ENUM(DRAFT, ACTIVE, CLOSED, TERMINATED) | |
| write_off_reason | TEXT NULL | diisi saat write-off |
| write_off_recorded_by | FK app_user NULL | actor internal yang mencatat keputusan external write-off |
| closed_at | TIMESTAMP NULL | diisi saat kontrak CLOSED |
| closed_reason | ENUM(MATURITY, SETTLEMENT) NULL | alasan penutupan kontrak |
| version | BIGINT | optimistic locking |

**Invariant:**
- `asset_price = principal + down_payment`; `asset_price > 0`, `principal > 0`, `0 <= down_payment < asset_price`.
- Interest/penalty rates `>= 0`, `tenor_months > 0`, `grace_period_days >= 0`.
- Hanya boleh generate jadwal sekali; activation idempotent.
- `DRAFT → ACTIVE → CLOSED/TERMINATED`; setelah ACTIVE tidak boleh kembali DRAFT.
- `ACTIVE → CLOSED` dapat terjadi karena `MATURITY` (seluruh installment resolved) atau `SETTLEMENT`; `ACTIVE → TERMINATED` hanya melalui write-off.
- Saat maturity, contract boleh CLOSED walaupun masih ada `AVAILABLE` contract credit; credit menjadi liability terpisah dan refund flow berada di luar MVP.

### 1.4 Installment
| Field | Type | Keterangan |
|---|---|---|
| contract_id | FK Contract | index `(contract_id, due_date)` |
| period_no | INT | 1..tenor, unique per kontrak |
| due_date | DATE | calendar month + month-end clamp |
| principal_amount | NUMERIC(19,2) | |
| interest_amount | NUMERIC(19,2) | scheduled interest for the period |
| recognized_interest_amount | NUMERIC(19,2) | interest already recognized as receivable; 0 until billing, may include settlement accrued interest |
| penalty_amount | NUMERIC(19,2) | gross cumulative penalty recognized |
| paid_amount | NUMERIC(19,2) | active regular-payment allocation |
| settled_amount | NUMERIC(19,2) | settlement allocation, default 0 |
| written_off_amount | NUMERIC(19,2) | residual written off, default 0 |
| status | ENUM(PENDING, PARTIALLY_PAID, PAID, OVERDUE, SETTLED, WRITTEN_OFF) | |
| paid_at | TIMESTAMP NULL | |
| settled_at | TIMESTAMP NULL | |
| written_off_at | TIMESTAMP NULL | |

Derived:
- `effective_penalty = penalty_amount − active paid penalty allocations − penalty adjustments`
- `recognized_total = principal_amount + recognized_interest_amount + effective_penalty`
- `resolved_amount = paid_amount + settled_amount + written_off_amount`
- `outstanding = recognized_total − resolved_amount`
- Future `interest_amount − recognized_interest_amount` is schedule-only and is never a ledger receivable until recognition.
- For an in-period settlement, accrued interest is first added to `recognized_interest_amount`, then included in settlement.

State transition:
```
PENDING ── partial ──▶ PARTIALLY_PAID ── full payment ──▶ PAID
PENDING/PARTIALLY_PAID ── overdue job ──▶ OVERDUE
PENDING/PARTIALLY_PAID/OVERDUE ── settlement ──▶ SETTLED
PENDING/PARTIALLY_PAID/OVERDUE ── write-off ──▶ WRITTEN_OFF
OVERDUE ── full regular pay ──▶ PAID
```

Weekend/public holiday tidak menggeser due date pada MVP.

### 1.5 SettlementQuote
| Field | Type | Keterangan |
|---|---|---|
| quote_no | VARCHAR(40) | unique, generated `Q-YYYYMMDD-XXXX` |
| contract_id | FK Contract | ACTIVE only |
| quoted_at | TIMESTAMP | |
| valid_until | TIMESTAMP | MVP TTL, mis. 15 menit |
| contract_version | BIGINT | stale detection snapshot |
| outstanding_principal | NUMERIC(19,2) | snapshot |
| unpaid_billed_interest | NUMERIC(19,2) | snapshot |
| accrued_interest | NUMERIC(19,2) | snapshot |
| penalty_outstanding | NUMERIC(19,2) | snapshot |
| rebate_amount | NUMERIC(19,2) | snapshot |
| admin_fee | NUMERIC(19,2) | snapshot |
| available_credit | NUMERIC(19,2) | snapshot |
| credit_used | NUMERIC(19,2) | explicit requested netting |
| gross_amount | NUMERIC(19,2) | before credit |
| cash_due | NUMERIC(19,2) | after credit |
| status | ENUM(QUOTED, EXECUTED, EXPIRED) | |

Quote component snapshot bersifat immutable; `status` boleh berubah `QUOTED → EXECUTED/EXPIRED`. Execution wajib memvalidasi expiry, contract ACTIVE, dan `contract_version` serta seluruh component snapshot.

### 1.6 Settlement
| Field | Type | Keterangan |
|---|---|---|
| settlement_no | VARCHAR(40) | unique, generated `SET-YYYYMM-XXXX` |
| contract_id | FK Contract | |
| quote_id | FK SettlementQuote | |
| cash_received | NUMERIC(19,2) | aktual cash |
| credit_used | NUMERIC(19,2) | liability yang dinet |
| rebate_amount | NUMERIC(19,2) | |
| admin_fee | NUMERIC(19,2) | |
| executed_at | TIMESTAMP | |
| idempotency_key | VARCHAR(80) | endpoint-scoped |

`settlement_allocation`: `settlement_id`, `installment_id`, `allocation_type(PENALTY|INTEREST|PRINCIPAL)`, `amount`. Allocation immutable.

`settlement_credit_application`: `settlement_id`, `contract_credit_id`, `amount`, `created_at`, `created_by`. Satu settlement dapat mengonsumsi beberapa source credit; total consume tidak boleh melebihi available credit.

### 1.7 Payment
| Field | Type | Keterangan |
|---|---|---|
| payment_no | VARCHAR(30) | unique, generated `PAY-YYYYMM-XXXX` |
| contract_id | FK Contract | index |
| amount | NUMERIC(19,2) | > 0 |
| channel | ENUM(CASH, BANK_TRANSFER, VA_STUB, EWALLET_STUB) | |
| paid_at | TIMESTAMP | |
| status | ENUM(POSTED, VOIDED) | void = reversal, bukan delete |
| voided_at | TIMESTAMP NULL | |
| void_reason | TEXT NULL | |
| idempotency_key | VARCHAR(80) | unique bersama endpoint scope |

### 1.8 PaymentAllocation
| Field | Type | Keterangan |
|---|---|---|
| payment_id | FK Payment | |
| installment_id | FK Installment NULL | NULL hanya untuk EXCESS |
| allocation_type | ENUM(PENALTY, INTEREST, PRINCIPAL, EXCESS) | urutan PENALTY→INTEREST→PRINCIPAL |
| amount | NUMERIC(19,2) | > 0 |

Allocation row bersifat append-only. Setelah payment VOIDED, allocation lama tetap menjadi histori dan tidak lagi dihitung sebagai active allocation.

### 1.9 PenaltyAccrual
| Field | Type | Keterangan |
|---|---|---|
| installment_id | FK Installment | |
| accrual_date | DATE | unique `(installment_id, accrual_date)` |
| days_late | INT | |
| amount | NUMERIC(19,2) | > 0; incremental daily accrual, bukan cumulative snapshot |
| version | BIGINT | |

### 1.10 PenaltyAdjustment
| Field | Type | Keterangan |
|---|---|---|
| installment_id | FK Installment | |
| adjustment_type | ENUM(WAIVE, REDUCE) | |
| amount | NUMERIC(19,2) | > 0 |
| reason | TEXT | wajib |
| approved_by | FK app_user | JWT principal |

Adjustment immutable; koreksi dibuat sebagai adjustment baru, bukan UPDATE histori.

### 1.11 ContractCredit / ContractCreditApplication
`contract_credit` menyimpan source excess amount yang immutable.

| Field | Type | Keterangan |
|---|---|---|
| contract_id | FK Contract | index |
| source_payment_allocation_id | FK PaymentAllocation | harus `EXCESS` |
| amount | NUMERIC(19,2) | > 0, immutable |
| status | ENUM(AVAILABLE, APPLIED, REFUNDED) | `APPLIED` hanya bila saldo 0 |

`contract_credit_application`:
- `credit_id` FK
- `installment_id` FK
- `amount` NUMERIC(19,2) > 0
- `applied_at` TIMESTAMP
- `created_by` FK app_user

Available credit = `amount − Σ application.amount`. Pada MVP, `REFUNDED` hanya berarti seluruh credit direfund sehingga saldo menjadi 0; partial refund ditunda. Satu credit dapat diaplikasikan sebagian ke banyak installment. Credit application regular hanya boleh mengurangi **recognized receivable** pada installment (future unrecognized interest tidak boleh dicatat sebagai PIUTANG_BUNGA sebelum billing).

### 1.12 Accounts (chart of accounts)
```
KAS (ASSET)
PIUTANG_POKOK (ASSET)
PIUTANG_BUNGA (ASSET)
PIUTANG_DENDA (ASSET)
TITIPAN_NASABAH (LIABILITY)
PENDAPATAN_BUNGA (INCOME)
PENDAPATAN_DENDA (INCOME)
DISKON_PELUNASAN (EXPENSE)
PENDAPATAN_ADMIN (INCOME)
BIAYA_PENGHAPUSAN_PIUTANG (EXPENSE)
```

### 1.13 JournalEntry / JournalLine
`JournalEntry` mempunyai `reversal_of_id` (self FK, nullable). `JournalEntry` dan `JournalLine` immutable: tidak boleh UPDATE/DELETE; koreksi memakai journal reversal baru.

### 1.14 Business Number Counter
`document_number_counter`: `id UUID PK`, `counter_key UNIQUE`, `last_value INT`, plus audit fields. Counter di-increment secara transactional dengan row lock; numbering tidak dijamin gapless setelah rollback. Format business numbers dijelaskan di Tech Spec.

### 1.15 Auth & Operational Support
`app_user`:
`id, username UNIQUE, password_hash, full_name, role, is_active, failed_login_attempts, locked_until, last_login_at`.

`role = ADMIN_OPERASIONAL | FINANCE | MANAJEMEN | SYSTEM`.

`refresh_token`: `id, user_id, token_hash UNIQUE, expires_at, revoked_at, replaced_by_id NULL`.

`idempotency_keys`: `key, endpoint, request_hash, response_json, status, created_at, expires_at, request_id`.

`job_run`: `job_name, started_at, finished_at, records_processed, records_failed, status`.

`reconciliation_exception`: `contract_id, check_date, check_type, expected_amount, actual_amount, diff_amount, status, resolved_note, resolved_by, resolved_at`.

`outbox_events`: `aggregate_type, aggregate_id, event_type, payload_json, status, created_at, sent_at, retry_count`.

## 2. API Surface (ringkasan)

| Method | Path | Keterangan |
|---|---|---|
| POST | /api/v1/auth/login | login |
| POST | /api/v1/auth/refresh | rotate refresh token |
| POST | /api/v1/auth/logout | revoke refresh token |
| POST | /api/v1/contracts | buat kontrak (DRAFT) |
| PUT | /api/v1/contracts/{id} | update contract hanya DRAFT |
| POST | /api/v1/contracts/{id}/activate | generate jadwal → ACTIVE |
| GET | /api/v1/contracts/{id} | detail + outstanding |
| GET | /api/v1/contracts/{id}/installments | jadwal angsuran |
| POST | /api/v1/payments | terima pembayaran (Idempotency-Key wajib) |
| POST | /api/v1/payments/{id}/void | reversal |
| GET | /api/v1/contracts/{id}/credit | saldo & histori credit |
| POST | /api/v1/contracts/{id}/credit/apply | aplikasikan credit |
| GET | /api/v1/contracts/{id}/statement | rekening koran kontrak |
| POST | /api/v1/settlements/quote | simulasi pelunasan |
| POST | /api/v1/settlements | eksekusi pelunasan |
| POST | /api/v1/contracts/{id}/write-off | write-off |
| GET | /api/v1/reports/aging | laporan aging |
| GET | /api/v1/reports/outstanding | outstanding portofolio |
| GET | /api/v1/reports/reconciliation-exceptions | exception rekonsiliasi |
| POST | /api/v1/reports/reconciliation-exceptions/{id}/resolve | resolve exception |

## 3. Invariant Global (wajib selalu benar — di-test)

1. Σ debit = Σ kredit untuk setiap `journal_entry`.
2. `asset_price = principal + down_payment`.
3. `resolved_amount` per installment tidak pernah melebihi `recognized_total`.
4. Satu kontrak hanya punya satu jadwal (unik per `(contract_id, period_no)`).
5. Settlement hanya sah pada kontrak ACTIVE, quote masih valid, current component recalculation cocok dengan quote snapshot, dan credit yang dipakai tercatat eksplisit. Setelah settlement, residual recognized receivable pada installment menjadi `SETTLED`; future unrecognized interest is not receivable and is not charged.
6. Setiap Payment POSTED punya ≥ 1 PaymentAllocation dan total alokasi = payment amount; allocation EXCESS tidak menaikkan `installment.paid_amount`. Jika payment memiliki EXCESS, credit source mengikuti lifecycle void policy.
7. Allocation hanya dihitung sebagai aktif bila payment berstatus POSTED; void menggunakan reversal journal dan tidak menghapus histori allocation.
8. Satu `PenaltyAccrual` per `(installment_id, accrual_date)` dan `amount` adalah incremental daily accrual.
9. Effective penalty outstanding = penalty_amount − paid penalty allocations − penalty adjustments; tidak boleh negatif.
10. `recognized_interest_amount <= interest_amount` pada installment aktif.
11. Credit application total untuk satu credit tidak boleh melebihi amount credit.
12. JournalEntry/JournalLine tidak boleh UPDATE/DELETE; koreksi memakai reversal journal.
13. Reconciliation normal: payment-vs-cash, allocation-vs-payment, dan receivable-vs-installment harus balance; receivable hanya principal + recognized interest + effective penalty, bukan future unrecognized interest.
14. Settlement memiliki quote snapshot + settlement record + immutable settlement allocations + settlement-credit applications bila credit digunakan.
15. Write-off mengubah residual recognized receivable menjadi `WRITTEN_OFF` dan journal menurunkan receivable sesuai written-off amount.
16. Payment yang source EXCESS credit-nya sudah diaplikasikan/refunded tidak boleh di-void pada MVP; void tersebut memerlukan flow reversal credit yang belum menjadi scope.
17. Bila payment/credit application menyelesaikan seluruh installment suatu kontrak dan tidak ada unsettled installment lain, contract dapat auto-close dengan `closed_reason=MATURITY`; settlement mempunyai `closed_reason=SETTLEMENT`.

## 4. Contoh Skenario Data (untuk test & demo)

Kontrak: plafon 10.000.000, DP 2.000.000, principal 8.000.000, tenor 11,
rate flat 1.5%/bulan (`0.0150`), start 2026-01-31.
- bungaTotal = 8jt × 1.5% × 11 = 1.320.000
- angsuran = (8jt/11) + (1.320.000/11) ≈ 727.272,73 + 120.000 = 847.272,73 (pembulatan ke installment terakhir)
- Due date bulan 2 = 2026-02-28 (bukan 31), dst. Installment terakhir menyerap selisih pembulatan.

Test ini jadi "golden test": jadwal yang dihasilkan sistem harus persis sama dengan angka
yang dihitung manual/spreadsheet, dan tercatat di test sebagai expected value.
