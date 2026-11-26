# Progress Log

Log sprint per sprint (dipindahkan dari README). Rencana lengkap ada di [Sprint Plan](05_SPRINT_PLAN.md)
dan [tasks.md](tasks.md). Catatan implementasi lengkap per task ada di
[tasks-archive.md](tasks-archive.md); entri di sini sengaja ringkas (1–3 baris per item) dan tidak
mengulang detail arsip.

Sprint 0 (fondasi) — **selesai**:

- [x] A1 — Repository setup, CI, Docker Compose, README
- [x] A2 — Injectable Clock, audit foundation, exception envelope
- [x] A3 — Flyway baseline schema, system parameters, COA seed
- [x] A4 — Contract number generator

Sprint 1 (schedule engine core) — **selesai** (golden FLAT/EFFECTIVE test hijau, `gradlew test` hijau):

- [x] B1 — JPA entity Contract/Customer/Asset/Installment + repositories
- [x] B2 — Schedule engine FLAT (pure Java) + golden test
- [x] B3 — Schedule engine EFFECTIVE/anuitas + edge guards (i=0, n=1)
- [x] B4 — Due date calc (31→Feb 28/29, leap year) + test matrix tanggal

Sprint 2 (contract API & activation) — **selesai** (B5; lihat [ADR-006](adr/ADR-006-contract-creation-and-activation.md) & [ADR-007](adr/ADR-007-idempotent-contract-creation.md)):

- [x] B5 — `POST /api/v1/contracts` (create DRAFT, `Idempotency-Key` wajib, customer/asset reuse by identity, duplicate-live-contract guard), `POST /api/v1/contracts/{id}/activate` (generate + persist jadwal tepat sekali, idempotent), `GET /api/v1/contracts` (paged, filter status, search `contract_no`/nama), `GET /api/v1/contracts/{id}`, `GET /api/v1/contracts/{id}/installments`
- [x] V6 — `contract.planned_start_date` (tanggal mulai yang diotor saat drafting; `start_date` effective dipin saat aktivasi, default = `planned_start_date`)
- [x] V7 — `contract.idempotency_key` backstop + partial unique index `(customer_id, asset_id) WHERE status IN ('DRAFT','ACTIVE')` (invariant #18)
- [ ] C-5 — update contract saat DRAFT: **deferred**

Sprint 3 (payment & ledger) — **selesai** (lihat [ADR-008](adr/ADR-008-ledger-posting-semantics.md), [ADR-009](adr/ADR-009-allocation-engine-semantics.md), [ADR-010](adr/ADR-010-payment-contract-seam.md)):

- [x] C1 — Modul `ledger`: entity immutable `journal_entry`/`journal_line` (`ImmutableAuditable`), `LedgerPostingService` (`MANDATORY`, guard satu entry non-reversal per `(ref_type, ref_id)`), vocabulary `LedgerRefType`, akun type-safe `LedgerAccount`, dan jurnal disbursement (`PIUTANG_POKOK`/`KAS`) yang diposting di transaksi aktivasi kontrak
- [x] C2 — Allocation engine murni di modul `payment`: denda → bunga → pokok, angsuran jatuh tempo tertua dahulu (hanya `due_date <= business date`), cap identik trigger V3, satu baris `EXCESS` untuk kelebihan; test 20+ skenario + guard value object (lihat [ADR-009](adr/ADR-009-allocation-engine-semantics.md))
- [x] C3 — `POST /api/v1/payments` (`Idempotency-Key` wajib): alokasi lewat engine C2, resolusi `InstallmentStatus`/`paid_at` di modul `contract` lewat port `InstallmentReceivablePort`, satu jurnal double-entry per pembayaran (`KAS` vs `PIUTANG_DENDA`/`PIUTANG_BUNGA`/`PIUTANG_POKOK`/`TITIPAN_NASABAH`), replay idempotent tanpa double-post; excess hanya dicatat sebagai baris `EXCESS` (credit row milik E3)
- [ ] E3 — credit application (`contract_credit`), E4 — payment void: **deferred** ke Sprint 5

Sprint 4 (penalty, aging & phase-1 close) — **selesai** (ADR-011 … ADR-017, ADR-020; detail:
[tasks-archive.md](tasks-archive.md)):

- [x] C4 — Billing/recognition: port `InstallmentBillingPort` dengan **lazy billing** di transaksi
      `POST /payments` (bunga receivable pada due date — PRD skenario 1 tanpa seeding), satu jurnal `BILLING`
      per installment, dan **maturity auto-close** (`closed_reason=MATURITY`) saat pembayaran reguler terakhir
      melunasi seluruh installment (invariant 17)
- [x] D1 — Denda harian: modul `penalty` (`PenaltyCalculator` murni + `PenaltyAccrual`), step
      `PenaltyAccrualPort.accrueDuePenalty`, seam `InstallmentPenaltyPort`, satu jurnal `PENALTY_ACCRUAL` per
      hari terlambat di luar grace
- [x] T3–T6 — Job harian ShedLock billing → penalty → aging, lazy accrual di jalur payment, retry race
      job-vs-payment (409 `CONCURRENT_MODIFICATION`), step aging `OVERDUE`. Detail: archive (T3–T6)
- [x] T23 — Penyelarasan dependensi Boot 4: ShedLock `7.10.1`, springdoc `3.1.1`, `OpenApiSmokeIT`. Detail: archive
- [x] T7 — RBAC + identitas JWT fail-closed (ADR-015): authority dari claim `roles`, `SYSTEM` ditolak,
      matrix Addendum §3.4 di satu tabel matcher berakhir `denyAll()`, `EndpointRoleMatrixIT`. Detail: archive
- [x] T8/T9 — Aging report (`reporting` read-only) dan contract statement (ledger read port, V11). Detail: archive
- [x] T24–T26, T29, T11 — lifecycle idempotency key sekali-pakai-selamanya (ADR-017), backstop akunting DB
      V12, robustness job harian V13, perbaikan query statement PostgreSQL (X-13), phase-1 exit verification.
      Detail: archive
- [x] Hardening DoS input (ADR-016): `DecimalBounds` menolak money/rate di luar `NUMERIC(19,2)`/`NUMERIC(7,4)`,
      backstop di `CanonicalRequestJson`, aktivasi menolak tanggal efektif lebih tua dari `today − 1 tahun`
- [x] Review eksternal 2026-09-30 (16 temuan, CR-01…CR-16) dipetakan ke task; tabel verdict di archive

Sprint 5 (settlement, credit application & penalty waiver, Epic E) — **selesai** (PR #7–#10; detail: archive):

- [x] T14 — E3 Excess → `contract_credit` + credit-apply/read; V14 cap/status (ADR-009)
- [x] T15 — E5 Penalty waive/reduce + `EffectivePenaltyPort`; V15/V16; fix-pass PR #7 (ADR-019)
- [x] T30 — Schedule + aging read-path lewat effective penalty (ADR-019 D4, temuan PR #7/#8)
- [x] T12 — E1 Settlement quote: modul `settlement`, `SettlementQuoteEngine`, `POST /settlements/quote` (ADR-018)
- [x] T13 — E2 Settlement execution: `POST /settlements`, satu jurnal `SETTLEMENT`, V17 (ADR-018)

Sprint 6 (void, consistency check, write-off, CI gate) — **selesai** (detail: archive):

- [x] T16 — E4 Payment void: `POST /payments/{id}/void`, reversal journal, recompute, V18 (ADR-020, PR #11)
- [x] T17 — F1 Consistency-check job: langkah keempat harian, rekonsiliasi read-only, V19 (Addendum §7.1, PR #12)
- [x] T19 — F5 Write-off: `POST /contracts/{id}/write-off`, `WriteOffEngine`, V20 (PR #13)
- [x] T18 — F4 Idempotency key cleanup job: hapus `idempotency_keys` kedaluwarsa, satu `job_run`, V21 (PR #14)
- [x] T27a — Minimal CI gate: `ci.yml` menjalankan `./gradlew check` (PR #15)
- [x] T31 — Durable void idempotency key: tabel `payment_void_idempotency`, V22; koreksi invariant DB V23
      (void tidak lagi dihitung terhadap cap, ADR-021) (PR #16 + review fixes PR #17)

Sprint 6b (Phase-2 security boundary + CI hardening) — **selesai** (detail: archive):

- [x] T20 — Observability baseline: `CorrelationIdFilter` (`X-Request-Id`), ECS JSON logs, `request_id` pada
      `idempotency_keys` (menutup X-3) (Addendum §10, PR #18; fixes `0c05dfe`)
- [x] T21a — Auth foundation: modul `auth`, `POST /api/v1/auth/login`, Argon2id (BouncyCastle), access token
      HS256 15 menit (`sub` = `app_user.id`, `roles` array string), refresh token opaque disimpan sebagai
      hash SHA-256, decoder menolak `sub` tidak dikenal/tidak aktif (menutup T7-D2), seed demo repeatable
      `R__demo_seed.sql` hanya di profil `demo`; guard kapasitas Argon2 → 503 `AUTHENTICATION_CAPACITY_EXCEEDED`
      (ADR-022; PR #19 + fixes `070e7ad`)
- [x] T21b — Refresh rotation (`POST /api/v1/auth/refresh`, single-use, race-safe via conditional revoke),
      logout idempoten (`POST /api/v1/auth/logout`), brute-force lockout 5 × `PT15M` → 423 `ACCOUNT_LOCKED`
      (login tanpa outer transaction; kegagalan di `REQUIRES_NEW` dan sukses di recorder terpisah, keduanya
      mengunci baris `app_user` sehingga kegagalan paralel terserialisasi), dev-key guard untuk profil
      `prod`/`production` (termasuk `spring.profiles.default`, dibandingkan per byte hasil decode Base64),
      transport refresh token di body (menutup X-14, `AppUser` pindah ke gaya entity proyek → menutup A-14)
      (ADR-022 D10–D13)
- [x] T27 — CI & runtime gates (CR-15): OWASP Dependency-Check (gagal pada CVSS ≥ 7,0, `NVD_API_KEY` wajib
      dan dijaga guard CI), gitleaks CLI ter-pin 8.30.1 atas working tree (`dir`) + seluruh history (`git`,
      termasuk diff merge) dengan allowlist sempit, dan Compose smoke (`up --build` → `/actuator/health` UP →
      kontrak `/v3/api-docs` divalidasi `jq` → `down`); job terpisah, tidak ada scanner yang di-wire ke
      `check`, dan PR dari fork melewati gate dependency secara eksplisit (job pemberitahuan), bukan
      diam-diam dinyatakan lulus. Run acceptance ber-key di CI ditunda selama lock billing akun GitHub;
      implementasi dan verifikasi lokal selesai, jadi task ditutup atas arahan pengguna.

Sprint 7 direncanakan di [tasks.md](tasks.md): T35 (CORS + ADR-023), T32 (G2 shell/login/kontrak),
T33 (G3 pembayaran/jadwal/statement), T34 (G5 demo) — 13 pts. T28 (rotasi kunci PII/HMAC) pindah ke
Sprint 8 sebagai T28a/T28b; payments tab + settlement/write-off UI menjadi T39 di Sprint 8b.

## Sprint 7 — Frontend Core & Demo

- [x] T35 — Browser-client CORS boundary + ADR-023: `CorsConfigurationSource` di security chain (preflight
      dijawab sebelum matriks otorisasi), `AllowedOrigins` memvalidasi properti saat startup (wildcard,
      path, query, default port, dan huruf non-kanonik ditolak), `allowCredentials=false`, `GET,POST`,
      header `Authorization`/`Content-Type`/`Idempotency-Key`/`X-Request-Id` (expose `X-Request-Id`),
      default kosong = tidak ada origin, Compose/test memakai `http://localhost:3000`; ADR-023 mencatat
      boundary browser (token di memory, role hanya UX, tanpa `/me`) dan pin Next.js 16.4.6 + cek OSV
      (branch `t35-cors-adr-023`). Review PR #22 (2026-11-21): Compose memakai default hanya saat variabel
      unset sehingga nilai kosong eksplisit tetap menolak semua origin, dan origin IPv6 divalidasi ke
      bentuk kanonik browser.
- [x] T32 — G2: app shell, login, contract list/detail/create/activate. Scaffold Next.js 16.4.6 (App
      Router, Tailwind v4 + shadcn/ui, TanStack Query, RHF + Zod, Vitest + RTL), Node 24.16.0 via
      `.nvmrc`; token hanya di memori + refresh single-flight dan replay sekali; `/login`, shell
      `(dashboard)` ber-guard, `/contracts` (filter/pencarian/paginasi, DRAFT outstanding "–"),
      `/contracts/new` (Zod, `planned_start_date`, satu `Idempotency-Key` per mount), `/contracts/[id]`
      (header, badge, aktivasi tanpa input tanggal, tab Jadwal/Kredit/Ledger) dan statement minimal;
      job CI `frontend` + allowlist gitleaks untuk output build. Verifikasi: 79 test / 12 file, lint,
      typecheck, build hijau; backend tetap 120 suite / 926 test (branch `t32-nextjs-shell`, stacked di
      `t35-cors-adr-023`)
      - Remediasi review Qodo (2026-11-25): generasi sesi + pembersihan cache saat logout, gating
        Kredit/Ledger untuk `MANAJEMEN`, uang/rate sebagai string desimal eksak (ADR-024) dengan batas
        `NUMERIC(19,2)` pada input, build produksi wajib `NEXT_PUBLIC_API_BASE_URL`, normalisasi telepon
        `0`→`62`, dan fallback `Idempotency-Key` dari `getRandomValues`. Verifikasi: 108 test / 17 file,
        lint/typecheck/build hijau; backend `./gradlew check` 121 suite / 932 test (setelah `main` di-merge)
- [x] T33 — G3: payment, installments, statement, credit. `/contracts/[id]/payments/new` (jumlah + channel
      saja; `paid_at` dari server, tanpa catatan — keputusan 2026-11-25), satu `Idempotency-Key` per mount
      dipakai ulang saat retry, key yang sudah terpakai (`CONFLICT`/`IDEMPOTENCY_KEY_EXPIRED`) meminta
      "Mulai form baru"; tampilan sukses menunjukkan alokasi per angsuran dan peringatan kelebihan sebagai
      kredit; tombol "Catat Pembayaran" (ADMIN, ACTIVE); filter rekening koran `ref_type`/`from`/`to` di API;
      "Terapkan Kredit" (ADMIN, ACTIVE, saldo > 0); invalidasi prefix `["contract", id]` setelah setiap tulis.
      Verifikasi: 178 test / 23 file, lint/typecheck/build hijau; backend `./gradlew check` 121 suite / 932
      test; uji manual di browser terhadap stack Compose (branch `t33-payment-statement-credit`)
      - Remediasi review akhir: hasil pembayaran tetap tampil walau pembayaran itu menutup kontrak
        (maturity), saldo kredit dimuat ulang setelah apply gagal, dialog kredit tidak bisa ditutup saat
        request berjalan, dan filter ref type tetap berlaku saat rentang tanggal terbalik
      - Remediasi review kedua (PR #24): `POST /contracts/{id}/credit/apply` kini wajib `Idempotency-Key`
        (ADR-017 addendum; replay, 409 `CONFLICT`/`IDEMPOTENCY_KEY_EXPIRED`), dialog kredit memakai satu key
        per operasi, invalidasi pembayaran di `onSettled`, rekening koran tidak menampilkan baris lama saat
        memuat, hasil pembayaran tidak menawarkan aksi bila status kontrak belum terkonfirmasi
- [x] T34 — G5: demo script, seed data, README close-out. `scripts/demo.mjs` (Node tanpa dependency)
      menjalankan alur operator lewat API: login `admin` → kontrak B (EFFECTIVE, golden schedule §18.2,
      bayar tepat waktu tanpa denda, replay `Idempotency-Key` yang sama → `payment_no` sama, kredit tetap 0) → kontrak A
      (FLAT, golden schedule §18.1, bayar Rp 2.000.000 → denda dulu, angsuran 1 PAID, angsuran 2
      PARTIALLY_PAID) → rekening koran (Σ debit = Σ credit) → aging → pemisahan role finance/manager.
      Tanggal relatif terhadap hari ini (keputusan 2026-11-25; §18 diamandemen) karena `paid_at` selalu
      jam server; customer dipakai ulang dan `serial_no` aset unik per run sehingga aman dijalankan ulang;
      `--reset` (opt-in) menghapus volume. Job CI `compose-smoke` menjalankan helper test dan script ini.
      README root + frontend: bagian demo, user demo, dan keterbatasan yang diketahui. Verifikasi: demo
      exit 0 dari volume kosong dan saat dijalankan ulang, alur browser cocok, helper 28/28, frontend 203
      test / 23 file, backend 121 suite / 939 test (branch `t34-demo-script`)

**Sprint 7 selesai.** Alur operator utama berjalan di browser dan dapat diulang lewat `node scripts/demo.mjs`.

Sprint 8 (reporting, outbox & rotasi kunci PII) — **direncanakan 2026-11-26**, 14 pts (stretch 1 poin).
Definisi metrik di ADR-026:

- [x] T36 — G1 metrik reporting + `GET /reports/outstanding`. Endpoint baru mengembalikan total
      outstanding (= total portofolio aging), AR, NPL whole-contract, collection rate MTD (kohort due-date,
      hanya alokasi POSTED non-EXCESS) dan settlement cash, sesuai ADR-026. Data dibaca lewat port read-only
      `contract`/`payment`/`settlement`; kalkulator di `reporting` murni. V24 menambah index range
      `installment.due_date` dan `settlement.executed_at`. Verifikasi: backend 126 suite / 968 test, helper
      demo 30/30 (branch `t36-reporting-outstanding`)
- [ ] T37 — G4 halaman laporan aging & outstanding + nama customer di API aging
- [ ] T38 — G6 outbox writer transaksional, enam event domain, publisher berbasis log
- [ ] T28a — PII key ring + envelope `v2` (ADR-025)
- [ ] T28b — re-keying PII/HMAC + kompatibilitas kunci `RequestFingerprint`
