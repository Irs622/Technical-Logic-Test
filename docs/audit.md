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
| INV-01 | Alokasi memakai satu `UPDATE` bersyarat; tidak ada pola baca-lalu-tulis | Tinjauan kode + uji paralel | PASSED — `InventoryTest` (5.000 pembeli paralel → tepat 100 order aktif); `/sim/load` 250.000 pembeli → 100 |
| INV-02 | `CHECK (reserved + sold <= allocation)` aktif di MySQL ≥ 8.0.16 | Hasil `SHOW CREATE TABLE` + uji pelanggaran | PASSED di H2 (uji pelanggaran CHECK). **MySQL 8 belum diuji** |
| INV-03 | Reservasi + order + ledger dalam satu transaksi; tidak ada panggilan gateway di dalamnya | Tinjauan kode | PASSED — tinjauan kode `Inventory.reserve`; panggilan gateway dilakukan setelah commit |
| INV-04 | `UNIQUE (user_id, campaign_id, active_key)` menolak order aktif ganda | Uji integrasi | PASSED — `InventoryTest` + `HttpTest` (409 ALREADY_PURCHASED) |
| INV-05 | `Idempotency-Key` menghasilkan respons identik pada retry | Uji integrasi | PASSED — `InventoryTest`, `HttpTest` (201 lalu 200, order sama) |
| INV-06 | Pelepasan reservasi idempoten (`uq_once` di ledger) | Uji worker ganda paralel | PASSED — 8 worker paralel melepas 5 reservasi tepat 5 kali |
| INV-07 | Callback ganda/terlambat/hilang ditangani | Uji dengan mock gateway (`duplicate`, `late`, `lost`) | PASSED — callback ganda, tanda tangan salah, hilang→rekonsiliasi, terlambat→pulih/refund |
| INV-08 | VA kedaluwarsa = `reserved_until` | Uji integrasi | PASSED — `InventoryTest` (expiry VA = reserved_until) |
| INV-09 | Kill switch efektif < 10 detik dan tercatat di audit | Uji manual + log | PASSED sebagian — pause berlaku seketika (diuji) dan tercatat di `campaign_audit`; belum ada pengukuran waktu |
| INV-10 | Alert invariant terpasang dan pernah diuji | Bukti alert uji | PARSIAL — monitor + auto-pause teruji; alert hanya ke log, belum ke on-call |
| INV-11 | Concurrency test: ≥ 5.000 request paralel, alokasi 100 → tepat 100 order aktif | Laporan uji | PASSED — lihat INV-01 |
| INV-12 | Load test ≥ 3× puncak, termasuk timeout gateway 30% | Laporan uji | OPEN — beban 250.000 in-process lulus, tetapi belum lewat HTTP ≥3× puncak dan belum dengan gateway timeout 30% |
| INV-13 | Runbook insiden & prosedur kompensasi disetujui bisnis/keuangan/legal | Dokumen | OPEN — butuh persetujuan bisnis/keuangan/legal |
| INV-14 | Klien memakai key yang sama saat retry; tidak menaikkan status order sendiri | Uji unit + tinjauan | OPEN — diimplementasikan (`Settings.purchaseKey`, `ShopRepository.purchase`) tetapi belum ada uji otomatis di sisi Android |

Syarat rilis fitur flash sale: **INV-01 s/d INV-14 berstatus PASSED**.

### Audit Log
- **[INC-001] Studi kasus oversell** ditambahkan sebagai dasar desain; seluruh INV-xx dibuka.
- **[INC-002] Implementasi simulasi** (`server/`, `app/`): 26 test server lulus; reproduksi insiden — mode lama 250.000 pembeli → 18.409 VA untuk alokasi 100 (oversell 18.309), mode aman → tepat 100.
- Catatan jujur: database simulasi adalah H2 (mode MySQL), bukan MySQL 8; perilaku lock dan CHECK perlu diverifikasi ulang di MySQL sebelum dipakai sebagai bukti produksi. Waiting room (`202 QUEUED`) belum diimplementasikan di simulasi.
