# Sprint Plan — Multifinance Loan Servicing Core System

Versi 0.3 (final pre-code review). Melengkapi: `01_PRD.md`, `02_TECH_SPEC.md`, `03_DOMAIN_MODEL.md`, `04_GAPS_ADDENDUM.md`.

---

## 1. Asumsi

- **Tim:** 1 developer (solo), part-time ±10–15 jam/minggu.
- **Sprint:** 2 minggu per sprint. Sprint 0 = setup, bisa dikerjakan dalam 1 minggu.
- **Story points:** Fibonacci (1, 2, 3, 5, 8). 1 point ≈ 2–3 jam kerja efektif.
- **Velocity target:** 8–13 points/sprint. Jangan ambisius — sistem finansial lambat karena testing-nya berat, dan itu bagus.
- **Definisi fase:** Sprint 0–4 = Fase 1 MVP. Sprint 5–6 = Fase 2. Sprint 7–8 = Fase 3. Sprint 9+ = deferred/polish.

## 2. Definition of Ready (DoR)
Story boleh masuk sprint kalau:
- Requirement ID dari PRD/addendum jelas (C-x, S-x, P-x, dst).
- Acceptance criteria tertulis dan bisa diverifikasi otomatis/manual.
- Dependensi story lain sudah diidentifikasi.

## 3. Definition of Done (DoD)
Story baru "done" kalau **semua** terpenuhi:
- [ ] Kode + migration Flyway ter-review (self-review dengan checklist).
- [ ] Unit test engine (pure Java) ditulis untuk semua cabang logic baru.
- [ ] Integration test (Testcontainers) untuk write path yang menyentuh DB.
- [ ] Invariant terkait ter-test (lihat domain model §3) — minimal yang relevan dengan story.
- [ ] Golden test masih hijau (regression).
- [ ] OpenAPI annotation updated, `gradlew test` hijau di CI.
- [ ] `created_by` terisi dari JWT principal untuk request user atau principal `SYSTEM` untuk job.

## 4. Backlog Ringkas (Epic → Story)

### Epic A — Foundation
| Story | Pts | Ref |
|---|---|---|
| A1: Repo setup + CI + docker-compose + README skeleton + `docs/adr/` folder | 3 | Addendum §11 |
| A2: Clock bean (Asia/Jakarta, injectable) + audit fields base + exception envelope | 2 | Gap: timezone |
| A3: Flyway baseline: semua tabel domain model + system_parameter (+ seed values §1.3) + COA seed + outbox_events tabel | 3 | DM §1, Addendum §1.2, §1.3, L-4 |
| A4: Business document number generator (counter table, monthly uniqueness, retry) | 1 | Gap: contract_no |

### Epic B — Contract & Schedule (Fase 1)
| Story | Pts | Ref |
|---|---|---|
| B1: Entity JPA Contract/Customer/Asset/Installment + repositories | 3 | DM §1.1–1.4, C-1, C-4 |
| B2: Schedule engine FLAT (pure Java) + golden test | 3 | TS §4.1, DM §4, S-1 |
| B3: Schedule engine EFFECTIVE/anuitas + edge guards (i=0, n=1) | 3 | TS §4.2, S-2 |
| B4: Due date calc (31→Feb 28/29, leap year) + test matrix tanggal | 3 | DM §4, S-3 |
| B5: API create/activate + contract list/detail (generate jadwal, idempotent) | 3 | C-1, C-2, C-3, C-4, S-4 |

### Epic C — Payment & Ledger (Fase 1)
| Story | Pts | Ref |
|---|---|---|
| C1: Ledger entity + posting service + balance invariant test | 5 | TS §3, L-1, L-2 |
| C2: Allocation engine (denda→bunga→pokok, oldest first) + 20 skenario test | 5 | P-2, P-3 |
| C3: API POST /payments + Idempotency-Key + double-post test | 5 | P-1, TS §2.5 |
| C4: Posting rule + billing/recognition: terima angsuran, ~~aktivasi/disburse~~, bunga due-date | 2 | TS §3, Addendum §12, L-1 |
| C5: Statement endpoint (rekening koran) | 2 | DM §2 |

### Epic D — Penalty & Aging (Fase 1)
| Story | Pts | Ref |
|---|---|---|
| D1: Penalty calc pure function + PenaltyAccrual idempotent | 3 | D-1, D-3, Addendum §6 |
| D2: Job harian (shedlock) denda + aging + job_run logging | 3 | D-1, D-2, Addendum §10 |
| D3: Aging report query + endpoint | 2 | D-2 |

### Epic E — Settlement, Void, Credit (Fase 2)
| Story | Pts | Ref |
|---|---|---|
| E1: Settlement quote snapshot (ACT/30, expiry, contract version) + golden test | 3 | E-1, E-3, Addendum §8, §13 |
| E2: Settlement transaction + allocation + credit consume → CLOSED + jurnal | 3 | E-2, Addendum §13 |
| E3: Excess payment → contract_credit + TITIPAN_NASABAH + apply endpoint | 3 | P-4, Addendum §2 |
| E4: Void payment → reversal jurnal + recalc denda synchronous | 5 | P-5, Addendum §6 |
| E5: Waive denda + posting rule | 2 | D-4 |

### Epic F — Consistency & Auth (Fase 2)
| Story | Pts | Ref |
|---|---|---|
| F1: Consistency check job + reconciliation_exception + resolve endpoint | 3 | L-3, Addendum §7 |
| F2: Auth: app_user, login/refresh/logout, JWT, brute-force lockout | 5 | Addendum §3 + gap |
| F3: RBAC enforcement di endpoint + created_by dari JWT | 2 | Addendum §3.3 |
| F4: Idempotency key TTL + cleanup job | 1 | Gap: cleanup |
| F5: Write-off endpoint + installment WRITTEN_OFF + jurnal BIAYA_PENGHAPUSAN_PIUTANG | 2 | W-1, Addendum §4 |

### Epic G — Reporting, Frontend, Polish (Fase 3)
| Story | Pts | Ref |
|---|---|---|
| G1: Dashboard metrics: outstanding, AR, NPL, collection rate | 3 | R-1 |
| G2: FE: login + contract list/detail + form create + activate | 5 | Addendum §15 |
| G3: FE: payment form + installment table + statement | 5 | Addendum §15 |
| G4: FE: reports (aging, outstanding) | 3 | R-1, R-2 |
| G5: Demo script (curl) + seed data + README final | 2 | Gap: demo script |
| G6: Outbox events table + publisher (tanpa Kafka dulu, log-based) | 2 | TS §2.6 |

**Total committed backlog: ±102 points = Sprint 0–8b. Dengan velocity 8–13 points/sprint, target realistis adalah ±9–10 sprint part-time (Sprint 6 dipecah menjadi 6 + 6b untuk menghindari sprint >13 pts). Beberapa sprint sengaja sedikit di atas target karena testing finansial berat dan dapat dipecah saat planning.**

---

## 5. Rencana per Sprint

### Sprint 0 — Setup (1 minggu)
**Goal:** `gradlew bootRun` + PostgreSQL via docker-compose jalan, CI hijau, repo presentable.
- A1 (3), A2 (2), A3 (3), A4 (1) = **9 pts**
- **Exit:** repo publik, CI passing, README skeleton menjelaskan arsitektur + cara run; `docs/adr/ADR-001-modular-monolith.md` + `ADR-002-double-entry-ledger.md` tertulis.

### Sprint 1 — Schedule Engine Core
**Goal:** Angka jadwal FLAT & EFFECTIVE **benar** dan ter-lock oleh golden test.
- B1 (3), B2 (3), B3 (3), B4 (3) = **12 pts**
- **Exit:** golden test hijau + matrix tanggal 20 case lolos. **Belum ada API.**
- ⚠️ Jangan lanjut ke business write path sebelum engine benar.

### Sprint 2 — Contract API & Activation
**Goal:** Kontrak bisa dibuat dan diaktivasi; jadwal tersimpan konsisten. Update saat DRAFT (`C-5`) tetap deferred bila velocity tidak cukup.
- B5 (3) + integration test activation idempotency + invariant #4 (2) = **5 pts**
- **Exit:** PRD skenario #1 bisa dijalankan end-to-end via API + DB check. `C-5` hanya dianggap done bila story update DRAFT benar-benar diimplementasikan. Final installment/maturity close rule sudah tercakup pada payment resolution path.
- **Status B5 — selesai (lihat ADR-006 & ADR-007):**
  - `POST /contracts` (DRAFT create, header `Idempotency-Key` wajib), `POST /contracts/{id}/activate` (generate jadwal tepat sekali, idempotent by state machine), `GET /contracts` (paged + filter + search), `GET /contracts/{id}`, `GET /contracts/{id}/installments`.
  - Migration `V6` (`planned_start_date`) + `V7` (`idempotency_key` backstop, partial unique `uq_contract_live_asset` — invariant #18: maksimal satu kontrak live per customer+asset).
  - `C-5` (update DRAFT) **tetap deferred** — dibutuhkan untuk membetulkan DRAFT yang salah tanpa campur tangan ops.

### Sprint 3 — Payment & Ledger
**Goal:** Uang masuk tercatat benar, allocation benar, double-submit aman.
- C1 (5), C2 (5), C3 (5) = **15 pts**
- **Exit:** skenario payment normal/partial + idempotency lolos; late-payment allocation dapat diuji dengan penalty yang sudah di-recognize.
- **Status C1 — selesai (lihat ADR-008):**
  - Modul `ledger` (domain/application/infrastructure): `JournalEntry`/`JournalLine` immutable di atas `ImmutableAuditable` baru, `LedgerPosting`/`LedgerPostingLine` (invariant bentuk + Σ debit = Σ kredit di domain), `LedgerRefType`, `LedgerAccount` (10 akun DM §1.12), `LedgerPostingService`.
  - Posting `MANDATORY` di transaksi pemanggil + guard satu entry non-reversal per `(ref_type, ref_id)`; reversal (`reversal_of_id`) dikecualikan untuk E4.
  - **Jurnal disbursement diposting saat aktivasi kontrak** (`PIUTANG_POKOK` debit / `KAS` kredit, TS §3) sehingga invariant 13 tidak lahir dengan exception; edge `contract → ledger` diresmikan di TS §1. `aktivasi/disburse` karena itu dicoret dari C4.
  - `entry_date` (tanggal bisnis event) vs `posted_at` (Clock) final saat insert; `journal_line.entry_date` denormalisasi.
  - **Tanpa migrasi** (skema V1 sudah lengkap) dan tanpa unique index baru — guard duplicate ada di aplikasi karena setiap event sudah punya guard DB sendiri (alasan lengkap di ADR-008).
  - Test: 38 test baru (unit domain: bentuk/balance/amount/normalisasi akun; IT Testcontainers: commit-time balance, rollback bersama, posting tanpa transaksi, guard duplicate, reversal, actor dari `AuditContext`, parity COA, FK backstop, aktivasi end-to-end).
- **Status C2 — selesai (lihat ADR-009):**
  - Modul `payment` pertama: `com.serfira.payment.domain.AllocationType` + engine murni di `domain/allocation` (`PaymentAllocationEngine` beserta value object `InstallmentAllocationInput`, `AllocationLine`, `AllocationResult`). Tanpa Spring/JPA/DB — `businessDate` adalah parameter, jadi deterministik di test.
  - **Window kelayakan = angsuran yang sudah jatuh tempo** (`due_date <= business date`); angsuran masa depan tidak pernah disentuh, sisanya menjadi satu baris `EXCESS` (credit nasabah). Pembayaran sebelum due date pertama = EXCESS penuh (keputusan produk, Addendum §16.5).
  - Waterfall per angsuran tertua dahulu (`due_date`, tie-break `period_no`), di dalamnya `PENALTY → INTEREST → PRINCIPAL`; cap per komponen identik trigger V3 + batas `recognized_total − resolved_amount` (invariant 3), sehingga alokasi yang lolos engine tidak gagal di COMMIT.
  - `Σ baris = payment amount` (invariant 6) dan `EXCESS ⟺ installmentRef null` dijaga di domain; baris bernilai nol tidak pernah dibuat. Tanpa rounding (skala 2, `setRoundingMode.UNNECESSARY`).
  - Engine hanya mengembalikan angka: `status`/`paid_at` installment tetap milik modul `contract` (C3).
  - **Tanpa migrasi** (DDL `payment`/`payment_allocation` sudah ada di V1; trigger V3 sudah ada) dan tanpa perubahan API/OpenAPI.
  - Test: unit test murni (`PaymentAllocationEngineTest` 20+ skenario termasuk golden angka demo Addendum §18.1, plus test value object/guard). Sisi DB invariant 6 sudah diuji `AccountingInvariantsIT`; write path (entity, transaksi, idempotency, jurnal) adalah C3.
  - ⚠️ Catatan untuk C3: `recognized_interest_amount`/`penalty_amount` harus di-seed lewat SQL di IT sampai C4 (billing/recognition) ada — bunga baru receivable setelah di-bill pada due date.

### Sprint 4 — Penalty, Aging & Phase-1 Close
**Goal:** Sistem hidup dengan denda dan aging harian yang bisa diaudit.
- C4 (3), C5 (2), D1 (3), D2 (3), D3 (2) = **13 pts**
- ⚠️ C4 (billing/recognition) dinaikkan ke 3 pts: billing step menyentuh scheduler + ledger posting + recognized_interest_amount, bukan sekedar posting rule.
- **Exit:** automated billing/recognition + penalty + aging + `job_run` berjalan; final regular payment dapat auto-close contract; **Fase 1 selesai**.

### Sprint 5 — Settlement, Credit & Waive
**Goal:** Settlement semantics dan excess credit benar sebelum void/auth.
- E1 (3), E2 (3), E3 (3), E5 (2) = **11 pts**
- **Exit:** settlement quote/execution balance, future interest tidak double-count, credit application/consume ter-audit, settlement tidak menutup contract dengan unresolved credit, waive menghasilkan adjustment + journal.

### Sprint 6 — Void, Consistency & Write-off
**Goal:** Audit trail lengkap — void dengan penalty recalc synchronous, tiga consistency check, dan write-off dengan jurnal.
- E4 (5), F1 (3), F4 (1), F5 (2) = **11 pts**
- **Exit:** void synchronous penalty recalc benar; tiga reconciliation check jalan; write-off journal dan installment WRITTEN_OFF benar.

### Sprint 6b — Auth & RBAC
**Goal:** Security boundary jelas sebelum frontend dibangun.
- F2 (5), F3 (2) = **7 pts**
- ⚠️ Auth dipecah ke sprint tersendiri karena F2 (login/refresh/logout/brute-force) dan F3 (RBAC enforcement di semua endpoint) masing-masing non-trivial dan Sprint 6 asli sudah 16 pts.
- **Exit:** login/refresh/logout berjalan; RBAC enforcement default-deny aktif sesuai endpoint-to-role matrix (Addendum §3.4); `created_by` terisi dari JWT principal; **Fase 2 selesai**.

### Sprint 7 — Frontend Core & Demo
**Goal:** Flow utama bisa dipakai dari browser.
- G2 (5), G3 (5), G5 (2) = **12 pts**
- **Exit:** login → contract → payment → statement berjalan; demo script repeatable.

### Sprint 8 — Reporting & Outbox
**Goal:** Reporting manajemen dan event foundation selesai.
- G1 (3), G4 (3), G6 (2) = **8 pts**
- **Exit:** dashboard metrics punya definisi tertulis, aging/outstanding report siap, outbox publisher log-based berjalan; **Fase 3 selesai**.

### Deferred backlog — tidak dihitung dalam 100 points
- PRD C-5: update contract hanya saat DRAFT (bila tidak sempat di Sprint 2).
- R-3: cash-in report per periode/channel.
- R-4: CSV export.
- P-6: payment gateway webhook stub.
- S-5: restructuring/reschedule.

> Catatan naming: `C5` pada Epic C adalah **Statement endpoint**, sedangkan `C-5` pada PRD adalah **update contract**. Penamaan ini sengaja dibedakan dengan format hyphen agar tidak lagi ambigu.

## 6. Risiko & Mitigasi

| Risiko | Dampak | Mitigasi |
|---|---|---|
| Scope creep di frontend (kebawa bikin UI keren) | Backend setengah jadi | FE dibatasi G2–G4, no custom design system, pakai shadcn/ui apa adanya |
| Engine schedule salah terlambat ketahuan | Refactor besar | Sprint 1 lock golden test, tidak lanjut sebelum hijau |
| Over-engineering (Kafka, microservices) | Sprint bocor | Fase 3+ saja; ADR tulis alasannya |
| Waktu part-time tersedot kerjaan kantor | Velocity drop | Sprint bisa di-skip tanpa rusak (urutan story sudah dependency-safe) |

## 7. Design Gates sebelum coding
1. Finalisasi rate convention, due-date rule, settlement formula, credit application/closing policy, receivable definition, dan reconciliation checks.
2. Flyway schema harus merepresentasikan immutable journal + append-only adjustments + settlement quote/settlement allocation + installment resolution states sebelum payment/settlement coding dimulai.
3. Golden schedule test dan allocation test harus punya expected values yang eksplisit.
4. Billing/penalty recognition ke ledger, quote stale detection, settlement transaction record, dan PII uniqueness model harus sudah disepakati sebelum payment/settlement coding.

## 8. Aturan Main (buat diri sendiri)
1. **Golden test hijau = gate.** Tidak ada story berikutnya sebelum engine benar.
2. **1 ADR per keputusan arsitektur**, ditulis maksimal 15 menit setelah keputusan diambil.
3. **Demo script di-update tiap akhir sprint** — bukan nanti pas mau showcase.
4. Tiap sprint tutup dengan: update README progress + self-retro 15 menit (apa yang molor, kenapa).
