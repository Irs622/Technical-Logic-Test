# Product Requirements Document (PRD)

## 1. Project Overview
- **Project Name:** Kotlin App (`kotlin-app`)
- **Platform:** Android Mobile (API 26+ / Android 8.0 Oreo up to API 34+ / Android 14)
- **Language:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material 3)

## 2. Core Objectives
- Establish a clean, production-ready Android Kotlin architecture following Google's recommended Android Architecture.
- Provide scalable patterns for UI, State Management, Data Persistence, and Networking.
- Maintain high security, performance, and accessibility standards.

## 3. Key Functional Modules
- **Module 1 (Core & Navigation):** App entry point, splash screen, theme switcher, and navigation graph.
- **Module 2 (Feature Screens):** Main dashboard / feature workflow with reactive state flow.
- **Module 3 (Data & Offline Sync):** Repository pattern with local storage and remote API syncing capability.

## 4. Non-Functional Requirements
- **Performance:** Cold start time < 1.5 seconds, 60fps / 120fps smooth scrolling.
- **Offline Support:** App gracefully operates offline with cached data.
- **Security:** Secure storage for sensitive tokens, ProGuard/R8 obfuscation enabled for release builds.

## 5. Modul E-Commerce Simulasi (Flash Sale / Live Commerce)

Aplikasi adalah **simulasi** toko online dengan saldo virtual dan gateway pembayaran tiruan. Modul flash sale menjadi tulang punggung cerita arsitektur; latar belakang di [`incident-live-commerce.md`](incident-live-commerce.md).

### 5.1 Kebutuhan Fungsional
| ID | Kebutuhan |
|---|---|
| FR-01 | Pelanggan dapat menjelajah katalog, mencari, dan memfilter kategori |
| FR-02 | Pelanggan dapat mengelola keranjang dan checkout dengan Saldo Simulasi atau VA tiruan |
| FR-03 | Kampanye promo memiliki alokasi, harga promo, jendela waktu, dan batas 1 unit per pelanggan |
| FR-04 | Pembelian promo membuat reservasi dengan batas waktu bayar; VA kedaluwarsa bersamaan dengan reservasi |
| FR-05 | Bila alokasi habis, pelanggan langsung diberi "stok habis" tanpa VA |
| FR-06 | Pelanggan melihat status order, VA, hitung mundur, dan struk |
| FR-07 | Operasional dapat menjeda kampanye (kill switch) dan melihat rekonsiliasi |
| FR-08 | Pembayaran terlambat setelah reservasi dilepas memicu refund otomatis |
| FR-09 | Mock gateway dapat mensimulasikan sukses, timeout, callback ganda, terlambat, dan hilang |

### 5.2 Kebutuhan Non-Fungsional (Konsistensi & Ketahanan)
| ID | Kebutuhan | Target |
|---|---|---|
| NFR-01 | **Tidak pernah** ada order aktif melebihi alokasi | 0 pelanggaran, ditegakkan oleh constraint DB |
| NFR-02 | Pembelian idempoten terhadap retry/tap ganda | 100% |
| NFR-03 | Deteksi pelanggaran invariant | < 1 menit |
| NFR-04 | Waktu henti dampak lewat kill switch | < 10 detik |
| NFR-05 | Kapasitas lonjakan | ≥ 3× puncak (≥ 1.000 req/detik di `/purchase` pada uji) |
| NFR-06 | Latensi `/purchase` p95 | < 500 ms (tanpa waiting room) |
| NFR-07 | Rekonsiliasi order vs gateway | tiap 1 menit selama kampanye, tiap jam sesudahnya |
| NFR-08 | Semua nominal uang bertipe integer rupiah | tanpa floating point |

### 5.3 Di Luar Lingkup
Uang sungguhan, kartu kredit, logistik nyata, dan integrasi penyedia pembayaran produksi.
