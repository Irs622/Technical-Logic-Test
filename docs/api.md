# API Contracts & Integration Guide

## 1. Base Configuration
- **Base URL:** `https://api.example.com/v1/`
- **Header standar:** `Content-Type: application/json`, `Accept: application/json`, `Authorization: Bearer <token>` (rute terproteksi)
- **Header khusus:** `Idempotency-Key: <uuid>` (wajib untuk `POST` yang membuat order), `X-Client-Version`

## 2. Standard Response Envelope
```json
{
  "success": true,
  "data": {},
  "message": "Operation successful",
  "error": null
}
```
Saat gagal:
```json
{
  "success": false,
  "data": null,
  "message": "Stok promo sudah habis.",
  "error": { "code": "SOLD_OUT", "retryable": false, "retry_after_sec": null }
}
```
Klien bercabang pada `error.code`, **bukan** pada teks `message`.

## 3. Error Handling Umum
- `400 Bad Request`: validasi gagal (`VALIDATION_ERROR`).
- `401 Unauthorized`: token kedaluwarsa → refresh senyap atau logout.
- `403 Forbidden`: tidak punya izin.
- `404 Not Found`: sumber daya tidak ada.
- `409 Conflict`: konflik status bisnis (lihat §4.2).
- `429 Too Many Requests`: kena rate limit; baca `Retry-After`.
- `500/503`: fallback; retry dengan exponential backoff + jitter (hanya bila `retryable = true`).

## 4. Endpoint Flash Sale

### 4.1 `GET /campaigns/{id}`
```json
{
  "id": "cmp_01",
  "product_id": "prd_88",
  "promo_price": 500000,
  "normal_price": 1000000,
  "currency": "IDR",
  "status": "ACTIVE",
  "starts_at": "2026-10-09T13:00:00Z",
  "ends_at": "2026-10-09T13:30:00Z",
  "stock_hint": { "remaining_approx": 37, "is_approximate": true },
  "payment_window_sec": 3600,
  "server_time": "2026-10-09T13:15:02.114Z"
}
```
- `remaining_approx` berasal dari cache dan hanya petunjuk tampilan. Harga dalam **rupiah bulat (integer)**.

### 4.2 `POST /campaigns/{id}/purchase`
Header: `Idempotency-Key` wajib. Body:
```json
{ "quantity": 1 }
```
Server menolak `quantity != 1` untuk kampanye berbatas satu unit per pelanggan (`VALIDATION_ERROR`).

| HTTP | `error.code` | Arti | Tindakan klien |
|---|---|---|---|
| 201 | – | Order dibuat, VA terbit | Tampilkan VA + hitung mundur |
| 200 | – | `Idempotency-Key` sama; mengembalikan order yang sama | Perlakukan seperti 201 |
| 202 | `QUEUED` | Masuk waiting room (`ticket`, `retry_after_sec`) | Polling `GET /queue/{ticket}` |
| 409 | `SOLD_OUT` | Alokasi habis, **tanpa VA** | Tampilkan state habis |
| 409 | `ALREADY_PURCHASED` | Pelanggan sudah punya order aktif; `data.order_id` disertakan | Arahkan ke order tersebut |
| 409 | `CAMPAIGN_NOT_ACTIVE` | Belum mulai / sudah berakhir / dijeda | Perbarui tampilan kampanye |
| 429 | `RATE_LIMITED` | Terlalu cepat | Hormati `Retry-After` |
| 503 | `PAYMENT_UNAVAILABLE` | VA gagal pasti; reservasi sudah dilepas | Boleh coba lagi (key baru dibuat bila user menekan ulang setelah hasil final) |

Respons sukses:
```json
{
  "success": true,
  "data": {
    "order_id": "ord_9f3a",
    "order_no": "TS-20261009-0412",
    "status": "PENDING_PAYMENT",
    "unit_price": 500000,
    "va": { "bank": "BCA", "number": "8077012345678901", "amount": 500000 },
    "reserved_until": "2026-10-09T14:15:02Z"
  },
  "message": "Pesanan dibuat.",
  "error": null
}
```
Jaminan server:
- Tidak pernah ada lebih dari `allocation` order aktif per kampanye.
- `va.expires_at == reserved_until`.
- Pemanggilan ulang dengan key yang sama selalu mengembalikan hasil yang sama selama masa simpan key (≥ 24 jam).

### 4.3 `GET /queue/{ticket}`
`{ "state": "WAITING" | "ADMITTED" | "REJECTED", "position_approx": 1840, "retry_after_sec": 3 }`. `ADMITTED` memberi token sekali pakai untuk `/purchase`.

### 4.4 `GET /orders/{id}` · `GET /orders`
Mengembalikan status terbaru: `PENDING_PAYMENT | PAID | FULFILLED | EXPIRED | REFUND_REQUIRED | REFUNDED | CANCELLED | VOIDED`. Daftar bersifat paginasi kursor.

### 4.5 `POST /orders/{id}/cancel`
Hanya untuk `PENDING_PAYMENT`. Idempoten; melepaskan reservasi.

## 5. Endpoint Internal & Admin

| Endpoint | Pemanggil | Catatan |
|---|---|---|
| `POST /internal/payments/callback` | Payment gateway | Header tanda tangan (HMAC) wajib. Dedup lewat `provider_event_id`. Selalu balas `200` untuk event duplikat |
| `POST /admin/campaigns/{id}/pause` | Operasional | Kill switch; butuh peran `ops_admin`; mencatat `reason` |
| `POST /admin/campaigns/{id}/resume` | Operasional | Hanya jika invariant lolos |
| `GET /admin/campaigns/{id}/reconciliation` | Operasional | `allocation`, `reserved`, `sold`, `orders_by_status`, selisih terhadap gateway |
| `POST /admin/campaigns/{id}/void-excess` | Operasional + Keuangan | Menandai order di atas alokasi (urutan `reserved_at`) sebagai `VOIDED` dan memicu refund/kompensasi sesuai keputusan |

## 6. Mock Gateway (mode simulasi)

`POST /sim/payments/{order_id}/pay` — mensimulasikan pelanggan membayar VA.

Query `behavior`: `ok` (default), `delay=<detik>`, `duplicate` (callback dikirim dua kali), `late` (dikirim setelah `reserved_until`), `lost` (callback tidak pernah dikirim; hanya rekonsiliasi yang bisa memperbaiki).

Mode ini hanya aktif pada build `debug`/staging dan ditandai jelas di UI ([`DESIGN.md`](../DESIGN.md) §7.9).

## 7. Status Implementasi di Simulasi (`server/`)

| Fitur | Status |
|---|---|
| `GET /campaigns/{id}`, `POST /campaigns/{id}/purchase`, `GET /orders`, `GET /orders/{id}`, cancel | Ada |
| Idempotency-Key, 429 + `Retry-After` (10 percobaan/10 detik/pengguna) | Ada |
| `GET /queue/{ticket}` dan respons `202 QUEUED` (waiting room) | **Belum** — hanya rate limit dan fast-reject "stok habis" |
| `/internal/payments/callback` (HMAC), `/admin/campaigns/{id}/pause\|resume\|void-excess\|reconciliation` | Ada |
| `/sim/*`: `reset`, `gateway`, `payments/{id}/pay?behavior=ok\|duplicate\|late\|lost`, `expire`, `reconcile`, `load` | Ada. `POST /campaigns/{id}/purchase?mode=legacy` memakai alur lama (bug) untuk demo |
| Toko: `GET /products`, `/products/{id}`, `/categories`, `/me`, `POST /me/topup`, `POST /checkout`, `GET /shop/orders` | Ada |
| Auth | Disederhanakan: `Authorization: Bearer demo-<userId>` |
