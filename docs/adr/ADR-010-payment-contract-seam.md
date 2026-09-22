# ADR-010: Seam `payment → contract` dan Write Path Pembayaran (C3)

- **Status:** Accepted
- **Date:** 2026-09-22
- **Deciders:** Hans (solo developer)

---

## Context

Story C3 (`05_SPRINT_PLAN.md` §5 Sprint 3) adalah write path pembayaran pertama: `POST /api/v1/payments`
menerima uang, mengalokasikannya lewat engine C2, memperbarui state angsuran, dan mem-posting jurnal —
semuanya dalam satu transaksi dan aman di-retry. Engine C2 sudah selesai dan sengaja hanya mengembalikan
angka (ADR-009 keputusan 5): `status`/`paid_at` angsuran tetap milik modul `contract`, dan engine tidak pernah
meng-import `com.serfira.contract.*`. Yang belum diputuskan:

1. **Seam antar-modul.** Modul `payment` butuh membaca receivable per angsuran dan menuliskan hasil resolusi
   ke angsuran — datanya milik modul `contract` (DM §1.4). TS §1 hanya mengizinkan `payment → ledger`;
   `10-architecture` mewajibkan cross-module lewat application-service interface atau kontrak modul eksplisit,
   dan melarang `payment` men-query tabel `installment`/`contract` langsung.
2. **Bentuk seam.** Application service kontrak yang ada (`ContractCommandService`) berorientasi command
   kontrak (create/activate/close); tidak ada operasi "baca receivable" atau "terapkan pembayaran".
3. **Kepemilikan aturan state.** Siapa yang menolak kontrak yang tidak bisa menerima pembayaran, dan siapa
   yang menurunkan `InstallmentStatus` (`PAID` vs `PARTIALLY_PAID`).
4. **`penalty_adjustment`.** Cap denda di ADR-009 keputusan 3 mengurangi Σ `penalty_adjustment`, sementara
   penulis kolom itu (E5, waive) belum ada. Seam C3 harus tetap bisa mencapai invariant 9 tanpa menunggu E5.
5. **Bentuk jurnal pembayaran** (akun debit/kredit, jumlah entry, `ref_type`, `entry_date`) — TS §3
   menetapkan urutan alokasi, bukan bentuk jurnalnya.
6. **Idempotency.** `IdempotencyService` (B5/ADR-007) bersifat generik, tetapi boundary transaksinya harus
   diputuskan: claim idempotency ikut rollback bersama transaksi bisnis, atau tidak.
7. **Status code penolakan.** Kontrak DRAFT/CLOSED/TERMINATED: 409 (vocabulary `contract`) atau 422 baru.
8. **Timezone respons.** `Clock` memakai Asia/Jakarta (TS §2.0), sedangkan Jackson 3 default merender
   `OffsetDateTime` di UTC — terlihat pertama kali pada replay idempotent (respons tersimpan di-parse ulang).

---

## Decision

1. **Seam berupa port interface milik modul `contract`**: `contract.application.InstallmentReceivablePort`
   (`loadReceivableSnapshot(contractId)` + `applyPaymentResolution(contractId, resolvedByInstallment, paidAt)`),
   diimplementasikan `InstallmentReceivableService` di modul yang sama. Presedennya ADR-008 (edge
   `contract → ledger` lewat `LedgerPostingService`). `payment` hanya melihat port + record snapshot
   (`ContractReceivableSnapshot`, `InstallmentReceivable`) dan tidak pernah meng-import entity/repository
   kontrak. Edge `payment → contract (application interface)` diresmikan di TS §1.
2. **Aturan state hidup di pemilik data**, bukan di pemanggil: port yang melempar `ContractNotFoundException`
   (404) dan `ContractStateException` (409 `CONTRACT_STATE_INVALID`) saat kontrak tidak dikenal / tidak ACTIVE,
   dan port pula yang menurunkan `InstallmentStatus` + `paid_at`. `payment` memutuskan **berapa** uang masuk
   ke tiap angsuran, `contract` memutuskan **apa artinya** bagi state angsuran.
3. **Snapshot read-only + mutasi lewat satu operasi terpisah.** `loadReceivableSnapshot` mengembalikan angka
   apa adanya (nama field mengikuti DM §1.4) tanpa filter due date — filter kelayakan tetap milik engine
   (ADR-009 keputusan 1), sehingga tidak ada definisi window kedua.
4. **`penalty_adjustment` dibaca sisi `contract`** sebagai bagian snapshot, sehingga `headroom(PENALTY)`
   ADR-009 (invariant 9) sudah berlaku penuh sejak C3 meskipun penulisnya baru ada di E5. Ini read-edge
   sementara yang dicatat sebagai risiko; saat E5 ada, sum tersebut pindah ke belakang interface modul
   `penalty` tanpa mengubah kontrak port ini.
5. **Satu jurnal per pembayaran**, `LedgerRefType.PAYMENT`, `ref_id = payment.id`, `entry_date = paid_at`:
   **debit `KAS` = jumlah pembayaran**, kredit diagregasi per komponen dengan urutan tetap
   `PIUTANG_DENDA → PIUTANG_BUNGA → PIUTANG_POKOK → TITIPAN_NASABAH` (komponen nol dilewati, ≤ 4 baris kredit),
   setiap baris diberi `contract_id`. Guard "satu entry non-reversal per `(ref_type, ref_id)`" dari C1 menjadi
   proteksi gratis terhadap double-post.
6. **Amount dari klien, `paid_at` dari server.** `paid_at` selalu dari `Clock` (TS §2.0) dan tidak pernah dari
   request body; backdate tidak didukung pada MVP.
7. **Idempotency di dalam transaksi bisnis.** Claim (`Propagation.MANDATORY`) berjalan di dalam
   `@Transactional PaymentApplicationService.createPayment`, jadi operasi yang gagal ikut me-rollback claim
   dan retry dengan key yang sama tetap boleh memperbaiki body. Endpoint tidak pernah menulis `contract_credit`
   (itu E3): kelebihan hanya menjadi baris `EXCESS` + kredit `TITIPAN_NASABAH`.
8. **Status code penolakan mengikuti vocabulary yang sudah ada**: 400 `VALIDATION_ERROR` / idempotency
   (header hilang atau malformed), 404 `CONTRACT_NOT_FOUND`, 409 `CONTRACT_STATE_INVALID` untuk kontrak yang
   bukan ACTIVE, 409 untuk key sama dengan body berbeda. Tidak ada 422 baru. `ErrorCode.PAYMENT_NOT_FOUND`
   ditambahkan sebagai awal vocabulary modul `payment` (dipakai E4/read path).
9. **Respons dirender di timezone bisnis**: `spring.jackson.time-zone=Asia/Jakarta`. Tanpa ini, respons
   pertama (`paid_at` = `…+07:00`) dan respons replay hasil parse `response_json` (`…Z`) tidak identik,
   padahal TS §2.5 menghendaki respons tersimpan dikembalikan apa adanya.

---

## Alternatives Considered

1. **`payment` membaca tabel `installment`/`contract` langsung.** Ditolak: melanggar TS §1 dan
   `10-architecture` (module boundary + "modul tidak boleh query tabel modul lain"), serta membuat aturan
   receivable punya dua tuan.
2. **Menambah method ke `ContractCommandService`.** Ditolak: service itu command-oriented untuk kontrak
   (create/activate/close) dan akan menggelembung dengan vocabulary `payment`; port terpisah membuat
   dependensi eksplisit dan mudah di-mock pada unit test.
3. **Read model bersama (mis. di `reporting`).** Ditolak untuk C3: `reporting` adalah read-only dan dibangun
   di fase berikutnya; memakainya untuk write path akan mencampur read model dengan transaksi finansial.
4. **Engine C2 yang mengembalikan status angsuran.** Ditolak di ADR-009 (keputusan 5) dan tetap ditolak di
   sini: engine harus tetap murni dan tidak mengenal vocabulary `contract.domain`.
5. **Ikut mem-`SUM` `penalty_adjustment` di engine `payment`.** Ditolak: `payment` tidak boleh tahu tabel
   modul `penalty`; sisi `contract` sudah membacanya (dan trigger cap V3 melakukan hal yang sama).
6. **`contract_credit` row dari C3.** Ditolak: kepemilikan credit adalah E3; C3 hanya mencatat baris `EXCESS`
   dan akun `TITIPAN_NASABAH`, sehingga tidak ada sumber kebenaran ganda.
7. **Claim idempotency di luar transaksi bisnis (REQUIRES_NEW).** Ditolak: retry setelah kegagalan validasi
   akan mengembalikan respons gagal selamanya untuk key tersebut.
8. **422 untuk kontrak non-ACTIVE.** Ditolak: tidak ada preseden 422 di codebase; 409 `CONTRACT_STATE_INVALID`
   sudah dipakai activate/close.
9. **Membersihkan idempotency key kedaluwarsa di C3.** Ditunda ke F4 (story "Idempotency key TTL + cleanup
   job"), sesuai pembagian scope sprint; TS §2.5 hanya diperbaiki referensinya.

---

## Consequences

- `payment` dan `contract` tetap dapat di-split tanpa membongkar kode: satu port interface adalah seam-nya.
- Unit test engine C2 tidak terpengaruh (port hanya disentuh lapisan application).
- Satu pembayaran = satu jurnal balance, dengan `contract_id` pada setiap baris sehingga rekonsiliasi
  per-kontrak tidak perlu join ke `payment`.
- `paid_at` angsuran adalah instant pembayaran yang **pertama** menyelesaikan angsuran, bukan yang terakhir;
  angsuran yang sudah `SETTLED`/`WRITTEN_OFF` tidak pernah diturunkan statusnya oleh pembayaran reguler.
- Σ `payment_allocation` aktif per angsuran dihitung dari baris `POSTED` (tabel `payment_allocation` append-only,
  jadi tidak butuh kolom void pada MVP); saat E4 memperkenalkan baris `VOIDED`, filter status harus ditinjau
  ulang bersama trigger V3 yang saat ini menghitung **semua** baris.
- Jurnal pembayaran belum memisahkan pokok/bunga pada akun pendapatan: pengakuan pendapatan (TS §5, C4) tetap
  memakai jurnal billing-nya sendiri.
- Port C3 dipakai ulang E2/E4/E5 (settlement, void, credit application) sebagai jalur resolusi angsuran,
  sehingga semantik `paid_at`/status konsisten di seluruh modul.

---

## References

- `docs/01_PRD.md` §3 (P-1–P-4, skenario 1–4)
- `docs/02_TECH_SPEC.md` §1, §1.1, §2.0, §2.2, §2.3, §2.5, §3
- `docs/03_DOMAIN_MODEL.md` §1.4 (receivable), §1.12 (COA)
- `docs/04_GAPS_ADDENDUM.md` §2, §12, §16.5
- `docs/05_SPRINT_PLAN.md` §5 Sprint 3 (C3), F4
- ADR-007 (idempotent request handling), ADR-008 (ledger posting semantics),
  ADR-009 (allocation engine semantics)