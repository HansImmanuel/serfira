# Frontend Specification — Multifinance Loan Servicing Core System

- Versi: 0.1 (pre-Sprint 7)
- Stack: Next.js 14 (App Router), TypeScript, shadcn/ui, React Query (TanStack Query v5)
- Scope: Fase 1–2 halaman operasional; Fase 3 menambahkan reporting.

> **Filosofi:** FE adalah thin operational UI, bukan showcase design. Gunakan shadcn/ui komponen as-is, tidak perlu custom design system. Fokus pada kebenaran data dan UX workflow yang efisien.

---

## 1. Arsitektur Frontend

### 1.1 Stack Decision
| Concern | Pilihan | Alasan |
|---|---|---|
| Framework | Next.js 14 App Router | SSR/SSG untuk initial load, server components untuk data fetching |
| UI Components | shadcn/ui | Akselerasi development, tidak perlu design dari nol |
| Data fetching (client) | TanStack Query v5 | Caching, refetch, loading state otomatis |
| Forms | React Hook Form + Zod | Validasi client-side, type-safe |
| HTTP client | Custom `apiFetch` wrapper | Satu tempat unwrap `{data, error}` envelope |
| Auth state | Memory (access token) + httpOnly cookie (refresh token) | Sesuai Tech Spec §2.6 |

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

### 1.3 API Client Pattern
```typescript
// lib/api.ts
interface ApiResponse<T> {
  data: T | null;
  error: { code: string; message: string } | null;
}

async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${getAccessToken()}`,  // dari memory store
      ...options?.headers,
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
```
1. Login → POST /auth/login → access token (memory) + refresh cookie (httpOnly, auto-set by BE)
2. Setiap request → Authorization: Bearer <access_token>
3. 401 response → POST /auth/refresh (cookie dikirim otomatis) → access token baru di memory
4. Refresh gagal → redirect ke /login
5. Logout → POST /auth/logout + clear memory token
```

**Idempotency-Key generation:**
- Di-generate saat form pembuka pertama kali mount (UUID v4), bukan saat submit.
- Disimpan di React state lokal form; tidak perlu di store global.
- Double-submit prevention: disable tombol submit saat `isSubmitting=true`, tapi Idempotency-Key tetap sama.

---

## 2. Halaman & Komponen

### 2.1 `/login`
**Komponen:** form username + password, tombol Login.
**Behavior:**
- Submit → `POST /auth/login` → simpan access token di memory → redirect ke `/contracts`.
- Error display: tunjukkan `error.message` dari envelope jika 401/403.
- Brute-force lockout: tampilkan "Account locked, try again in X minutes" dari error message BE.

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
| Harga Asset | number | > 0 |
| Uang Muka (DP) | number | >= 0, < asset_price |
| Plafon (auto-calc) | readonly | = asset_price - down_payment |
| Tenor (bulan) | number | > 0, integer |
| Skema Bunga | select (FLAT/EFFECTIVE) | required |
| Suku Bunga (% per bulan) | number | >= 0 |
| Tanggal Mulai | date picker | required |

**Submit:** `POST /contracts` → redirect ke `/contracts/[id]` (DRAFT state).
**Note:** Status DRAFT, belum generate jadwal. Tombol Aktivasi ada di detail page.

---

### 2.4 `/contracts/[id]` — Contract Detail
**Komponen utama:** header info kontrak + tab panel.

**Header:** Contract No, Status badge, Customer, Asset, Principal, Tenor, Rate, Outstanding total.

**Tabs:**
1. **Jadwal Angsuran** — tabel installments (lihat §2.5).
2. **Pembayaran** — list payments dengan status POSTED/VOIDED.
3. **Kredit** — saldo available credit + history application.
4. **Ledger** — link ke statement.

**Actions (ADMIN_OPERASIONAL only):**
- DRAFT: tombol "Aktivasi" → `POST /contracts/{id}/activate` → konfirmasi dialog.
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
| Jumlah Bayar | number | > 0 |
| Channel | select (CASH/BANK_TRANSFER/VA_STUB/EWALLET_STUB) | |
| Tanggal Bayar | datetime-local | max = now (tidak boleh future) |
| Catatan | text | optional |

**Idempotency-Key:** UUID di-generate saat halaman mount, di-pass sebagai header `Idempotency-Key`.

**Submit:** `POST /payments` → tampilkan sukses + alokasi breakdown → link kembali ke contract detail.

**Preview alokasi (optional nice-to-have):** sebelum submit, tampilkan estimasi alokasi denda→bunga→pokok berdasarkan outstanding saat ini. Hanya informatif, bukan constraint.

---

### 2.7 `/contracts/[id]/statement` — Rekening Koran
**Komponen:** tabel mutasi per kontrak secara kronologis.

**Kolom:**
| Kolom | Source |
|---|---|
| Tanggal | `entry_date` |
| Keterangan | `description` |
| Ref Type | `ref_type` (PAYMENT/SETTLEMENT/PENALTY/BILLING) |
| Debit | `debit` sum per entry |
| Kredit | `credit` sum per entry |
| Kontrak Ref | link ke payment/settlement |

**Filter:** date range, ref_type.

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
**Filter:** as-of date (default today).

**Tampilan:** tabel dengan kolom aging bucket:
| Contract No | Customer | Outstanding | Current | 1-30 DPD | 31-60 DPD | 61-90 DPD | >90 DPD |
|---|---|---|---|---|---|---|---|

**Summary baris total** di atas tabel.

---

### 2.10 `/reports/outstanding` — Outstanding Portfolio
**Tampilan:** summary cards + tabel.

**Cards:**
- Total Outstanding
- Total AR (overdue receivable)
- NPL % (kontrak >90 DPD / total outstanding)
- Collection Rate periode ini

**Tabel:** per-contract breakdown dengan outstanding, aging bucket, status.

---

## 3. Konvensi Teknis

### 3.1 Money Formatting
```typescript
// components/shared/MoneyDisplay.tsx
const formatMoney = (amount: number) =>
  new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(amount);
// Output: "Rp 1.573.333"
```

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

API calls melalui Next.js `app/api/` route handlers untuk menghindari CORS issue di development, atau langsung ke BE jika sudah dikonfigurasi CORS. Untuk MVP/portofolio, konfigurasi langsung dengan CORS di BE lebih sederhana.

---

## 5. Deferred / Out of Scope Fase 1–3

- Custom design system / branding.
- Dark mode.
- Mobile responsiveness (tabel bisa horizontal scroll).
- Real-time update (WebSocket/SSE) — refresh manual sudah cukup untuk portofolio.
- Export CSV dari UI — R-4 adalah P2 dan deferred.
- Notifikasi push / email.
