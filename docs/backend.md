# Backend & Data Architecture

Dua sisi: **server (sumber kebenaran stok & pembayaran)** dan **klien Android (cache + antarmuka)**. Latar belakang aturan ada di [`incident-live-commerce.md`](incident-live-commerce.md).

## Bagian A — Server

### A.1 Stack
- **Database otoritas:** MySQL 8.0.16+ (wajib, agar `CHECK` constraint ditegakkan), InnoDB, isolation `REPEATABLE READ` (default).
- **Cache & rate limit:** Redis. **Tidak pernah** menjadi penentu stok terjual.
- **Layanan:** Order/Inventory Service (stateless, bisa di-scale horizontal), Payment Adapter, Worker (kedaluwarsa + rekonsiliasi), Monitor.
- **Antrean:** message queue untuk notifikasi & email (bukan untuk keputusan stok).

### A.2 Aturan emas inventori
1. Tidak ada `SELECT stok` lalu `UPDATE stok = <nilai di aplikasi>`. Selalu `UPDATE ... SET reserved = reserved + 1 WHERE ... AND reserved + sold < allocation`.
2. Keputusan = `affected_rows`, bukan hasil bacaan sebelumnya.
3. Reservasi + pembuatan order + ledger dalam **satu transaksi singkat**.
4. Panggilan gateway pembayaran **di luar** transaksi; tidak memegang lock baris stok.
5. Setiap operasi yang bisa diulang (beli, buat VA, callback, lepas reservasi) **idempoten**.
6. Semua jalur mengunci baris `campaign` lebih dulu (`SELECT ... FOR UPDATE`) sebelum menyentuh `orders` agar tidak deadlock; perubahan status hanya lewat **compare-and-set** (`UPDATE orders SET status=? WHERE id=? AND status=?`).
7. Waktu mengikuti jam DB (`NOW(3)`), bukan jam aplikasi/klien.
8. Harga di-snapshot ke order saat reservasi.

### A.3 Mesin status order

```
PENDING_PAYMENT ──paid──────────▶ PAID ──▶ FULFILLED
      │ (reserved_until lewat)
      ├──────────────────────────▶ EXPIRED ──(bayar terlambat)──▶ REFUND_REQUIRED ──▶ REFUNDED
      ├──cancel/VA gagal pasti───▶ CANCELLED
      └──kill switch/oversell────▶ VOIDED (kompensasi lewat prosedur insiden)
```

Transisi yang diizinkan hanya yang tergambar. Setiap transisi menulis baris `inventory_ledger` dan `order_events`.

| Transisi | Efek stok (`campaign`) |
|---|---|
| (baru) → PENDING_PAYMENT | `reserved + 1` |
| PENDING_PAYMENT → PAID | `reserved − 1`, `sold + 1` |
| PENDING_PAYMENT → EXPIRED/CANCELLED | `reserved − 1` |
| EXPIRED → REFUND_REQUIRED | tidak ada (stok sudah dilepas) |

### A.4 Worker
| Worker | Interval | Tugas |
|---|---|---|
| `expire-reservations` | 10 detik | Cari `PENDING_PAYMENT` dengan `reserved_until < NOW()`, CAS → `EXPIRED`, kembalikan `reserved`. Batch kecil, `SKIP LOCKED` |
| `reconcile-payments` | 1 menit | Bandingkan order vs status VA/pembayaran di gateway; perbaiki status ambigu (timeout, callback hilang) |
| `invariant-monitor` | 15 detik saat kampanye aktif | Cek invariant (A.5), kirim alert, picu kill switch otomatis bila perlu |

### A.5 Invariant yang dipantau
```sql
-- 1. Tidak boleh ada kampanye melampaui alokasi
SELECT id FROM campaign WHERE reserved + sold > allocation;

-- 2. Counter harus sama dengan kenyataan order
SELECT c.id FROM campaign c
LEFT JOIN (
  SELECT campaign_id,
         SUM(status = 'PENDING_PAYMENT') AS res,
         SUM(status = 'PAID' OR status = 'FULFILLED') AS sld
  FROM orders GROUP BY campaign_id
) o ON o.campaign_id = c.id
WHERE c.reserved <> IFNULL(o.res,0) OR c.sold <> IFNULL(o.sld,0);

-- 3. Satu pelanggan, satu order aktif per kampanye
SELECT user_id, campaign_id, COUNT(*) FROM orders
WHERE status IN ('PENDING_PAYMENT','PAID','FULFILLED')
GROUP BY user_id, campaign_id HAVING COUNT(*) > 1;
```
Pelanggaran → alert on-call + kill switch kampanye. Target deteksi: **< 1 menit**.

### A.6 Kill switch
- Flag `campaign.status` (`ACTIVE` / `PAUSED` / `ENDED`) dibaca di dalam `UPDATE` alokasi (A.2), sehingga efektif seketika tanpa menunggu cache kedaluwarsa.
- Endpoint admin: `POST /admin/campaigns/{id}/pause`. Setiap penggunaan dicatat (siapa, kapan, alasan).
- Bila terjadi oversell: pause → bulk cancel VA belum bayar di gateway → tahan callback → jalankan prosedur kompensasi.

### A.7 Pengendalian trafik
- **Waiting room** sebelum `/purchase` (**diimplementasikan**, `Admission.kt`): meloloskan 5× alokasi; sisanya `202` lalu `409 SOLD_OUT` cepat. Tiap reservasi yang dilepas membuka 5 slot.
- **Rate limit:** per user (mis. 3 req/10 detik), per IP/device, per kampanye.
- Respons stok pada halaman produk dari cache dengan label "perkiraan"; tidak dipakai untuk keputusan.

### A.8 Observabilitas
Metrik per kampanye: `reservations_total`, `sold_out_total`, `active_orders`, `allocation`, `va_created_total`, `va_error_total`, `callback_late_total`, latensi p50/p95/p99 `/purchase`. Dashboard & alert dibuat **sebelum** kampanye dibuka; runbook ditaut di alert.

---

## Bagian B — Klien Android

### B.1 Network
- OkHttp 4.x + Retrofit 2.x (atau Ktor Client), Kotlinx Serialization.
- Interceptor: Auth (Bearer), `IdempotencyInterceptor` untuk `POST /purchase`, Logging (hanya DEBUG).
- Timeout wajar (connect 5s, read 10s). Retry otomatis **hanya** untuk request idempoten atau yang membawa `Idempotency-Key` yang sama.

### B.2 Alur pembelian di klien
1. Pengguna menekan **Beli** → buat `Idempotency-Key` (UUID) **sekali per niat beli**, simpan di Room/DataStore sampai hasil final diterima.
2. Kirim `POST /campaigns/{id}/purchase`. Jika timeout/jaringan putus, **ulangi dengan key yang sama**, bukan key baru.
3. Tombol dinonaktifkan selama `Submitting` (cegah tap ganda); tetap tidak mengandalkannya, karena server idempoten.
4. Hasil dipetakan ke `PurchaseResult` (sealed interface) dan ditampilkan sesuai [`DESIGN.md`](../DESIGN.md) §8.

```kotlin
sealed interface PurchaseResult {
    data class Reserved(val orderId: String, val vaNumber: String, val expiresAt: Instant) : PurchaseResult
    data class Queued(val ticket: String, val retryAfterSec: Int) : PurchaseResult
    data object SoldOut : PurchaseResult
    data object AlreadyPurchased : PurchaseResult   // server mengembalikan order yang sudah ada
    data object CampaignNotActive : PurchaseResult
    data class Failure(val retryable: Boolean, val message: String) : PurchaseResult
}
```

### B.3 Waktu & countdown
- Klien menyimpan `serverOffset = serverTime - deviceTime` dari respons `GET /campaigns/{id}`. Countdown promo dan VA memakai waktu server terkoreksi, bukan jam perangkat.
- Tampilan "Sisa N" adalah **perkiraan**; saat ditekan Beli, server yang memutuskan.

### B.4 Local storage
- **Room:** cache katalog, keranjang, order & struk (offline baca), `pending_purchase` (key idempotensi + status).
- **DataStore:** preferensi, offset waktu server.
- **Keystore/EncryptedSharedPreferences:** token.

### B.5 Background
- WorkManager: sinkron order, perbarui status VA, ingatkan sebelum VA kedaluwarsa (notifikasi lokal 10 menit sebelum `expiresAt`).
- Coroutines: `Dispatchers.IO` untuk network/DB.

### B.6 Mode simulasi
Aplikasi ini simulasi: **gateway pembayaran adalah mock** (`MockPaymentGateway`) dengan perilaku yang dapat diatur (sukses, timeout, callback terlambat, callback ganda). Fitur ini wajib agar skenario insiden dapat diuji tanpa uang sungguhan. Detail kontrak di [`api.md`](api.md) §6.
