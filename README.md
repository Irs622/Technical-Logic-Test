# Technical Logic Test — Studi Kasus Live Commerce (Oversell Stok Promo)

Jawaban tertulis **dan** simulasi yang bisa dijalankan untuk studi kasus oversell pada sesi live commerce: server Ktor dengan database SQL yang menegakkan invariant, plus aplikasi Android (Jetpack Compose) sebagai klien.

| Bagian | Isi |
|---|---|
| [Soal](#soal) | Studi kasus asli |
| [1. Analisis insiden](#1-analisis-insiden) | Akar masalah, faktor pemicu, mitigasi, jawaban "berhenti di 200?" |
| [2. Alur pembelian yang seharusnya](#2-alur-pembelian-yang-seharusnya) | Flow, SQL atomik, state machine, kegagalan VA |
| [3. Desain ulang arsitektur](#3-desain-ulang-arsitektur) | Arsitektur target, prioritas P0–P2 |
| [Bukti dari simulasi](#bukti-dari-simulasi) | Hasil beban 250.000 pembeli dan 26 test |
| [Menjalankan](#menjalankan) | Server, reproduksi insiden, aplikasi Android |
| [Batasan dan deviasi](#batasan-dan-deviasi-dari-kasus) | Yang belum dibuat / berbeda dari kasus, ditulis apa adanya |
| [Struktur proyek](#struktur-proyek) | Peta repo dan dokumen pendukung |

---

## Soal

Seorang penjual menawarkan **100 unit** seharga **Rp500.000** selama sesi live **20.00–20.30 WIB**; setelah itu harga kembali **Rp1.000.000**. Ia punya **250 unit** lain di gudang untuk harga normal. Sekitar **250.000 penonton** mencoba membeli antara 20.15 dan 20.30. Setiap pelanggan maksimal satu unit; aplikasi menampilkan nomor **virtual account (VA)** yang berlaku **24 jam**.

Keesokan harinya pukul 09.00, penjual melapor **150 pembayaran promo (Rp75 juta)** padahal hanya 100 unit yang ditawarkan, dan meminta platform menanggung selisih **Rp25 juta**. **800 penjual lain** mengalami hal sama. Pukul 12.00 jumlahnya menjadi **200 pembayaran** (kelebihan 100 unit, tuntutan Rp50 juta) dan engineering tidak bisa menjamin angkanya berhenti.

**Proses backend saat ini:** (1) baca sisa stok dari MySQL, jika > 0 lanjut; (2) sebelum membuat VA, kurangi stok 1 unit dan tulis ke MySQL; (3) jika pembuatan VA gagal, tambahkan kembali 1 unit. Tim memastikan tidak ada kegagalan VA, sehingga langkah 3 tidak pernah jalan.

**Pertanyaan**
1. Bagaimana analisis Anda atas insiden ini?
2. Menurut Anda, bagaimana proses pembelian seharusnya berjalan di dalam sistem?
3. Jika diberi keleluasaan penuh mendesain ulang arsitektur sistem dan aplikasi, apa yang Anda usulkan agar insiden tidak terulang?

Angka dasar yang dipakai:
- Selisih per unit = Rp1.000.000 − Rp500.000 = **Rp500.000**. 50 unit lebih = Rp25 juta, 100 unit lebih = Rp50 juta; eksposur naik linear dengan tiap pembayaran ekstra.
- 250.000 request dalam 15 menit ≈ **280 request/detik rata-rata**, dengan puncak jauh lebih tinggi di menit-menit awal, dan semuanya menyasar **satu baris stok** (hot row).
- "150 dibayar" hanyalah **batas bawah**: yang diterbitkan adalah VA, bukan pembayaran. VA yang belum dibayar masih sah sampai 20.30 hari berikutnya.

---

## 1. Analisis insiden

### Akar masalah: check-then-act yang tidak atomik (lost update)

Backend melakukan tiga langkah terpisah: **baca** stok → **putuskan** lanjut bila `stok > 0` → **tulis** stok baru. Di bawah konkurensi, banyak request membaca nilai yang sama, semuanya lolos pengecekan, lalu saling menimpa tulisan. Stok turun jauh lebih sedikit daripada jumlah VA yang terbit.

```mermaid
sequenceDiagram
  participant A as Pembeli A
  participant B as Pembeli B
  participant S as Backend
  participant D as MySQL
  A->>S: Beli
  B->>S: Beli
  S->>D: Baca stok (A)
  D-->>S: stok = 1
  S->>D: Baca stok (B)
  D-->>S: stok = 1
  Note over S: Keduanya lolos stok > 0
  S->>D: Tulis stok = 0 (A)
  S->>D: Tulis stok = 0 (B)
  S-->>A: VA terbit
  S-->>B: VA terbit
  Note over D: 2 VA terbit, stok hanya turun 1
```

Klaim "rollback langkah 3 tidak pernah berjalan" benar tetapi **tidak relevan**: ia hanya menyingkirkan satu hipotesis (stok dikembalikan karena VA gagal), bukan penyebab utama. Penyebabnya ada di langkah 1–2.

### Faktor yang memperparah

| # | Faktor | Dampak |
|---|---|---|
| 1 | Tidak ada invariant di database (`reserved + sold <= allocation`) | Kode yang salah dibiarkan menembus |
| 2 | Satu baris stok menerima seluruh trafik | Kontensi tinggi → timeout dan retry → jendela race makin lebar |
| 3 | VA berlaku 24 jam | Eksposur terbuka jauh setelah sesi selesai |
| 4 | Tidak ada reservasi dengan TTL dan kepemilikan stok per order | Tidak bisa tahu order mana yang "sah" |
| 5 | Harga promo mengikuti sesi, tidak di-snapshot ke order | Sulit membuktikan harga mana yang berlaku |
| 6 | Tidak ada idempotency key | Tap ganda / retry klien bisa membuat order ganda |
| 7 | Tidak ada monitor invariant atau alert | Diketahui dari telepon penjual 12 jam kemudian |
| 8 | Tidak ada kill switch per kampanye | Tidak bisa menghentikan dampak dalam detik |
| 9 | Tidak ada concurrency/load test sebagai syarat rilis | Bug hanya muncul di produksi |
| 10 | Tidak ada runbook dan syarat promo eksplisit | Kompensasi diputuskan ad-hoc lewat telepon |

### Mitigasi segera (urutan eksekusi)

1. **Hentikan sumber pendarahan** — matikan penerbitan VA promo lewat kill switch di **backend** (bukan hanya menyembunyikan di aplikasi), lalu pastikan request yang sedang berjalan dan job tertunda ikut berhenti.
2. **Ukur eksposur** — rekonsiliasi order, VA, dan status pembayaran dengan penyedia pembayaran untuk **seluruh** penjual terdampak. Kelompokkan: `dibayar`, `VA belum dibayar`, `status tidak pasti`. Hitung per kampanye: `order_aktif − alokasi`.
3. **Bekukan jumlah** — VA belum dibayar untuk kampanye ber-oversell dinonaktifkan di sisi gateway, atau callback-nya ditahan sampai kebijakan diputuskan.
4. **Hubungi penjual secara proaktif**, jangan menunggu 800 penjual menelepon satu per satu.

### Menjawab penjual: "Ada jaminan berhenti di 200?"

Jawaban yang benar adalah **mekanisme**, bukan angka:

> "Mulai pukul X semua VA promo untuk kampanye Anda sudah dikunci sehingga tidak ada pembayaran baru yang diterima. Jumlah final 200 pembayaran, dan kami menyiapkan opsi penyelesaian."

Angka final tidak boleh disebut sebelum langkah 3 benar-benar terverifikasi di sisi gateway.

### Opsi penyelesaian order di atas alokasi

Keputusan ini milik bisnis, keuangan, dan legal; engineering menyiapkan datanya.

| Opsi | Ringkas | Plus | Minus |
|---|---|---|---|
| A. Penuhi semua | Penjual kirim, platform menanggung selisih Rp500.000 × kelebihan | Pelanggan puas, tidak ada ulasan bintang satu | Biaya langsung; 800 penjual lain berpotensi menuntut hal sama |
| B. Penuhi N pertama, refund sisanya | Urut waktu reservasi/pembayaran; sisanya refund penuh + voucher | Biaya terkontrol, adil secara prosedur | Pelanggan yang di-refund kecewa |
| C. Campuran | Penuhi sebagian atas kesediaan penjual, sisanya refund + voucher | Fleksibel | Rumit dioperasikan untuk 800 penjual |

Rekomendasi teknis: siapkan skrip yang menghasilkan daftar `order_id` terurut (`reserved_at`, lalu `paid_at`) beserta keputusan A/B/C per order, sehingga apa pun keputusannya bisa dieksekusi massal dan teraudit. Kompensasi tidak boleh dihitung manual per telepon.

---

## 2. Alur pembelian yang seharusnya

### Prinsip

1. **Satu otoritas stok: MySQL.** Redis/cache hanya untuk tampilan dan rate limiting, tidak pernah menentukan "terjual".
2. **Alokasi atomik** — keputusan = `affected_rows` dari satu `UPDATE` bersyarat, bukan hasil bacaan sebelumnya.
3. **Invariant di database** — `CHECK (reserved + sold <= allocation)` sebagai jaring pengaman terakhir.
4. **Reservasi + order + ledger dalam satu transaksi singkat** agar tidak ada reservasi yatim.
5. **Panggilan gateway di luar transaksi** dan tidak pernah memegang lock baris stok.
6. **Idempoten di semua titik**: pembelian, pembuatan VA, callback, pelepasan reservasi.
7. **Harga di-snapshot ke order** saat reservasi, memakai jam server.
8. **Tampilan stok di klien hanya petunjuk**, bukan jaminan.

### Alokasi atomik

```sql
START TRANSACTION;

UPDATE campaign
   SET reserved = reserved + 1
 WHERE id = ? AND status = 'ACTIVE'
   AND NOW(3) >= starts_at AND NOW(3) < ends_at
   AND reserved + sold < allocation;
-- affected_rows = 0  -> ROLLBACK, jawab SOLD_OUT / CAMPAIGN_NOT_ACTIVE, TIDAK ada VA

INSERT INTO orders (..., unit_price, idempotency_key, status, reserved_until)
VALUES (..., promo_price_snapshot, ?, 'PENDING_PAYMENT', NOW(3) + INTERVAL ? SECOND);
-- duplicate key (user,campaign) / idempotency_key -> ROLLBACK, kembalikan order yang ada

INSERT INTO inventory_ledger (order_id, entry_type) VALUES (?, 'RESERVE');
COMMIT;
```

### Flow lengkap

```mermaid
sequenceDiagram
  participant U as Aplikasi Android
  participant S as Order/Inventory Service
  participant D as MySQL
  participant P as Payment Gateway
  U->>S: POST /purchase (Idempotency-Key)
  S->>D: Reservasi atomik + order PENDING_PAYMENT
  alt alokasi habis
    S-->>U: 409 SOLD_OUT (tanpa VA)
  else berhasil
    S->>P: Buat VA (idempotency key = order_id, expiry = reserved_until)
    alt VA gagal pasti
      S->>D: Lepas reservasi (idempoten per order_id)
      S-->>U: 503 retryable
    else hasil tidak diketahui (timeout)
      S->>P: Cek status VA sebelum retry
    else VA berhasil
      S-->>U: 201 order + nomor VA + expires_at
      U->>P: Bayar
      P->>S: Callback pembayaran (HMAC)
      S->>D: CAS PENDING_PAYMENT -> PAID, reserved-1, sold+1
    end
  end
  Note over S,D: Worker melepas reservasi kedaluwarsa. Bayar terlambat dan alokasi sudah diambil orang lain: refund otomatis
```

### State machine order

```mermaid
stateDiagram-v2
  [*] --> PENDING_PAYMENT: reservasi atomik berhasil
  PENDING_PAYMENT --> PAID: callback pembayaran (CAS)
  PENDING_PAYMENT --> EXPIRED: lewat reserved_until (worker)
  PENDING_PAYMENT --> CANCELLED: batal pelanggan / VA gagal pasti
  PENDING_PAYMENT --> VOIDED: kill switch / kelebihan alokasi
  EXPIRED --> PAID: bayar terlambat, alokasi masih ada
  EXPIRED --> REFUND_REQUIRED: bayar terlambat, alokasi sudah diambil
  REFUND_REQUIRED --> REFUNDED
  PAID --> FULFILLED
  PAID --> VOIDED: kelebihan alokasi (refund)
```

### Aturan turunan

| Topik | Aturan |
|---|---|
| 1 unit per pelanggan | `UNIQUE (user_id, campaign_id, active_key)`; `active_key = 1` hanya untuk status `PENDING_PAYMENT/PAID/FULFILLED` (NULL selainnya), sehingga pelanggan boleh membeli lagi setelah ordernya kedaluwarsa |
| Kegagalan VA | Transaksi DB tidak mencakup gateway. Gagal pasti → lepas reservasi via `inventory_ledger` (`UNIQUE(order_id, entry_type)`); timeout → cek status VA dulu, jangan terbitkan VA kedua |
| TTL reservasi | VA flash sale dibuat kedaluwarsa pada `reserved_until` (default 60 menit) |
| Pembayaran terlambat | Setelah order `EXPIRED`: dipulihkan bila alokasi masih ada, selain itu `REFUND_REQUIRED` → refund otomatis |
| Callback | Verifikasi HMAC, dedup `provider_event_id`, lalu transisi status **compare-and-set** agar callback dan worker kedaluwarsa tidak berebut |
| Jam | Seluruh keputusan waktu memakai jam server DB, bukan jam klien |
| Harga | `unit_price` di-snapshot saat reservasi; pembayaran setelah 20.30 tetap memakai harga snapshot selama order belum kedaluwarsa |

---

## 3. Desain ulang arsitektur

Tantangannya bukan sekadar 250.000 pengguna, melainkan **lonjakan request, konsistensi alokasi, dan integrasi pembayaran**. MySQL menjadi satu-satunya otoritas stok; Redis untuk rate limit/cache; worker rekonsiliasi dan monitor invariant sebagai jaring pengaman; kill switch per kampanye.

```mermaid
flowchart TB
  M[Aplikasi Android / iOS] --> W[API Gateway + Rate Limit + Waiting Room]
  W --> SVC[Order / Inventory Service]
  SVC --> DB[(MySQL: otoritas stok, conditional update + constraint)]
  SVC --> PAY[Payment Gateway: VA + pembatalan massal]
  PAY -->|callback tervalidasi| SVC
  SVC --> KS{{Kill switch per kampanye}}
  REC[Worker kedaluwarsa + rekonsiliasi order, VA, pembayaran] --> DB
  REC --> PAY
  MON[Monitor invariant + alert on-call] --> DB
  MON --> KS
```

| Level | Solusi | Tujuan |
|---|---|---|
| P0 | Conditional update atomik + `CHECK` constraint | Mencegah oversell |
| P0 | Kill switch per kampanye (di backend) | Menghentikan dampak dalam detik |
| P0 | Idempotency key + rekonsiliasi pembayaran | Mencegah duplikasi, menangani status ambigu |
| P0 | Alert: `reserved + sold > allocation` atau `order_aktif > allocation` | Deteksi dalam menit, bukan 12 jam |
| P1 | Reservasi TTL + worker kedaluwarsa + VA expiry selaras | Mengelola stok belum dibayar |
| P1 | Waiting room, rate limit per user/IP/device | Meredam lonjakan trafik |
| P1 | Pembatalan massal VA di gateway | Membekukan eksposur saat insiden |
| P2 | Redis cache, CDC, data warehouse, sharded counter | Optimasi bila terbukti perlu |

**Tentang hot row.** Untuk 100 unit, hampir semua request ditolak cepat: `UPDATE` tidak mengenai baris (`affected_rows = 0`) begitu alokasi habis. Pencegahan terbaik adalah *admission control* (waiting room meloloskan ±3–5× alokasi, sisanya langsung "stok habis" tanpa menyentuh MySQL). Bila alokasi besar dan kontensi terbukti jadi masalah, pecah alokasi menjadi N slot counter; invariantnya tetap `SUM(slot.allocation)`.

**Syarat rilis fitur flash sale:** concurrency test (≥5.000 request paralel pada alokasi 100 → tepat 100 order aktif), load test ≥3× puncak termasuk gateway timeout, chaos test callback ganda/terlambat, runbook insiden, dan syarat promo yang eksplisit (alokasi, 1 per akun, TTL bayar, hak platform membatalkan order di atas alokasi).

**Di sisi aplikasi:** satu `Idempotency-Key` per niat beli (disimpan sampai hasil final); retry memakai key yang sama; tombol Beli dinonaktifkan saat memproses tetapi server tetap idempoten; countdown memakai waktu server; angka stok ditampilkan sebagai "perkiraan"; hasil dipetakan ke `PurchaseResult` (Reserved / SoldOut / AlreadyPurchased / CampaignNotActive / RateLimited / Unknown / Failure).

---

## Bukti dari simulasi

Reproduksi insiden dengan **250.000 pembeli virtual** pada alokasi 100 (`POST /sim/load`, dijalankan di server nyata):

| Mode | Diterima / VA terbit | Stok habis | Oversell | Durasi |
|---|---:|---:|---:|---:|
| Alur lama (baca → tulis) | **18.409** | 231.591 | **18.309** | ±4,7 dtk |
| Alur baru (UPDATE bersyarat atomik) | **100** | 249.900 | **0** | ±3,8 dtk |

Pada alur lama, counter stok di database hanya turun sedikit padahal puluhan ribu VA terbit — sama seperti gejala di kasus.

**26 test otomatis lulus** (`./gradlew :server:test`), antara lain:
- 5.000 pembeli paralel pada alokasi 100 → tepat 100 order aktif, 4.900 `SOLD_OUT`.
- Alur lama pada 2.000 pembeli → oversell terbukti.
- Idempotency key sama → order sama (201 lalu 200).
- Satu pelanggan satu order aktif; kedaluwarsa melepas stok dan pelanggan boleh membeli lagi.
- Pelepasan reservasi idempoten dengan 8 worker paralel.
- Callback ganda, tanda tangan salah, callback hilang (diperbaiki rekonsiliasi), bayar terlambat (dipulihkan atau di-refund).
- Gateway gagal pasti → reservasi dilepas; gateway timeout → VA dicek dulu, tidak ada VA ganda.
- `CHECK` constraint menolak oversell walau kode salah; monitor menjeda kampanye otomatis saat counter menyimpang; `void-excess` memperbaiki counter.
- Checkout toko paralel: stok tidak bisa minus.

Status audit per poin ada di [`docs/audit.md`](docs/audit.md) (INV-01 s/d INV-14) dan hanya menandai PASSED untuk yang benar-benar teruji.

---

## Menjalankan

Butuh JDK 17+ (Android Studio menyertakan JBR):
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

**Server (Ktor + H2 mode MySQL), port 8080**
```bash
./gradlew :server:run
./gradlew :server:test
```

**Reproduksi insiden**
```bash
curl -X POST "localhost:8080/sim/reset?allocation=100"
curl -X POST "localhost:8080/sim/load?mode=legacy&users=250000"   # alur lama: oversell
curl -X POST "localhost:8080/sim/reset?allocation=100"
curl -X POST "localhost:8080/sim/load?mode=safe&users=250000"     # alur baru: tepat 100
```

**Mencoba satu pembeli lewat curl**
```bash
curl -X POST localhost:8080/campaigns/1/purchase -H 'Authorization: Bearer demo-1' \
     -H 'Idempotency-Key: abc' -d '{"quantity":1}'          # 201, kirim ulang -> 200
curl -X POST "localhost:8080/sim/payments/<order_id>/pay?behavior=duplicate"
curl -X POST localhost:8080/admin/campaigns/1/pause          # kill switch
curl localhost:8080/admin/campaigns/1/reconciliation
```
Perilaku gateway tiruan: `behavior=ok|duplicate|late|lost`, dan `POST /sim/gateway?mode=ok|fail|timeout`.

**Aplikasi Android** — buka di Android Studio lalu *Run 'app'*, atau `./gradlew :app:assembleDebug`. Emulator memakai `http://10.0.2.2:8080`; HP fisik: `adb reverse tcp:8080 tcp:8080` lalu ubah alamat server di tab Akun. Di tab Akun ID pengguna demo bisa diganti untuk mencoba beberapa pembeli pada promo yang sama.

---

## Batasan dan deviasi dari kasus

Ditulis apa adanya supaya tidak ada klaim yang melebihi bukti.

- **VA 60 menit, bukan 24 jam.** Di kasus VA berlaku 24 jam; saya persingkat sebagai *usulan perbaikan* untuk memperkecil eksposur. Ini keputusan bisnis dan mudah dikembalikan (`payment_window_sec`).
- **Database simulasi adalah H2 (mode MySQL), bukan MySQL 8.** Perilaku lock dan `CHECK` perlu diverifikasi ulang di MySQL sebelum dijadikan bukti produksi.
- **Waiting room (`202 QUEUED`) belum diimplementasikan** — baru rate limit per pengguna dan penolakan cepat saat stok habis. Redis juga belum dipakai (cache di memori).
- **Alert hanya ke log**, belum ke on-call.
- **250 unit gudang di harga normal belum dimodelkan**: setelah promo selesai belum ada alur penjualan sisa stok di Rp1.000.000.
- **Hanya satu penjual dan satu kampanye.** Skenario 800 penjual terdampak tidak disimulasikan; rekonsiliasi dan `void-excess` bekerja per kampanye.
- **Kompensasi massal (opsi A/B/C) baru berupa dokumen**, belum ada endpoint eksekusi refund/voucher massal.
- **Uji beban 250.000 berjalan in-process**, belum lewat HTTP pada ≥3× puncak dan belum dengan gateway timeout 30%.
- **Aplikasi Android sudah ter-build (APK debug) tetapi belum dijalankan di emulator**, jadi tampilannya belum diverifikasi visual dan belum ada uji otomatis sisi Android.
- Auth disederhanakan (`Authorization: Bearer demo-<userId>`); tidak ada uang sungguhan.

---

## Struktur proyek

```
.
├── server/                    # Backend simulasi (Ktor + H2): inventori atomik, gateway tiruan, beban, test
│   └── src/main/resources/schema.sql   # DDL: CHECK, UNIQUE, ledger, audit
├── app/                       # Aplikasi Android (Compose, OkHttp): toko lengkap + promo live
├── DESIGN.md                  # Sistem desain UI ("Struk & Rak Toko")
├── docs/
│   ├── incident-live-commerce.md   # Post-mortem lengkap (sumber README ini)
│   ├── backend.md  api.md  database.md
│   ├── requirements.md  audit.md  frontend.md  uiux.md  deployment.md
├── .agents/  .ai-context/     # Panduan agent AI untuk proyek
└── gradlew  settings.gradle.kts
```
