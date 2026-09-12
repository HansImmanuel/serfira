# ADR-003: Injectable Clock sebagai Batas Waktu Aplikasi

- **Status:** Accepted
- **Date:** 2026-09-12
- **Deciders:** Hans (solo developer)

---

## Context

Semua logika finansial Serfira (penalty accrual, jatuh tempo installment, settlement quote,
validasi idempotency, generasi nomor dokumen) bergantung pada waktu. Kalau kode bisnis memanggil
`LocalDate.now()` / `Instant.now()` secara langsung:

- pengujian menjadi non-deterministik (perlu sleep atau flaky di tanggal batas);
- tanggal batas (akhir bulan, Februari, tahun kabisat) tidak bisa diuji secara deterministik;
- jam server yang salah secara diam-diam mengkorupsi perhitungan denda.

---

## Decision

1. Definisikan interface `com.serfira.shared.clock.Clock` (`now()`, `today()`, `instant()`,
   `zone()`), disediakan sebagai bean Spring oleh `ClockConfiguration`
   (`@ConditionalOnMissingBean`, zona bisnis `Asia/Jakarta`).
2. Semua logika bisnis **wajib** menyuntikkan `Clock` ini — dilarang memanggil
   `LocalDate.now()` / `Instant.now()` / `LocalDateTime.now()` di dalam kode bisnis.
3. Untuk pengujian tersedia `FixedClock` yang dapat diubah (`setDate`, `advanceBy`) sehingga
   skenario waktu dapat digerakkan secara deterministik.
4. Bukan `java.time.Clock` standar: butuh metode `today()` dan zona bisnis yang eksplisit agar
   kode panggilan tidak berulang kali memilih zona sendiri.

---

## Consequences

- Semua pengujian berbasis waktu menjadi deterministik (golden test denda dan settlement).
- Wajib disiplin dalam code review: ada satu sumber kebenaran untuk "sekarang".
- Produksi memakai `SystemClock`; tidak ada konfigurasi tambahan.

---

## Alternatives yang Dipertimbangkan

- **`java.time.Clock` standar** — perlu konversi ulang di tiap titik pemanggilan; keputusan zona
  tersebar. Ditolak.
- **Mockito mock untuk waktu** — verbose dan rapuh dibanding `FixedClock`. Ditolak.
- **Static holder / utility `NowProvider`** — sulit diganti per-test Spring context dan
  mendorong hidden dependency. Ditolak.
