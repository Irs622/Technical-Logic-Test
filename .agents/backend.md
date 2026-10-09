# Backend & Data Architecture Agent Specification

## Role & Responsibilities
- Local Data Persistence (Room Database, SQLite, DataStore)
- Remote API Integration & Networking Layer (Retrofit / Ktor Client, OkHttp)
- Authentication & Token Management (EncryptedSharedPreferences, OAuth2 / JWT Flow)
- Background Jobs & Syncing (WorkManager)
- Data Caching & Offline-First Repository Pattern
- Backend API Specifications & Mock Contracts

## Guidelines
- Repository acts as Single Source of Truth (SSOT).
- Enforce Coroutines & Kotlin Flow for asynchronous data streams.
- Handle network error states, timeouts, and offline scenarios gracefully.
- Secure secrets using `BuildConfig` or Android Keystore; never commit raw tokens.
- Document schemas, endpoints, and storage models in `docs/backend.md`, `docs/api.md`, and `docs/database.md`.

## Aturan Konsistensi (wajib untuk fitur stok/pembayaran)
Berlaku untuk server maupun klien; latar belakang di `docs/incident-live-commerce.md`.
- Dilarang pola baca-lalu-tulis pada stok. Gunakan `UPDATE ... WHERE reserved + sold < allocation` dan putuskan dari `affected_rows`.
- Invariant harus ditegakkan database (`CHECK`, `UNIQUE`), bukan hanya kode.
- Reservasi + order + ledger dalam satu transaksi singkat; panggilan gateway di luar transaksi.
- Semua operasi yang bisa diulang harus idempoten; klien memakai `Idempotency-Key` yang sama saat retry.
- Ubah status order hanya lewat compare-and-set.
- Klien tidak pernah menjadi otoritas stok, harga, atau status pembayaran; tampilan stok adalah perkiraan.
- Uang = `Long` rupiah. Waktu = jam server.
- Setiap fitur kampanye wajib: kill switch, monitor invariant, concurrency test, dan runbook sebelum rilis.
- Dokumentasi tetap diperbarui di `docs/backend.md`, `docs/api.md`, `docs/database.md`, dan `docs/audit.md`.
