# ADR-014: Lazy Penalty Accrual di Jalur Pembayaran (T4)

- **Status:** Accepted
- **Date:** 2026-09-30
- **Deciders:** Hans (solo developer)
- **Amends:** ADR-012 keputusan 8 dan risiko residual (i); mengimplementasikan ADR-013 keputusan 1

---

## Context

ADR-012 sengaja membatasi D1 pada step accrual yang berdiri sendiri dan menunda lazy trigger di jalur
pembayaran ke D2. Keputusan historis itu menyisakan risiko: pembayaran yang datang sebelum job harian dapat
melunasi installment dan menurunkan base ke nol sebelum denda untuk hari yang sudah berjalan diakui.

T3 sudah menyediakan job harian dengan urutan billing → accrual, tetapi job tidak dapat menjadi satu-satunya
pengaman karena pembayaran dapat datang kapan saja. ADR-013 kemudian menetapkan invariant
accrue-before-resolve: setiap alur yang menurunkan base denda wajib melakukan billing dan accrual melalui
tanggal bisnisnya di transaksi yang sama sebelum resolusi diterapkan. T4 mengimplementasikan invariant itu
untuk `POST /api/v1/payments` tanpa memindahkan kepemilikan data antar-modul.

---

## Decision

1. **Tambahkan edge satu arah `payment → penalty` melalui application interface.**
   `PaymentApplicationService` memanggil `penalty.application.PenaltyAccrualPort`; modul `payment` tidak
   mengetahui repository/entity `penalty` dan dilarang membaca atau menulis `penalty_accrual` secara
   langsung. `penalty` tetap memiliki accrual dan memutakhirkan aggregate `contract` melalui
   `InstallmentPenaltyPort` yang sudah ada.
2. **Seluruh urutan finansial berjalan di dalam supplier idempotensi dan satu transaksi pembayaran:**
   billing → accrual → snapshot receivable → allocation → resolution → jurnal `PAYMENT`. Persistensi baris
   `payment`/`payment_allocation` merupakan bagian dari hasil allocation sebelum resolution, tanpa mengubah
   urutan keputusan bisnis tersebut. `PenaltyAccrualService` memakai propagasi `REQUIRED`, sehingga saat
   dipanggil dari pembayaran ia bergabung dengan transaksi pemanggil; jurnal `BILLING`, jurnal
   `PENALTY_ACCRUAL`, dan jurnal `PAYMENT` berada dalam unit commit yang sama.
3. **Satu tanggal bisnis ditangkap per eksekusi supplier.** `clock.today()` dibaca sekali setelah claim baru
   diterima, lalu nilai yang sama dipakai untuk billing, accrual, dan allocation. Dengan demikian tanggal
   `D` dikenai denda dari base pada awal `D`, sebelum resolusi pembayaran pada `D`. Replay atas key yang
   sudah `COMPLETED` tidak masuk supplier dan tidak menangkap tanggal bisnis baru.
4. **Rollback dan replay mengikuti batas idempotensi yang sama.** Kegagalan setelah claim—termasuk saat
   billing, accrual, snapshot, allocation, persistensi, resolution, atau posting jurnal—me-rollback claim,
   seluruh pengakuan receivable, accrual/jurnal denda, pembayaran/alokasi, perubahan installment, dan jurnal
   pembayaran. Retry setelah rollback adalah eksekusi baru dan dapat memakai key yang sama. Retry identik
   setelah sukses hanya mengembalikan `response_json` tersimpan tanpa mengulangi langkah finansial.
5. **Waterfall tetap `PENALTY → INTEREST → PRINCIPAL → EXCESS`.** Karena snapshot diambil setelah accrual,
   denda yang baru diakui pada transaksi yang sama langsung tersedia untuk allocation. Maturity close baru
   dievaluasi pada resolution setelah denda melalui `D` masuk snapshot dan dibayar.
6. **Tidak ada perubahan skema atau API/OpenAPI.** T4 memakai tabel, constraint, endpoint, envelope, dan
   kontrak request/response yang sudah ada. Tidak ada endpoint job baru dan tidak ada migrasi Flyway.
7. **Race job-versus-payment diterima sementara dan tetap menjadi T5.** Job dan payment dapat bersamaan
   mencoba unique `(installment_id, accrual_date)`. Flush di accrual membuat loser gagal di dalam use case,
   tetapi payment belum mempunyai retry seluruh transaksi dengan fresh claim. T5 tetap TODO untuk retry
   optimistic-lock dan SQLSTATE `23505` dengan transaksi baru; ADR ini tidak mengklaim race tersebut sudah
   selesai atau aman untuk beban konkuren.

---

## Alternatives Considered

1. **Mengandalkan scheduler T3 saja.** Ditolak: urutan eksekusi job dan request tidak dapat dijamin, sehingga
   pembayaran sebelum job masih dapat menghilangkan base hari berjalan.
2. **Membiarkan `payment` menulis `penalty_accrual` atau `installment.penalty_amount` langsung.** Ditolak:
   melanggar kepemilikan data modul `penalty`/`contract`, menggandakan aturan accrual, dan melewati seam yang
   sudah diuji.
3. **Menjalankan accrual di luar supplier idempotensi atau transaksi pembayaran.** Ditolak: request yang
   gagal dapat meninggalkan accrual/jurnal tanpa pembayaran, sedangkan replay dapat menjalankan efek
   finansial kedua kali.
4. **Mengambil snapshot atau melakukan resolution sebelum accrual.** Ditolak: allocation tidak melihat denda
   hari berjalan dan base dapat turun sebelum charge tanggal `D` dihitung, bertentangan dengan ADR-013.
5. **Menunggu T5 sebelum mengaktifkan lazy accrual.** Ditolak: kehilangan denda deterministik ketika payment
   mendahului job lebih mendasar daripada race konkuren. Risiko uniqueness race diterima secara eksplisit
   untuk sementara dan dibatasi sebagai pekerjaan T5 berikutnya.

---

## Consequences

### Positif

- Risiko residual ADR-012 (i) ditutup untuk jalur pembayaran: pembayaran tidak dapat menurunkan base sebelum
  billing dan accrual melalui tanggal bisnisnya selesai.
- `payment` menggunakan satu sumber aturan denda melalui `PenaltyAccrualPort`; tidak ada formula finansial
  atau persistence denda yang diduplikasi.
- Atomicity mencakup claim idempotensi, tiga jenis jurnal, accrual, pembayaran/alokasi, dan perubahan
  installment.
- Nilai teruji: pada fixture periode pertama, 28 hari chargeable × `1.573,33` menghasilkan denda
  `44.053,24` dan total pembayaran `1.617.386,57`; pada hari chargeable pertama denda `1.573,33` membuat
  total pembayaran `1.574.906,66`.

### Negatif dan risiko tersisa

- Request pembayaran terlambat dapat melakukan backfill satu baris accrual dan satu jurnal per hari, sehingga
  latency mengikuti panjang backlog.
- Edge `payment → penalty` menambah coupling sinkron antar-modul, tetapi hanya pada application interface dan
  tetap satu arah.
- Race uniqueness job-versus-payment belum mempunyai retry di sisi payment. Risiko ini diterima hanya sampai
  T5 dan tidak boleh dihapus dari backlog.
- Risiko residual ADR-012 (ii) tetap terbuka: settlement quote/execution wajib melakukan accrual melalui
  tanggalnya sendiri pada T12/T13.

### Security, audit, dan idempotency

- Actor untuk payment-triggered billing, accrual, perubahan installment, dan semua jurnal berasal dari
  authenticated security context yang sama; identitas tidak diambil dari request body. Job T3 tetap memakai
  actor `SYSTEM` pada jalurnya sendiri.
- Tidak ada data sensitif baru yang dicatat atau endpoint/otorisasi baru yang dibuka. Logging tetap hanya
  memakai identifier dan metadata aman, bukan payload atau PII.
- Unique `(installment_id, accrual_date)`, replay stored response, dan satu supplier idempotensi mencegah
  double accrual/double payment pada retry serial. Jaminan retry saat konflik konkuren tetap scope T5.

### Verification

- `./gradlew compileJava compileTestJava`: pass.
- `./gradlew test --tests com.serfira.payment.PaymentApiIT`: 17 tests pass.
- `./gradlew test --rerun`: 413 tests pass.
- `./gradlew check`: pass.

**Referensi:** ADR-010/ADR-011/ADR-012/ADR-013; TS §1/§2.3/§2.5/§4.3; `docs/tasks.md` T4/T5;
`PaymentApplicationService`; `PaymentApiIT`.
