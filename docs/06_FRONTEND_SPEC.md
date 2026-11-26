# Frontend Specification — Multifinance Loan Servicing Core System

- Versi: 0.2 (pre-Sprint 7, stack version re-verified 2026-11-19)
- Stack: Next.js 16 (App Router), TypeScript, shadcn/ui, React Query (TanStack Query v5)
- Scope: Fase 1–2 halaman operasional; Fase 3 menambahkan reporting.

> **Filosofi:** FE adalah thin operational UI, bukan showcase design. Gunakan shadcn/ui komponen as-is, tidak perlu custom design system. Fokus pada kebenaran data dan UX workflow yang efisien.

---

## 1. Arsitektur Frontend

### 1.1 Stack Decision
| Concern | Pilihan | Alasan |
|---|---|---|
| Framework | Next.js 16 App Router (pin `16.4.6`) | SSR/SSG untuk initial load, server components untuk data fetching; major terbaru yang lolos cek CVE |
| UI Components | shadcn/ui | Akselerasi development, tidak perlu design dari nol |
| Data fetching (client) | TanStack Query v5 | Caching, refetch, loading state otomatis |
| Forms | React Hook Form + Zod | Validasi client-side, type-safe |
| HTTP client | Custom `apiFetch` wrapper | Satu tempat unwrap `{data, error}` envelope |
| Auth state | Memory (access token + refresh token) | Sesuai Tech Spec §2.6. X-14 sudah diputuskan (ADR-022 D10): refresh token dikirim di body, bukan cookie, jadi tidak ada CSRF/CORS ber-kredensial. |

> **Versi framework (diverifikasi 2026-11-19).** `next@latest` = **16.4.6** (Node ≥ 20.9.0, React 19). Query
> OSV.dev dengan `version: 16.4.6` mengembalikan **nol advisory** (semua severity); advisory HIGH/CRITICAL
> Next.js yang terdaftar sudah di-fix pada rilis ≤ 16.4.6. Pin versi eksak **16.4.6**, bukan rentang `^`,
> dan cek ulang CVE saat T32 dimulai serta setiap kali versi dinaikkan (advisory baru bisa muncul kapan
> saja). Jangan menaikkan major tanpa cek CVE dan tanpa mencatat hasilnya di ADR-023.

### 1.2 Struktur Folder
```
frontend/
├── app/
│   ├── (auth)/
│   │   └── login/
│   │       └── page.tsx
│   ├── (dashboard)/
│   │   ├── layout.tsx          ← sidebar + navbar + auth guard
│   │   ├── contracts/
│   │   │   ├── page.tsx        ← list contracts
│   │   │   ├── new/
│   │   │   │   └── page.tsx    ← create contract form
│   │   │   └── [id]/
│   │   │       ├── page.tsx    ← contract detail
│   │   │       ├── payments/
│   │   │       │   └── new/
│   │   │       │       └── page.tsx  ← input pembayaran
│   │   │       └── statement/
│   │   │           └── page.tsx      ← rekening koran
│   │   └── reports/
│   │       ├── aging/
│   │       │   └── page.tsx
│   │       └── outstanding/
│   │           └── page.tsx
├── components/
│   ├── ui/                     ← shadcn/ui generated components
│   ├── contracts/
│   │   ├── ContractCard.tsx
│   │   ├── InstallmentTable.tsx
│   │   └── ContractStatusBadge.tsx
│   ├── payments/
│   │   └── PaymentForm.tsx
│   └── shared/
│       ├── ErrorAlert.tsx
│       ├── LoadingSpinner.tsx
│       ├── MoneyDisplay.tsx    ← format Rp dengan locale id-ID
│       └── DateDisplay.tsx
├── lib/
│   ├── api.ts                  ← apiFetch wrapper + token management
│   ├── auth.ts                 ← token store di memory, refresh logic
│   └── utils.ts                ← shadcn/ui cn() + shared helpers
├── hooks/
│   ├── useContract.ts
│   ├── useInstallments.ts
│   └── usePayment.ts
└── types/
    ├── contract.ts
    ├── payment.ts
    └── api.ts                  ← ApiResponse<T> envelope type
```

> **Status T32 (2026-11-20):** the shell, login, contract list/create/detail/activate and a minimal
> statement page are implemented. Beyond the tree above, the delivered layout adds `app/providers.tsx`
> (QueryClient + toaster), `components/auth/LoginForm.tsx`,
> `components/contracts/{ContractListView,CreateContractForm,ContractDetailView,ActivateContractButton,CreditPanel,StatementView}.tsx`,
> `components/shared/{AppHeader,AppSidebar,AuthGuard,RequireWrite}.tsx`,
> `lib/{session,jwt,decimal,format,navigation}.ts` plus `lib/schemas/`, and
> `hooks/{useContracts,useSession,useStatement,useIdempotencyKey}.ts`, with tests beside each unit and a
> `test/` helper directory. `payments/`, `reports/`, `types/payment.ts` and `hooks/usePayment.ts` remain
> T33/T37/T39.

### 1.3 API Client Pattern
```typescript
// lib/api.ts
interface ApiResponse<T> {
  data: T | null;
  error: { code: string; message: string } | null;
}

// Endpoint auth publik tidak boleh membawa access token: permitAll hanya mengatur
// otorisasi, sedangkan bearer token yang kedaluwarsa ditolak resource server sebelum
// request mencapai controller.
const PUBLIC_AUTH_PATHS = ['/auth/login', '/auth/refresh', '/auth/logout'];

async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const accessToken = getAccessToken();  // dari memory store
  const isPublicAuth = PUBLIC_AUTH_PATHS.includes(path);
  const res = await fetch(`/api/v1${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken && !isPublicAuth
        ? { 'Authorization': `Bearer ${accessToken}` }
        : {}),
      ...options?.headers,  // header pemanggil tetap menang
    },
  });
  const body: ApiResponse<T> = await res.json();
  if (body.error) {
    throw new ApiError(body.error.code, body.error.message, res.status);
  }
  return body.data!;
}
```

**Aturan:** unwrap envelope **hanya di `apiFetch`**. Komponen dan hooks tidak boleh handle raw `{data, error}` secara manual.

### 1.4 Auth Flow

> **X-14 resolved (ADR-022 D10):** refresh token di **body respons**, disimpan di memory bersama access
> token. Tidak ada cookie `HttpOnly`, sehingga tidak ada CSRF handling maupun CORS ber-kredensial pada
> fase ini; keputusan cookie ditinjau ulang saat browser client nyata ada (G2).

```
1. Login → POST /auth/login {username, password} tanpa header Authorization
   → simpan access token + refresh token di memory
2. Request terproteksi → Authorization: Bearer <access_token>
3. 401 response → POST /auth/refresh {"refresh_token": <memory>} tanpa header Authorization
   → ganti keduanya dengan pasangan baru (token lama langsung tidak berlaku: rotasi single-use)
4. Refresh gagal (401) → buang kedua token, redirect ke /login
5. Logout → POST /auth/logout {"refresh_token": <memory>} tanpa header Authorization
   → buang kedua token (endpoint idempoten, 200)
```

**Kredensial per request:**

| Request | Kredensial |
| --- | --- |
| `POST /auth/login` | `username` + `password` di body |
| `POST /auth/refresh` | `refresh_token` di body |
| `POST /auth/logout` | `refresh_token` di body |
| Endpoint terproteksi | `Authorization: Bearer <access_token>` |

Login, refresh, dan logout adalah `permitAll` (ADR-022 D7), tetapi `permitAll` hanya
mengatur **otorisasi**, bukan autentikasi bearer. Resource server memproses header
`Authorization` lebih dulu dan menolak token yang kedaluwarsa sebelum request mencapai
controller, sehingga ketiga endpoint tersebut **tidak boleh** mengirim access token yang
tersimpan — token itu justru mungkin sudah kedaluwarsa saat refresh atau logout dipanggil.

**Aturan token:** token hanya di memory (bukan `localStorage`/`sessionStorage`), sehingga reload halaman
memaksa login ulang. Refresh tidak boleh dipanggil paralel dari beberapa request; `lib/auth.ts` memegang
satu promise refresh in-flight dan request lain menunggu hasilnya, karena rotasi single-use membuat
refresh kedua dengan token lama ditolak 401.

**Idempotency-Key generation:**
- Di-generate saat form pembuka pertama kali mount (UUID v4), bukan saat submit.
- Disimpan di React state lokal form; tidak perlu di store global.
- Double-submit prevention: disable tombol submit saat `isSubmitting=true`, tapi Idempotency-Key tetap sama.

---

## 2. Halaman & Komponen

### 2.1 `/login`
**Komponen:** form username + password, tombol Login.
**Behavior:**
- Submit → `POST /auth/login` → simpan access token dan refresh token di memory → redirect ke `/contracts`.
- Error display: tunjukkan `error.message` dari envelope jika 401/403.
- Brute-force lockout: **423 `ACCOUNT_LOCKED`** menampilkan "Account locked, try again in X minutes" dari
  `error.message` BE (pesan sudah memuat sisa menit). Form tidak perlu membedakan 401 vs 423 selain teks.

**Form fields:** `username` (required), `password` (required, min 8 char client-side).

---

### 2.2 `/contracts` — Contract List
**Komponen:** tabel kontrak dengan filter dan pagination.

**Kolom tabel:**
| Kolom | Source | Format |
|---|---|---|
| Contract No | `contract_no` | string |
| Customer | `customer.full_name` | string |
| Asset | `asset.brand + model` | string |
| Principal | `principal` | Rp format |
| Tenor | `tenor_months` | "12 bulan" |
| Status | `status` | Badge berwarna |
| Outstanding | `outstanding` | Rp format |
| Created | `created_at` | dd MMM yyyy |

**Filter:** Status (DRAFT/ACTIVE/CLOSED/TERMINATED), search by contract_no atau customer name.
**Pagination:** `page`, `size=20`, sort by `created_at` DESC default.
**Action:** Klik baris → `/contracts/[id]`; tombol "+ Buat Kontrak" → `/contracts/new`.

**RBAC:** Tombol "+ Buat Kontrak" hanya muncul untuk `ADMIN_OPERASIONAL`.

---

### 2.3 `/contracts/new` — Create Contract Form
**Komponen:** form multi-field dengan validasi Zod.

**Fields:**
| Field | Input Type | Validasi |
|---|---|---|
| Nama Nasabah | text | required |
| NIK | text | required, 16 digit |
| No. Telpon | text | required |
| Alamat | textarea | required |
| Tipe Asset | select (MOTORCYCLE/CAR/ELECTRONICS/OTHER) | required |
| Brand, Model | text | required |
| No. Seri | text | optional |
| No. Plat | text | optional |
| Harga Asset | text (desimal) | > 0, maksimum 17 digit integer dan skala ≤ 2 (`NUMERIC(19,2)`) |
| Uang Muka (DP) | text (desimal) | >= 0, < asset_price, domain yang sama |
| Plafon (auto-calc) | readonly | = asset_price - down_payment |
| Tenor (bulan) | number | > 0, integer |
| Skema Bunga | select (FLAT/EFFECTIVE) | required |
| Suku Bunga (% per bulan) | text (desimal) | >= 0, maksimum 2 desimal |
| Tanggal Mulai | date picker | required |

**Klarifikasi B5 (ADR-006):** field "Tanggal Mulai" dipetakan ke `planned_start_date`
(tanggal yang diotor operator saat drafting). Effective `start_date` baru ter-pin saat
aktivasi — default sama dengan `planned_start_date`.

**Submit:** `POST /contracts` dengan header `Idempotency-Key` (**wajib** sejak B5,
ADR-007 — lihat §1.4; retry dengan key sama me-replay respons create, bukan membuat
kontrak kedua) → redirect ke `/contracts/[id]` (DRAFT state).
**Note:** Status DRAFT, belum generate jadwal; `outstanding` = `null` pada DRAFT
(tampilkan "–"). Tombol Aktivasi ada di detail page.

---

### 2.4 `/contracts/[id]` — Contract Detail
**Komponen utama:** header info kontrak + tab panel.

**Header:** Contract No, Status badge, Customer, Asset, Principal, Tenor, Rate, Outstanding total.

**Tabs:**
1. **Jadwal Angsuran** — tabel installments (lihat §2.5).
2. **Pembayaran** — list payments dengan status POSTED/VOIDED.
3. **Kredit** — saldo available credit + history application. "Terapkan Kredit" (ADMIN_OPERASIONAL, ACTIVE)
   memakai satu `Idempotency-Key` per operasi apply: retry setelah respons hilang memakai key dan jumlah yang
   sama (server me-replay), key tetap sama walau dialog ditutup/dibuka, dan key baru hanya dibuat setelah
   sukses atau lewat "Mulai form baru" saat key terpakai (`CONFLICT`/`IDEMPOTENCY_KEY_EXPIRED`).
4. **Ledger** — link ke statement.

**Actions (ADMIN_OPERASIONAL only):**
- DRAFT: tombol "Aktivasi" → `POST /contracts/{id}/activate` → konfirmasi dialog.
  **Klarifikasi B5 (ADR-006):** dialog konfirmasi tidak memuat input tanggal;
  BE memakai `planned_start_date` sebagai effective `start_date`. Body request
  boleh membawa `start_date` opsional (untuk slip pencairan dengan tanggal efektif
  berbeda), tapi UI standar tidak mengekspornya. Aktivasi tidak memakai
  `Idempotency-Key` — repeat call pada kontrak yang sudah ACTIVE mengembalikan
  representasi saat ini tanpa write (idempotent by state machine).
- ACTIVE: tombol "Catat Pembayaran" → `/contracts/[id]/payments/new`.
- ACTIVE: tombol "Pelunasan Dipercepat" → modal settlement flow.
- ACTIVE: tombol "Write-off" → konfirmasi dialog + input reason.

---

### 2.5 Installment Table (komponen reusable)
**Kolom:**
| Kolom | Source | Keterangan |
|---|---|---|
| No. | `period_no` | |
| Jatuh Tempo | `due_date` | dd MMM yyyy |
| Pokok | `principal_amount` | Rp |
| Bunga | `interest_amount` | Rp |
| Denda | `effective_penalty` | Rp (derived) |
| Total Tagihan | `recognized_total` | Rp |
| Terbayar | `resolved_amount` | Rp |
| Sisa | `outstanding` | Rp |
| Status | `status` | Badge |

**Status badge colors:**
- `PENDING` → gray
- `PARTIALLY_PAID` → yellow
- `PAID` → green
- `OVERDUE` → red
- `SETTLED` → blue
- `WRITTEN_OFF` → dark gray / strikethrough

---

### 2.6 `/contracts/[id]/payments/new` — Input Pembayaran
**Komponen:** form pembayaran.

**Fields:**
| Field | Input | Keterangan |
|---|---|---|
| Jumlah Bayar | text (desimal) | > 0, maksimum 17 digit integer dan skala ≤ 2 (`NUMERIC(19,2)`) |
| Channel | select (CASH/BANK_TRANSFER/VA_STUB/EWALLET_STUB) | |

**Tanpa input tanggal dan catatan (keputusan T33, 2026-11-25):** `PaymentRequest` hanya menerima
`contract_id`, `amount`, dan `channel`. Waktu bayar selalu jam server (TS §2.0) dan tabel `payment` tidak punya
kolom catatan, jadi form tidak menampilkan "Tanggal Bayar" maupun "Catatan". Tampilan sukses menunjukkan
`paid_at` dari respons server.

**Idempotency-Key:** UUID di-generate saat halaman mount, di-pass sebagai header `Idempotency-Key`.
Retry setelah gagal memakai key yang sama. Jika key ditolak sebagai sudah terpakai (409 `CONFLICT` atau
`IDEMPOTENCY_KEY_EXPIRED`), form tidak membuat key baru secara diam-diam: operator diminta memeriksa rekening
koran lalu memilih "Mulai form baru", yang me-mount ulang form dengan key baru.

**Submit:** `POST /payments` → tampilkan sukses + alokasi breakdown → link kembali ke contract detail.

**Preview alokasi (optional nice-to-have):** sebelum submit, tampilkan estimasi alokasi denda→bunga→pokok berdasarkan outstanding saat ini. Hanya informatif, bukan constraint.

---

### 2.7 `/contracts/[id]/statement` — Rekening Koran
**Komponen:** tabel mutasi per kontrak secara kronologis.

**Kolom (ADR-013, menutup A-8 — ledger-literal, bukan customer balance view):**
| Kolom | Source |
|---|---|
| Tanggal | `entry_date` |
| Akun | `account_code` (+ nama akun) |
| Keterangan | `description` (dari `journal_entry` induk) |
| Ref Type | `ref_type` (PAYMENT/SETTLEMENT/PENALTY_ACCRUAL/BILLING/...) |
| Debit | `debit` — nilai `journal_line` literal untuk baris itu, bukan agregat |
| Kredit | `credit` — nilai `journal_line` literal untuk baris itu, bukan agregat |
| Reversal | flag bila `reversal_of_id IS NOT NULL` |
| Kontrak Ref | `ref_id` singkat (8 karakter, id lengkap di tooltip). Link ke payment/settlement menyusul di T39, karena belum ada halaman/endpoint detail payment |

Tidak ada kolom running balance pada Phase 1: baris melintasi akun yang berbeda arti (`PIUTANG_*`, `KAS`,
`PENDAPATAN_*`, `TITIPAN_NASABAH`), dan sebuah saldo tunggal berjalan lintas akun tidak mewakili apa pun yang
berguna. Agregat outstanding yang berarti tersedia lewat laporan aging (§2.9).

**Filter:** date range, ref_type — dikirim ke API (`from`/`to` inklusif, `ref_type`), tidak difilter di
browser. Rentang dengan tanggal awal setelah tanggal akhir ditolak di form tanpa request.

---

### 2.8 Settlement Flow (modal di Contract Detail)
**Step 1 — Quote:**
- Tombol "Lihat Quote Pelunasan" → `POST /settlements/quote`.
- Tampilkan breakdown: Outstanding Pokok, Bunga Tertunggak, Bunga Berjalan, Denda, Rebate, Admin Fee, **Total Cash Due**.
- Quote valid selama 15 menit (countdown timer di UI).

**Step 2 — Eksekusi:**
- Tombol "Lunasi Sekarang" → konfirmasi dialog dengan total cash due.
- `POST /settlements` dengan `quote_id` + `Idempotency-Key`.
- Success → contract status menjadi CLOSED → redirect ke contract detail.
- Error STALE_SETTLEMENT_QUOTE → tampilkan pesan "Quote sudah kadaluarsa, ambil quote baru".

---

### 2.9 `/reports/aging` — Laporan Aging
**Filter:** as-of date (default today). ADR-013 (menutup A-6): hanya `today` yang valid pada Phase 1, tidak
ada rekonstruksi historis — nilai lain ditolak backend dengan `400 INVALID_AS_OF_DATE`. UI mengunci filter
ke hari ini sampai historical `as_of` benar-benar didukung: field "Per tanggal" read-only menampilkan `as_of`
dari respons, dan request **tidak pernah mengirim** `as_of` (backend memakai hari ini, Asia/Jakarta), sehingga
browser di zona waktu lain tidak bisa meminta tanggal yang salah (T37).

**Tampilan:** tabel dengan kolom aging bucket, dihitung per installment lalu dijumlahkan per kontrak
(ADR-013, menutup A-7):
| Contract No | Customer | Outstanding | Current | 1-30 DPD | 31-60 DPD | 61-90 DPD | >90 DPD |
|---|---|---|---|---|---|---|---|

Hanya kontrak `ACTIVE` yang tampil. Kolom Customer berasal dari `customer_name` baris aging (dibawa lewat port
`contract`; NIK dan telepon tidak pernah muncul di laporan). **Summary baris total** di atas baris kontrak
berasal dari `portfolio` respons, tidak pernah dijumlahkan dari halaman yang sedang tampil. Paging
server-side (`page`, `size=20`).

---

### 2.10 `/reports/outstanding` — Outstanding Portfolio
**Tampilan:** summary cards + tabel.

Sumber: `GET /api/v1/reports/outstanding` (T36). Definisi ada di **ADR-026**. Browser tidak menghitung
metrik apa pun: semua angka diformat apa adanya dari API (rasio lewat `formatRatioPercent`, `"0.0150"` →
`1,50%`), dan rasio `null` tampil sebagai tanda kosong aplikasi "–" (`EMPTY_VALUE`).

**Cards:**
- Total Outstanding
- Total AR: receivable yang sudah jatuh tempo s.d. hari ini, termasuk yang masih dalam grace. Bukan "overdue".
- NPL %: seluruh outstanding kontrak yang punya installment >90 DPD / total outstanding, plus nominal dan
  jumlah kontraknya.
- Collection Rate bulan berjalan (MTD): collected / due beserta label periodenya.
- Settlement Cash MTD: card terpisah, tidak masuk collection rate.

**Tabel:** per-contract breakdown memakai ulang baris `GET /reports/aging` (outstanding + bucket, kontrak
ACTIVE saja, sehingga tanpa kolom status). Baris dengan `dpd_over_90 > 0` diberi badge NPL (identitas
ADR-026 D4). Tidak ada endpoint per-contract kedua.

---

## 3. Konvensi Teknis

### 3.1 Money Formatting

Uang dan rate datang sebagai **string desimal eksak** dari API (`"20000000.00"`, ADR-024), bukan JSON number.
Jangan pernah melewatkannya ke `Number`/`parseFloat`: `NUMERIC(19,2)` punya 17 digit integer, di atas presisi
`double`. Validasi, penjumlahan, dan konversi rate memakai `lib/decimal.ts` (BigInt); tampilan memakai
`lib/format.ts`:

```typescript
// components/shared/MoneyDisplay.tsx → lib/format.ts
formatMoney("1573333.00"); // "Rp 1.573.333"
formatMoney(null); // "–" (DRAFT outstanding)
```

`formatMoney` mem-parse string menjadi `BigInt` rupiah utuh (pembulatan half away from zero) lalu memformat
dengan `Intl`; tidak ada float di jalur mana pun, dan digit di atas `Number.MAX_SAFE_INTEGER` tetap utuh.

Input operator divalidasi ke domain `NUMERIC(19,2)` (maksimum 17 digit integer, skala ≤ 2) sebelum dikirim;
nilai yang lebih besar atau lebih halus ditolak sebagai error field, tidak dibulatkan diam-diam.

### 3.2 Date Formatting
```typescript
// Gunakan date-fns atau Intl.DateTimeFormat; jangan moment.js
const formatDate = (iso: string) =>
  new Intl.DateTimeFormat('id-ID', { day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(iso));
// Output: "28 Feb 2026"
```

### 3.3 Error Handling
```typescript
// Semua error dari ApiError di-catch di level komponen atau React Query onError
// Jangan let error bubbles unhandled
// Toast notification untuk error yang tidak mengubah state page
// Inline error untuk form validation errors
```

### 3.4 Loading States
- Skeleton loaders untuk table rows (shadcn/ui `Skeleton`).
- Spinner di dalam tombol saat submit (`isSubmitting`).
- Disable form saat submitting untuk prevent double submit.

### 3.5 RBAC di Frontend
```typescript
// Bukan pengganti BE enforcement, hanya untuk UX (hide/disable button)
const { role } = useAuth();
const canWrite = role === 'ADMIN_OPERASIONAL';
// <Button disabled={!canWrite}>Catat Pembayaran</Button>
```
Backend tetap menjadi enforcement utama; frontend hanya mengurangi confusion.

---

## 4. Environment & Deployment

```env
# .env.local
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

API dipanggil **langsung** dari browser ke backend (`NEXT_PUBLIC_API_BASE_URL`) dengan CORS di sisi backend;
tidak ada BFF, `app/api/` proxy, atau route handler perantara. Ini keputusan Sprint 7: satu boundary auth
(token di memory, tanpa cookie), satu kontrak error envelope, dan tidak ada lapisan yang bisa menyimpang
dari API. Detail CORS (origin allowlist, header, tanpa credentials) dan konsekuensi keamanannya dicatat di
**ADR-023** (T35); endpoint auth tidak pernah membawa access token (ADR-022 D7).

`NEXT_PUBLIC_*` **di-inline saat build**, jadi origin yang masuk ke bundle adalah nilai saat `next build`
berjalan, bukan saat `next start`. Aturannya:

- Development (`npm run dev`) boleh tidak mengisi variabel ini; fallback-nya `http://localhost:8080`.
- **Production build wajib** mengisi origin absolut `http`/`https`. Tanpa itu `next build` gagal, alih-alih
  diam-diam mengirim client yang menunjuk ke localhost.
- Nilai tidak boleh mengandung credentials, query string, atau fragment; trailing slash dibuang.
- CI memakai placeholder reserved `https://ci-build.invalid` karena tidak punya API ter-deploy.
- Ubah nilai berarti build ulang; mengedit `.env.local` tidak mengubah bundle yang sudah jadi.

Uang dan rate adalah **string desimal eksak** di wire (`"20000000.00"`, ADR-024); frontend tidak pernah
mem-parse-nya lewat `Number`/`parseFloat`. Format tampilan (`Rp`, `id-ID`, tanpa desimal) dihitung dengan
BigInt di `lib/format.ts`, dan input operator dibatasi ke domain `NUMERIC(19,2)` sebelum dikirim.

---

## 5. Deferred / Out of Scope Fase 1–3

- Custom design system / branding.
- Dark mode.
- Mobile responsiveness (tabel bisa horizontal scroll).
- Real-time update (WebSocket/SSE) — refresh manual sudah cukup untuk portofolio.
- Export CSV dari UI — R-4 adalah P2 dan deferred.
- Notifikasi push / email.
