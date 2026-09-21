# Addendum — Gap Analysis Follow-up

Versi 0.3 (final pre-code review). Dokumen ini melengkapi `01_PRD.md`, `02_TECH_SPEC.md`, `03_DOMAIN_MODEL.md`
dengan bagian-bagian yang teridentifikasi kurang. Notasi mengikuti domain model
(`field: type (constraint)`), semua tabel baru tetap punya `id UUID PK`,
`created_at`, `created_by`, `updated_at`, `updated_by` kecuali dinyatakan lain.

---

## 1. Config / System Parameter

**Masalah:** `grace_period`, `penalty rate harian`, `admin_fee`, `rebate` disebut
"konfigurasi" tanpa tempat penyimpanan. Tanpa ini nilainya hardcode dan tidak auditable.

**Keputusan:** parameter yang **berlaku per kontrak** disimpan langsung di `Contract`
(karena bisa beda per skema pembiayaan), parameter yang **berlaku global** disimpan
di tabel `system_parameter`.

### 1.1 Tambahan field di Contract
| Field | Type | Keterangan |
|---|---|---|
| grace_period_days | INT | default dari system_parameter saat kontrak dibuat, di-snapshot (bukan reference live) |
| penalty_rate_daily | NUMERIC(7,4) | idem, di-snapshot saat aktivasi |

Alasan snapshot, bukan live-reference: kalau rate global berubah, kontrak yang sudah
berjalan tidak boleh ikut berubah retroaktif — ini juga jadi bagian dari audit trail
kontrak (nilai apa yang berlaku saat kontrak itu hidup).

**Klarifikasi B5 (ADR-006).** Kedua kolom ini `NOT NULL`, jadi baris DRAFT *wajib* membawa nilai:
kontrak draft menyimpan snapshot **provisional** dari config yang berlaku saat drafting, dan
**aktivasi mengambil snapshot ulang** pada tanggal aktivasi. Yang mengikat kontrak hidup adalah
DM §1.3 ("snapshot config saat aktivasi"); selama DRAFT belum ada jadwal maupun denda, sehingga
penulisan ulang ini tidak retroaktif. Snapshot mana yang berlaku diuji di
`ContractActivationIT.activationSnapshotsTheConfigurationInForceAtActivationNotAtDrafting`.

### 1.2 Tabel baru: `system_parameter`
| Field | Type | Keterangan |
|---|---|---|
| param_key | VARCHAR(60) | unique, mis. `DEFAULT_GRACE_PERIOD_DAYS`, `DEFAULT_PENALTY_RATE_DAILY`, `SETTLEMENT_ADMIN_FEE`, `SETTLEMENT_REBATE_RATE` |
| param_value | VARCHAR(200) | disimpan sebagai string, di-parse sesuai tipe di service layer |
| effective_date | DATE | berlaku mulai tanggal ini |
| description | TEXT | |

Query selalu ambil baris dengan `effective_date <= today` terbaru — jadi perubahan
config juga auditable dan tidak menimpa histori (append-only, tidak UPDATE).

### 1.3 Seed values `system_parameter` (wajib ada di Flyway baseline)

Seed ini **harus dimasukkan di migration yang sama dengan pembuatan tabel** (`V1__baseline.sql`) agar kontrak pertama dapat dibuat tanpa error konfigurasi:

| param_key | param_value | Keterangan |
|---|---|---|
| `DEFAULT_GRACE_PERIOD_DAYS` | `3` | 3 hari grace period sebelum denda mulai dihitung |
| `DEFAULT_PENALTY_RATE_DAILY` | `0.0010` | 0.1%/hari dari saldo tagihan tertunggak |
| `SETTLEMENT_ADMIN_FEE` | `150000` | Rp 150.000 flat per settlement |
| `SETTLEMENT_REBATE_RATE` | `0.5000` | 50% dari eligible interest dikembalikan sebagai rebate |
| `SETTLEMENT_QUOTE_TTL_MINUTES` | `15` | Masa berlaku quote settlement (menit) |
| `IDEMPOTENCY_KEY_RETENTION_DAYS` | `7` | Retensi idempotency key sebelum boleh di-cleanup |

> **Catatan:** Nilai di atas adalah default portofolio — bukan production value. Ubah sebelum demo bila ingin skenario yang lebih realistis. Semua parameter dapat diubah via direct DB insert (append baris baru dengan `effective_date` baru); tidak perlu endpoint admin untuk MVP.

### 1.4 Business-facing number generator
Gunakan tabel `document_number_counter` untuk counter transactional dan concurrency-safe:

| Field | Type | Keterangan |
|---|---|---|
| id | UUID PK | |
| counter_key | VARCHAR(80) | unique, contoh `CONTRACT_202609` |
| last_value | INT | counter terakhir |
| created_at / created_by | audit | |
| updated_at / updated_by | audit | |

Format nomor MVP: `MF-YYYYMM-XXXX` (contract), `PAY-YYYYMM-XXXX` (payment),
`Q-YYYYMMDD-XXXX` (quote), `SET-YYYYMM-XXXX` (settlement). Gap angka akibat rollback
diperbolehkan; uniqueness dan concurrency safety lebih penting daripada gapless numbering.

---

## 2. ContractCredit (excess / prepayment)

**Masalah:** `PaymentAllocation.EXCESS` mereferensikan `contract_credit` tapi tabel
ini belum pernah didefinisikan, dan tidak ada akun ledger untuk menampungnya.

### 2.1 Tabel baru: `contract_credit`
| Field | Type | Keterangan |
|---|---|---|
| contract_id | FK Contract | index |
| source_payment_allocation_id | FK PaymentAllocation | asal credit; allocation_type=EXCESS |
| amount | NUMERIC(19,2) | > 0, immutable |
| status | ENUM(AVAILABLE, APPLIED, REFUNDED) | APPLIED hanya saat saldo 0 |

Saldo available = `amount − Σ applications`. Pada MVP, refund hanya full refund; partial refund ditunda. Tidak boleh ada UPDATE amount untuk mengubah histori.

### 2.2 Tabel baru: `contract_credit_application`
| Field | Type | Keterangan |
|---|---|---|
| credit_id | FK contract_credit | |
| installment_id | FK Installment | |
| amount | NUMERIC(19,2) | > 0 |
| applied_at | TIMESTAMP | |
| created_by | FK app_user | JWT principal |

Satu credit dapat punya banyak application. Ini memungkinkan partial application ke beberapa installment tanpa menghilangkan audit trail. MVP hanya mengizinkan application terhadap **recognized receivable**; future unrecognized interest tetap berada di schedule dan belum boleh dikreditkan ke `PIUTANG_BUNGA`.

### 2.3 Tambahan Chart of Accounts (§1.8 domain model)
```
TITIPAN_NASABAH   (LIABILITY) — saldo kredit/kelebihan bayar milik nasabah
```

### 2.4 Tambahan posting rule (§3 tech spec, tabel "Posting rules")
| Event | Debit | Kredit |
|---|---|---|
| Excess payment diterima | KAS | TITIPAN_NASABAH |
| Kredit diaplikasikan ke installment berikutnya | TITIPAN_NASABAH | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA |
| Refund kredit ke nasabah (Fase lanjut, opsional) | TITIPAN_NASABAH | KAS |

Kebijakan default (sesuai PRD P-4): excess **tidak otomatis** mengaplikasi ke installment berikutnya — butuh action eksplisit (endpoint baru) atau job periodik kalau ke depannya mau otomatis. Untuk Fase 1–2 cukup manual. Bila installment berikutnya belum memiliki recognized receivable, credit tetap AVAILABLE sampai receivable tersebut diakui.

### 2.5 Endpoint baru
```
POST /api/v1/contracts/{id}/credit/apply    -- aplikasikan saldo kredit ke installment tertua yang memiliki recognized receivable
GET  /api/v1/contracts/{id}/credit          -- lihat saldo & histori kredit
```

---

## 3. Auth: User, Role, Session

**Masalah:** PRD §2 menyebut JWT + RBAC 4 role, tapi tidak ada entity maupun endpoint.

### 3.1 Tabel baru
```
app_user (id, username unique, password_hash, full_name, role, is_active, failed_login_attempts, locked_until, last_login_at)
refresh_token (id, user_id, token_hash unique, expires_at, revoked_at, replaced_by_id nullable)
```

`role: ENUM(ADMIN_OPERASIONAL, FINANCE, MANAJEMEN, SYSTEM)`.

### 3.2 Endpoint baru
```
POST /api/v1/auth/login     -- {username, password} → access + refresh token
POST /api/v1/auth/refresh   -- rotate refresh token → access + refresh token baru
POST /api/v1/auth/logout    -- revoke refresh token
```

### 3.3 Keputusan
- Access token JWT short-lived (15 menit), refresh token disimpan hashed di DB dan **di-rotate saat dipakai**. Token lama direvoke dan menunjuk `replaced_by_id`; jangan stateless-only untuk refresh token.
- `created_by` / `updated_by` di semua user-facing write diisi dari `app_user.id` hasil JWT claim, bukan string bebas. Job memakai principal `SYSTEM`.
- Bootstrap exception: seeded `SYSTEM` user dapat memiliki `created_by=NULL` / `updated_by=NULL` pada insert awal; write berikutnya tetap memakai UUID `app_user.id`.
- RBAC memakai default-deny.
- Endpoint job scheduler internal (billing, denda, aging, reconciliation) tidak lewat JWT user; jalankan in-process dengan seeded non-interactive user `SYSTEM`.

### 3.4 Endpoint-to-Role Matrix

Legenda: ✅ = diizinkan | ❌ = dilarang | — = tidak relevan

| Endpoint | ADMIN_OPERASIONAL | FINANCE | MANAJEMEN | SYSTEM (internal job) |
|---|---|---|---|---|
| `POST /auth/login` | ✅ | ✅ | ✅ | — |
| `POST /auth/refresh` | ✅ | ✅ | ✅ | — |
| `POST /auth/logout` | ✅ | ✅ | ✅ | — |
| `POST /contracts` | ✅ | ❌ | ❌ | — |
| `PUT /contracts/{id}` | ✅ | ❌ | ❌ | — |
| `POST /contracts/{id}/activate` | ✅ | ❌ | ❌ | — |
| `GET /contracts/{id}` | ✅ | ✅ | ✅ | — |
| `GET /contracts` (list) | ✅ | ✅ | ✅ | — |
| `GET /contracts/{id}/installments` | ✅ | ✅ | ✅ | — |
| `GET /contracts/{id}/statement` | ✅ | ✅ | ❌ | — |
| `POST /payments` | ✅ | ❌ | ❌ | — |
| `POST /payments/{id}/void` | ✅ | ❌ | ❌ | — |
| `GET /contracts/{id}/credit` | ✅ | ✅ | ❌ | — |
| `POST /contracts/{id}/credit/apply` | ✅ | ❌ | ❌ | — |
| `POST /settlements/quote` | ✅ | ❌ | ❌ | — |
| `POST /settlements` | ✅ | ❌ | ❌ | — |
| `POST /contracts/{id}/write-off` | ✅ | ❌ | ❌ | — |
| `POST /penalty-adjustments` (waive/reduce) | ✅ | ❌ | ❌ | — |
| `GET /reports/aging` | ✅ | ✅ | ✅ | — |
| `GET /reports/outstanding` | ✅ | ✅ | ✅ | — |
| `GET /reports/reconciliation-exceptions` | ✅ | ✅ | ❌ | — |
| `POST /reports/reconciliation-exceptions/{id}/resolve` | ❌ | ✅ | ❌ | — |

> **Catatan implementasi:** Gunakan Spring Security `@PreAuthorize` atau `SecurityFilterChain` dengan role-based matchers. Default-deny berarti setiap endpoint baru harus secara eksplisit mendaftarkan role yang diizinkan; endpoint tanpa deklarasi otomatis `403`. Response 403 menggunakan error envelope standar dengan `code: FORBIDDEN`.

---

## 4. TERMINATED / Write-off

**Masalah:** status `TERMINATED` ada di Contract tapi tidak ada trigger, endpoint,
maupun posting rule.

### 4.1 Keputusan scope
Write-off **penuh** (proses collection, keputusan bisnis kapan suatu piutang
dianggap tak tertagih) ada di luar scope servicing-only — ini dipindah eksplisit
ke Non-Goals PRD §1.3. Yang tetap di-scope Fase 1–2 adalah **kemampuan mencatat**
keputusan write-off yang sudah diputuskan di luar sistem (mis. oleh tim collection),
bukan proses pengambilan keputusannya.

### 4.2 Endpoint baru
```
POST /api/v1/contracts/{id}/write-off   -- {reason} → status TERMINATED
```
Precondition: kontrak `ACTIVE`, butuh field `write_off_reason`, `write_off_recorded_by`
di Contract (nullable, diisi hanya saat write-off).

### 4.3 Posting rule tambahan (§3 tech spec)
| Event | Debit | Kredit |
|---|---|---|
| Write-off (hapus buku piutang tersisa) | BIAYA_PENGHAPUSAN_PIUTANG | PIUTANG_POKOK / PIUTANG_BUNGA / PIUTANG_DENDA (sisa receivable outstanding) |

Tambahan akun COA: `BIAYA_PENGHAPUSAN_PIUTANG (EXPENSE)`.

Invariant #2 domain model ("kecuali kontrak TERMINATED/write-off") sekarang punya
definisi konkret: outstanding installment yang tersisa saat write-off dipindah ke
akun biaya lewat jurnal ini, sehingga Σ paid + Σ written-off = total tagihan tetap
balance.

---

## 5. Concurrency: retry strategy untuk optimistic locking

**Masalah:** `@Version` dipakai tapi tidak ada strategi saat `OptimisticLockException`
terjadi, terutama saat job denda harian dan pembayaran menyentuh `Installment` yang
sama nyaris bersamaan.

**Keputusan:**
- Write path pembayaran (user-facing, via API): **retry otomatis maksimal 3x** dengan
  backoff kecil (mis. 50ms, 150ms, 400ms) di service layer sebelum melempar error ke
  client. Kalau tetap gagal setelah 3x, return `409 CONFLICT` dengan kode
  `CONCURRENT_MODIFICATION` — user diminta refresh & retry manual.
- Job harian (denda, aging): retry per installment sampai maksimum 5 percobaan dalam window job. Setelah itu record ditandai gagal, di-log, dan diproses alert; kegagalan satu installment tidak menghentikan batch.
- Job memakai shedlock (sudah disebut di tech spec) untuk memastikan hanya satu
  instance yang jalan — ini mencegah job-vs-job race, tapi retry di atas tetap perlu
  untuk job-vs-payment race.

---

## 6. Void payment → penalty recalculation

**Masalah:** Skenario 7 PRD ("denda menyesuaikan ulang" setelah void) tidak punya
mekanisme jelas — nunggu job besok berarti ada window data salah.

**Keputusan:** void payment **memicu recalculation langsung** (synchronous, dalam
transaksi yang sama dengan void), bukan menunggu job harian berikutnya:

1. Void payment → payment menjadi VOIDED; allocation lama tetap histori dan tidak lagi active, `installment.paid_amount` dihitung ulang dari active allocations, status installment dihitung ulang (PENDING/PARTIALLY_PAID/OVERDUE).
2. Kalau installment yang terdampak sudah lewat due date + grace period, panggil ulang fungsi kalkulasi denda yang sama dipakai job harian. Recalc tidak mengubah histori accrual; bila expected gross penalty lebih besar dari recognized, buat catch-up `PenaltyAccrual` incremental untuk delta dan posting jurnal.
3. Job harian berikutnya tetap jalan seperti biasa dan idempotent terhadap hasil
   langkah 2 (karena `unique(installment_id, accrual_date)` di `PenaltyAccrual`); catch-up harus berupa delta incremental dan tidak mengubah histori accrual.

**Void + excess credit policy:** payment yang menghasilkan `contract_credit` hanya boleh di-void bila source credit masih `AVAILABLE` penuh dan belum pernah diaplikasikan/refunded. Dalam kondisi tersebut credit dibalik bersama payment/reversal journal. Bila credit sudah pernah dipakai, void ditolak dengan error `CREDIT_ALREADY_CONSUMED`; reversal credit lintas transaksi/refund berada di luar MVP.

Ini juga berarti fungsi kalkulasi denda harus dipisah dari "job scheduler wrapper"-nya
— job cuma orkestrasi (iterasi semua installment overdue), sementara kalkulasi per
installment adalah pure function yang dipanggil dari dua tempat: job & void handler.

---

## 7. Consistency check job (L-3)

**Masalah:** disebut P1 requirement tapi desainnya kosong.

### 7.1 Apa yang direkonsiliasi
L-3 dipecah menjadi tiga check independen:

**A — payment vs cash journal**
```
Σ POSTED Payment.amount = net KAS receipt journal untuk payment tersebut
```

**B — allocation vs payment**
```
Σ PaymentAllocation.amount = Payment.amount
```

**C — receivable vs installment**
```
Σ (principal residual + recognized interest residual + effective penalty residual) = saldo PIUTANG_POKOK + PIUTANG_BUNGA + PIUTANG_DENDA terkait kontrak
```

Excess tidak masuk `installment.paid_amount`, tetapi tetap harus muncul sebagai liability `TITIPAN_NASABAH`; karena itu tidak boleh dibandingkan langsung ke A/C tanpa mapping yang sesuai.

### 7.2 Tabel baru: `reconciliation_exception`
| Field | Type | Keterangan |
|---|---|---|
| contract_id | FK | |
| check_date | DATE | |
| check_type | ENUM(PAYMENT_CASH, ALLOCATION_PAYMENT, RECEIVABLE_INSTALLMENT) | |
| expected_amount | NUMERIC(19,2) | |
| actual_amount | NUMERIC(19,2) | |
| diff_amount | NUMERIC(19,2) | |
| status | ENUM(OPEN, INVESTIGATING, RESOLVED) | |
| resolved_note | TEXT NULL | |
| resolved_by | FK app_user NULL | |
| resolved_at | TIMESTAMP NULL | |

### 7.3 Frekuensi & respons
- Job jalan harian (setelah job denda selesai, urutan: denda → aging → consistency check).
- **Tidak auto-correct.** Sistem finansial tidak boleh mengubah angka sendiri tanpa
  jejak manusia. Job hanya mencatat exception dan mengirim alert (log level ERROR
  minimal; notifikasi eksternal di luar scope Fase 1).
- Endpoint baru untuk Finance role: `GET /api/v1/reports/reconciliation-exceptions`,
  `POST /api/v1/reports/reconciliation-exceptions/{id}/resolve`.

---

## 8. Day-count convention

**Masalah:** perhitungan bunga berjalan saat settlement mid-periode tidak punya
konvensi hari yang eksplisit.

**Keputusan:** pakai **ACT/30** (asumsi 1 bulan = 30 hari) untuk konsistensi dengan
skema FLAT yang sudah berbasis "per bulan" flat, bukan kalender aktual. Ini
didokumentasikan sebagai asumsi eksplisit (mengikuti pola §3 tech spec Fase 3
"Karena ini servicing-saja..."):

```
bungaBerjalan = outstanding_pokok_periode_berjalan × ratePerBulan × (hariBerjalan / 30)
```
`hariBerjalan` = jumlah hari sejak `due_date` periode sebelumnya (atau `start_date`
kalau periode pertama) sampai tanggal settlement, di-cap maksimal 30.

Ditambahkan ke golden test (§4 domain model): satu skenario settlement mid-periode
dengan angka bunga berjalan yang dihitung manual memakai konvensi ini.

---

## 9. PII handling (minimal, proporsional untuk portofolio)

- `Customer.nik`, `phone`, `address`: **tidak dilog** dalam bentuk apapun (masking
  di logging interceptor — nik tampil sebagai `****1234` kalau terpaksa muncul di log).
- Kolom `nik` **dan `phone`** di-encrypt at rest (application-level encryption via JPA
  `AttributeConverter` AES-256-GCM, bukan pgcrypto di level DB, supaya key management ada di
  aplikasi; lihat ADR-004). Envelope versi `v1:<base64(IV‖ciphertext|tag)>` disimpan di kolom
  `TEXT`; plaintext hanya ada di memory entity saat lifecycle JPA.
- `nik_hash` menyimpan HMAC-SHA-256 atas NIK yang sudah dinormalisasi menggunakan application secret. `nik_hash` yang dipakai untuk uniqueness/index; ciphertext random tidak dipakai untuk uniqueness. Secret tidak disimpan di database.
- `phone_lookup` menyimpan HMAC-SHA-256 atas phone yang sudah dinormalisasi (trunk code `0…` → `62`), **unique** — aturan bisnis satu phone per customer (konsisten dengan form frontend yang mewajibkan no. telpon). Phone `NOT NULL`.
- Pencarian nasabah by raw NIK/phone hanya melalui lookup service internal (`CustomerSearchService`): input dinormalisasi + di-HMAC di memory, query memakai lookup value; raw value tidak pernah menyentuh SQL/log/URL. Endpoint REST dari service ini **belum diekspos** — menunggu RBAC (default-deny, Sprint 6b) dan dicatat di matriks §3.4.
- Kunci: env `SERFIRA_PII_ENCRYPTION_KEY` (32 byte) dan `SERFIRA_PII_HMAC_KEY` (≥32 byte), base64; startup gagal cepat bila kosong. Nilai dev/portofolio ada di `docker-compose.yml` dan `src/test/resources/application.properties` — bukan production value.
- Endpoint yang mengembalikan data Customer di response envelope: field `nik`
  di-mask untuk role selain ADMIN_OPERASIONAL & FINANCE (mis. MANAJEMEN cuma lihat
  agregat, tidak perlu NIK penuh).
- **Proyeksi ter-mask juga yang disimpan untuk retry** (B5/ADR-007): `idempotency_keys.response_json`
  memuat payload respons apa adanya, yang berarti hanya nilai ter-mask — bukan plaintext PII. Yang
  tersimpan di `request_hash` adalah digest keyed (HMAC-SHA-256), bukan payload request, sehingga
  PII tidak pernah ikut tersimpan demi idempotency.

---

## 10A. Payment Date Policy

`paid_at` berasal dari application clock dan tidak boleh future. Backdated payment di luar business-date policy tidak didukung pada MVP agar penalty/aging deterministic.

---

## 10. Observability (minimal)

- **Correlation ID**: setiap request masuk (khususnya yang bawa `Idempotency-Key`)
  di-assign `X-Request-Id` (generate kalau belum ada dari client), di-propagate ke
  semua log line dan disimpan di `idempotency_keys` table supaya retry bisa di-trace.
- **Structured logging**: JSON log (bukan plain text), field wajib: `timestamp`,
  `level`, `request_id`, `contract_id` (kalau relevan), `module`.
- **Job metrics minimal**: setiap job (denda, aging, consistency check) mencatat
  `job_run(job_name, started_at, finished_at, records_processed, records_failed,
  status)` — satu tabel sederhana, cukup untuk portofolio menunjukkan bahwa job
  berjalan reliably dan bisa diaudit tanpa perlu infra monitoring eksternal (Prometheus/Grafana opsional Fase 4).

---

## 11. API contract & CI/CD (minimal, murah, dampak besar untuk portofolio)

- **OpenAPI**: generate otomatis dari annotation Spring (springdoc-openapi), expose
  di `/v3/api-docs` dan Swagger UI di `/swagger-ui.html` — hampir tanpa effort
  tambahan, tapi jadi bukti konkret kontrak API rapi.
- **CI pipeline** (GitHub Actions, `.github/workflows/ci.yml`): jalankan
  `./gradlew test` (unit + integration test via Testcontainers) di setiap push/PR,
  plus build image Docker. Tidak perlu deploy otomatis untuk portofolio — cukup
  badge "tests passing" di README.

---

## 12. Billing / Recognition Timing

- Pada setiap due date yang sudah tercapai, billing step mengakui `Installment.interest_amount` ke `PIUTANG_BUNGA / PENDAPATAN_BUNGA`. Implementasi MVP boleh menjadi step dalam daily scheduler.
- Penalty job mengakui denda harian secara incremental ke `PIUTANG_DENDA / PENDAPATAN_DENDA`.
- Settlement atas current period yang belum billed terlebih dahulu menambah `Installment.recognized_interest_amount` sebesar `bungaBerjalan` dan mem-posting `PIUTANG_BUNGA / PENDAPATAN_BUNGA`, lalu menerima cash.
- Receivable reconciliation hanya membandingkan principal residual + recognized interest residual + effective penalty residual terhadap saldo PIUTANG_*. Future scheduled interest yang belum recognized/billed tidak dihitung.

---

## 13. Settlement Transaction & Quote

Settlement menggunakan `settlement_quote` sebagai snapshot dan `settlement` + immutable `settlement_allocation` sebagai transaction record. Component snapshot quote immutable; hanya status yang boleh berpindah `QUOTED → EXECUTED/EXPIRED`. Quote mempunyai TTL, component snapshot, dan snapshot `contract.version`; execution wajib recalculate current components dan menolak bila snapshot berbeda atau expired. `settlement` menggunakan `Idempotency-Key`; quote yang sudah `EXECUTED` tidak boleh dieksekusi ulang dengan key berbeda.

**Credit policy MVP:** credit AVAILABLE tidak otomatis diterapkan ke installment regular. Settlement dapat secara eksplisit memakai available credit untuk mengurangi cash due. Setiap source credit dicatat di `settlement_credit_application`. Untuk **settlement**, seluruh AVAILABLE credit harus dikonsumsi; bila credit lebih besar dari gross settlement, settlement ditolak dan refund berada di luar MVP. **Maturity close** karena final regular payment boleh meninggalkan AVAILABLE credit sebagai liability terpisah.

---

## 14. Reporting Definitions

- **Outstanding:** principal residual + recognized interest residual + effective penalty residual.
- **AR:** outstanding dari installment yang due pada as-of date.
- **NPL:** outstanding kontrak dengan DPD > 90 / total active outstanding.
- **Collection rate:** regular-payment cash applied to installments due during period / recognized installment receivable due during period. Settlement cash ditampilkan sebagai metric terpisah.

---

## 15. Frontend (Next.js) spec — outline

Ini butuh dokumen sendiri (mis. `06_FRONTEND_SPEC.md`) kalau mau didetailkan, tapi
kerangka minimal supaya BE dan FE tidak jalan buta:

**Halaman inti (Fase 1–2):**
- `/login`
- `/contracts` (list + filter status) → `/contracts/[id]` (detail, installment
  schedule, tombol aktivasi)
- `/contracts/new` (form create kontrak DRAFT)
- `/contracts/[id]/payments/new` (form input pembayaran manual)
- `/contracts/[id]/statement` (rekening koran)
- `/reports/aging`, `/reports/outstanding`

**Konvensi teknis:**
- API client: thin wrapper di sekitar `fetch`, selalu unwrap envelope
  `{data, error}` di satu tempat (interceptor), lempar exception typed kalau
  `error` tidak null — jangan handle unwrap di tiap komponen.
- State server (data kontrak, installment) pakai server components / React Query
  untuk caching — state client (form input) pakai local state biasa, jangan
  campur.
- Auth: access token di memory (bukan localStorage, untuk kurangi exposure XSS),
  refresh token di httpOnly cookie.
- Idempotency-Key untuk form submit (pembayaran, settlement) di-generate di client
  saat form dibuka (UUID), bukan saat submit — supaya double-click/double-submit
  tetap pakai key yang sama.

---

## 16. Rate, Due Date, dan Settlement Semantics

### 16.1 Rate storage
Semua rate menggunakan fraction desimal: `1.5% = 0.0150`. Jangan ada modul yang menganggap `1.5` sebagai 150% secara implisit.

### 16.2 Due date MVP
Due date memakai calendar month + **month-end clamp**. Contoh 31 Januari → 28 Februari pada tahun non-leap atau 29 Februari pada leap year. Weekend dan public holiday tidak digeser pada MVP.

### 16.3 Settlement component rules
Settlement quote harus menghitung:
- seluruh pokok outstanding, termasuk pokok installment future;
- bunga yang sudah menjadi tagihan tetapi belum terbayar;
- bunga berjalan periode aktif sampai settlement (ACT/30, cap 30 hari);
- denda outstanding;
- dikurangi rebate yang eligible;
- ditambah admin fee.

Future scheduled interest yang belum recognized/billed tidak dihitung sebagai receivable outstanding dan tidak boleh double-count dengan bunga berjalan. Settlement execution tidak otomatis mengaplikasikan credit ke installment regular. Quote/execution boleh mengonsumsi `AVAILABLE` credit secara eksplisit sebagai pengurang cash due. Setiap source credit yang dikonsumsi dicatat di `settlement_credit_application` dengan amount. Journal: `Dr TITIPAN_NASABAH / Cr PIUTANG_*`. Untuk **settlement**, seluruh AVAILABLE credit harus dikonsumsi; bila credit lebih besar dari gross settlement, settlement ditolak dan refund menjadi flow lanjutan di luar MVP. Untuk **maturity close**, final regular payment boleh menutup contract walaupun masih ada AVAILABLE credit; credit tetap menjadi liability terpisah sampai refund/application flow tersedia.

### 16.4 Penalty waiver audit
Tambahkan tabel `penalty_adjustment` untuk waive/reduksi denda. Adjustment bersifat append-only dan memuat `installment_id`, `adjustment_type`, `amount`, `reason`, `approved_by`, serta audit fields. Adjustment menghasilkan jurnal penyesuaian. Effective outstanding penalty = accrual yang diakui − penalty allocation aktif − adjustment.

### 16.5 Semantik alokasi pembayaran (C2)

Gap: PRD §3 memuat urutan `denda → bunga → pokok` pada "angsuran paling jatuh tempo dahulu" dan skenario 4
menyatakan excess tidak otomatis melunasi angsuran berikutnya, tetapi tidak pernah menyatakan **window**
kelayakan (apakah angsuran yang belum jatuh tempo boleh dialokasikan) atau granularitas waterfall-nya.

Keputusan (detail + alternatif di ADR-009):
- Window = `due_date <= business date` pembayaran. Pembayaran yang datang sebelum due date pertama menjadi
  EXCESS penuh (credit `TITIPAN_NASABAH`), dan baru boleh dikonsumsi setelah receivable-nya diakui (§2.1).
- Waterfall per angsuran: tertua dahulu (`due_date`, tie-break `period_no`), di dalamnya `PENALTY → INTEREST → PRINCIPAL`.
- Cap mengikuti trigger V3 (principal/recognized interest/effective penalty) dan `recognized_total − resolved_amount`.
- `EXCESS` maksimal satu baris dan hanya bila sisa > 0; baris bernilai nol tidak pernah dibuat (invariant 6).
- Konsekuensi yang disadari: PRD skenario 1 (lunas tepat waktu) baru penuh setelah C4 (billing/recognition)
  ada, karena bunga hanya receivable setelah di-bill pada due date.

---

## 17. Ringkasan perubahan skema (untuk Flyway migration)

Tabel baru: `document_number_counter`, `system_parameter`, `contract_credit`, `contract_credit_application`, `penalty_adjustment`, `settlement_quote`, `settlement`, `settlement_allocation`, `settlement_credit_application`, `app_user`, `refresh_token`, `idempotency_keys`, `reconciliation_exception`, `job_run`, `outbox_events`.
Field baru di Customer: `nik_hash`. Field baru di Contract: `asset_price`, `grace_period_days`, `penalty_rate_daily`, `write_off_reason`, `write_off_recorded_by`.
Field baru di Contract (B5, ADR-006/ADR-007): `planned_start_date` (V6) dan `idempotency_key` (V7).
Index baru (B5, V7): `uq_contract_idempotency UNIQUE (idempotency_key) WHERE idempotency_key IS NOT NULL` (backstop retry safety) dan `uq_contract_live_asset UNIQUE (customer_id, asset_id) WHERE status IN ('DRAFT','ACTIVE')` (invariant 18).
Akun COA baru: `TITIPAN_NASABAH`, `BIAYA_PENGHAPUSAN_PIUTANG`.
Semua migration harus diterapkan via Flyway; tidak ada perubahan schema manual.

---

## 18. Demo Seed Data Specification

Seed data ini harus tersedia setelah `docker-compose up` agar demo script (G5) bisa dijalankan ulang tanpa setup manual. Dua kontrak demonstrasi dengan karakteristik berbeda.

### 18.1 Kontrak Demo A — FLAT, Skenario Normal & Late

| Field | Value |
|---|---|
| customer | Budi Santoso, NIK: `3171012501900001` |
| asset | MOTORCYCLE, Honda Beat, Plat B1234XY |
| asset_price | Rp 20.000.000 |
| down_payment | Rp 4.000.000 |
| principal | Rp 16.000.000 |
| tenor_months | 12 |
| interest_scheme | FLAT |
| interest_rate | 0.0150 (1.5%/bulan) |
| start_date | 2026-01-31 |

**Skenario pre-seeded (state saat demo start):**
- Installment 1 (Feb 28): PAID — payment normal tepat waktu.
- Installment 2 (Mar 31): PAID — payment telat 5 hari, denda sudah terbayar.
- Installment 3 (Apr 30): PARTIALLY_PAID — partial payment, saldo tersisa.
- Installment 4–12: OVERDUE / PENDING (tergantung tanggal demo).

**Golden values (harus cocok dengan engine):**
- bungaTotal = 16.000.000 × 0.0150 × 12 = 2.880.000
- angsuranPokok = 16.000.000 / 12 = 1.333.333,33 (installment terakhir menyerap residual)
- angsuranBunga = 2.880.000 / 12 = 240.000
- angsuranTotal = 1.573.333,33/bulan (last installment adjust)

### 18.2 Kontrak Demo B — EFFECTIVE, Skenario Settlement

| Field | Value |
|---|---|
| customer | Siti Rahayu, NIK: `3275015506850002` |
| asset | CAR, Toyota Avanza, Plat D5678YZ |
| asset_price | Rp 200.000.000 |
| down_payment | Rp 50.000.000 |
| principal | Rp 150.000.000 |
| tenor_months | 36 |
| interest_scheme | EFFECTIVE |
| interest_rate | 0.0075 (0.75%/bulan) |
| start_date | 2026-01-15 |

**Skenario pre-seeded:**
- Installment 1–6: PAID — semua tepat waktu, tidak ada denda.
- Installment 7–36: PENDING/OVERDUE.
- State ini memposisikan kontrak untuk demo **pelunasan dipercepat** (settlement) pada bulan ke-7.

**Golden value installment #1 (pinned by `EffectiveScheduleGoldenTest.demoContractBGoldenCase`):**
- i = 0.0075, n = 36
- A = 150.000.000 × 0.0075 × (1.0075^36) / ((1.0075^36) − 1) = 4.769.959,90
  (DECIMAL128 precision, rounding HALF_EVEN; the earlier draft value ≈ 4.801.482 was wrong — verified numerically and by the golden test)
- bunga bulan 1 = 150.000.000 × 0.0075 = 1.125.000
- pokok bulan 1 = A − 1.125.000 = 3.644.959,90
- Installment terakhir (36): pokok 4.734.451,45, bunga 35.508,39, total 4.769.959,84 (menyerap residual)
- Σ pokok = 150.000.000,00; Σ bunga = 21.718.556,34

### 18.3 Seed User Demo

| username | password | role |
|---|---|---|
| `admin` | `Admin123!` | ADMIN_OPERASIONAL |
| `finance` | `Finance123!` | FINANCE |
| `manager` | `Manager123!` | MANAJEMEN |

> **Security note:** Password di atas hanya untuk demo/portofolio. Argon2id hash wajib digunakan — jangan simpan plaintext. Seed migration men-generate hash dari password ini.

### 18.4 Implementasi seed

Buat Flyway migration terpisah `V99__demo_seed.sql` (bukan V1) agar seed tidak bercampur dengan schema baseline dan dapat di-skip untuk production profile. Spring profile `demo` mengaktifkan migration ini; profile `prod` melewatinya.

Script demo (`docs/demo-script.sh` atau `docs/demo-script.curl`) wajib dimulai dengan:
```bash
# Reset seed: drop + recreate DB via docker-compose
docker-compose down -v && docker-compose up -d
# Tunggu aplikasi ready
sleep 5
# Jalankan skenario
curl -s -X POST http://localhost:8080/api/v1/auth/login ...
```