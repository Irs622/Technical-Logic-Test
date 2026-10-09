# Audit & Quality Assurance Report

## Initial Project Audit Status: PASSED (GREEN)

| Category | Status | Notes |
| :--- | :--- | :--- |
| **Security** | PASSED | Cleartext traffic disabled by default; no hardcoded credentials. |
| **Architecture** | PASSED | Clean Architecture & UDF structure initialized. |
| **Performance** | PASSED | Baseline Jetpack Compose structure without redundant recompositions. |
| **Dependencies** | PASSED | Modern AndroidX, Compose BOM, and Kotlin versions targeted. |

## Audit Log
- **[INITIAL-001] Setup:** Initial project scaffold created with clean structure conforming to standard guidelines.

## Audit Khusus: Konsistensi Stok & Pembayaran (Flash Sale)

Dipicu oleh studi kasus [`incident-live-commerce.md`](incident-live-commerce.md). Status diperbarui berdasarkan implementasi simulasi di `server/` (H2 mode MySQL) dan test di `server/src/test`.

| ID | Pemeriksaan | Bukti yang diminta | Status |
|---|---|---|---|
| INV-01 | Alokasi memakai satu `UPDATE` bersyarat; tidak ada pola baca-lalu-tulis | Tinjauan kode + uji paralel | PASSED — `InventoryTest` (5.000 paralel → 100) di H2 **dan MySQL 8**; beban 250.000 pembeli → 100 di keduanya |
| INV-02 | `CHECK (reserved + sold <= allocation)` aktif di MySQL ≥ 8.0.16 | Hasil `SHOW CREATE TABLE` + uji pelanggaran | PASSED di H2 **dan MySQL 8.0.46** (uji pelanggaran `CHECK` lulus di keduanya) |
| INV-03 | Reservasi + order + ledger dalam satu transaksi; tidak ada panggilan gateway di dalamnya | Tinjauan kode | PASSED — tinjauan kode `Inventory.reserve`; panggilan gateway dilakukan setelah commit; urutan lock `campaign → orders` konsisten |
| INV-04 | `UNIQUE (user_id, campaign_id, active_key)` menolak order aktif ganda | Uji integrasi | PASSED — `InventoryTest` + `HttpTest` di H2 dan MySQL |
| INV-05 | `Idempotency-Key` menghasilkan respons identik pada retry | Uji integrasi | PASSED — `InventoryTest`, `HttpTest` (201 lalu 200, order sama) di H2 dan MySQL |
| INV-06 | Pelepasan reservasi idempoten (`uq_once` di ledger) | Uji worker ganda paralel | PASSED — 8 worker paralel melepas 5 reservasi tepat 5 kali (H2 dan MySQL) |
| INV-07 | Callback ganda/terlambat/hilang ditangani | Uji dengan mock gateway (`duplicate`, `late`, `lost`) | PASSED — callback ganda, tanda tangan salah, hilang→rekonsiliasi, terlambat→pulih/refund (H2 dan MySQL) |
| INV-08 | VA kedaluwarsa = `reserved_until` | Uji integrasi | PASSED — `InventoryTest` (expiry VA = reserved_until) |
| INV-09 | Kill switch efektif < 10 detik dan tercatat di audit | Uji manual + log | PASSED sebagian — pause berlaku seketika (diuji) dan tercatat di `campaign_audit`; `freeze-all` menjeda 801 kampanye; belum ada pengukuran waktu |
| INV-10 | Alert invariant terpasang dan pernah diuji | Bukti alert uji | PASSED sebagian — monitor + auto-pause teruji dan alert sampai ke webhook (penerima uji lokal); belum tersambung ke on-call sungguhan |
| INV-11 | Concurrency test: ≥ 5.000 request paralel, alokasi 100 → tepat 100 order aktif | Laporan uji | PASSED — lihat INV-01 |
| INV-12 | Load test ≥ 3× puncak, termasuk timeout gateway 30% | Laporan uji | PARSIAL — 3.000 pembeli lewat HTTP asli + gateway gagal 30% → tepat 100 order, 100 VA; beban 250.000 in-process. Belum ≥3× puncak sebenarnya lewat HTTP |
| INV-13 | Runbook insiden & prosedur kompensasi disetujui bisnis/keuangan/legal | Dokumen | OPEN — rencana & eksekusi kompensasi A/B/C ada sebagai alat, tetapi persetujuan bisnis/keuangan/legal tetap di luar engineering |
| INV-14 | Klien memakai key yang sama saat retry; tidak menaikkan status order sendiri | Uji unit + tinjauan | PASSED — 7 unit test Android (`PurchaseIdempotencyTest`): retry 503/koneksi putus memakai key yang sama, key dibuang hanya setelah hasil final; UI diverifikasi manual di emulator |

Syarat rilis fitur flash sale: **INV-01 s/d INV-14 berstatus PASSED**. Saat ini 10 PASSED penuh, 2 PASSED sebagian (INV-09, INV-10), INV-12 parsial, INV-13 OPEN (keputusan bisnis).

### Audit Log
- **[INC-001] Studi kasus oversell** ditambahkan sebagai dasar desain; seluruh INV-xx dibuka.
- **[INC-002] Implementasi simulasi** (`server/`, `app/`): 26 test server lulus; reproduksi insiden — mode lama 250.000 pembeli → 18.409 VA untuk alokasi 100 (oversell 18.309), mode aman → tepat 100.
- Catatan jujur: database simulasi adalah H2 (mode MySQL), bukan MySQL 8; perilaku lock dan CHECK perlu diverifikasi ulang di MySQL sebelum dipakai sebagai bukti produksi. Waiting room (`202 QUEUED`) belum diimplementasikan di simulasi.
- **[INC-003] Penutupan celah** — waiting room (`Admission`), harga normal 250 unit gudang, 800 kampanye + eksposur, `freeze-all`, rencana/eksekusi kompensasi A/B/C, alert webhook, gateway FLAKY, dan perbaikan urutan lock (`campaign → orders`).
- **[INC-004] Verifikasi MySQL 8.0.46 (Docker)** — seluruh 35 test server lulus terhadap MySQL (`MYSQL_URL`); beban 250.000: alur lama 15.393 VA (oversell 15.293), alur baru tepat 100. Catatan: sempat salah mengira lulus karena hasil test lama di-cache Gradle; diperbaiki dengan `inputs.property("mysqlUrl")` dan `--rerun`.
- **[BUG-001]** Retry dengan `Idempotency-Key` yang sama setelah VA gagal pasti mengembalikan order yang sudah `CANCELLED`. Diperbaiki: key dibebaskan saat reservasi dibatalkan (`Inventory.ensureVa`), diuji.
- **[BUG-002]** `lifecycle 2.8.0` + Compose UI 1.6.x membuat app crash (`LocalLifecycleOwner not present`) di emulator; diturunkan ke 2.7.0 (ketahuan hanya setelah dijalankan di emulator).
