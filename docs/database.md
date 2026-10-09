# Database Schema & Persistence Strategy

Dua database dengan peran berbeda:
1. **MySQL (server)** — otoritas stok, order, pembayaran.
2. **Room (Android)** — cache dan antrean niat beli; **bukan** otoritas.

---

## 1. MySQL (Server)

> Implementasi simulasi: `server/src/main/resources/schema.sql` (H2 mode MySQL). Perbedaan dari DDL di bawah: `ENUM` diganti `VARCHAR` + `CHECK`, `active_key` memakai `CASE`. Skema ini diuji di H2 **dan MySQL 8.0.46** (`TIMESTAMP(3)` otomatis menjadi `DATETIME(3)` saat `MYSQL_URL` diset). Urutan lock: `campaign` → `orders` → `inventory_ledger`.

### 1.1 Prinsip
- Engine InnoDB, MySQL **≥ 8.0.16** agar `CHECK` ditegakkan.
- Uang disimpan sebagai `BIGINT` rupiah (bukan `DECIMAL`/`FLOAT`).
- Waktu `DATETIME(3)` UTC; `NOW(3)` dari DB menjadi sumber waktu.
- Invariant ditegakkan **di database**, bukan hanya di kode.

### 1.2 Skema

```sql
CREATE TABLE campaign (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  product_id    BIGINT NOT NULL,
  seller_id     BIGINT NOT NULL,
  promo_price   BIGINT NOT NULL,
  normal_price  BIGINT NOT NULL,
  allocation    INT    NOT NULL,
  reserved      INT    NOT NULL DEFAULT 0,
  sold          INT    NOT NULL DEFAULT 0,
  payment_window_sec INT NOT NULL DEFAULT 3600,
  status        ENUM('SCHEDULED','ACTIVE','PAUSED','ENDED') NOT NULL DEFAULT 'SCHEDULED',
  starts_at     DATETIME(3) NOT NULL,
  ends_at       DATETIME(3) NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT ck_alloc_pos   CHECK (allocation >= 0),
  CONSTRAINT ck_counters    CHECK (reserved >= 0 AND sold >= 0),
  CONSTRAINT ck_no_oversell CHECK (reserved + sold <= allocation),
  CONSTRAINT ck_price       CHECK (promo_price > 0 AND promo_price <= normal_price),
  CONSTRAINT ck_window      CHECK (starts_at < ends_at),
  INDEX ix_campaign_seller (seller_id),
  INDEX ix_campaign_status_time (status, starts_at, ends_at)
) ENGINE=InnoDB;

CREATE TABLE orders (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no        VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL,
  campaign_id     BIGINT NOT NULL,
  status          ENUM('PENDING_PAYMENT','PAID','FULFILLED','EXPIRED',
                       'REFUND_REQUIRED','REFUNDED','CANCELLED','VOIDED') NOT NULL,
  unit_price      BIGINT NOT NULL,                 -- snapshot saat reservasi
  idempotency_key CHAR(36) NOT NULL,
  reserved_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reserved_until  DATETIME(3) NOT NULL,
  paid_at         DATETIME(3) NULL,
  -- NULL untuk status tidak aktif: UNIQUE mengizinkan banyak NULL
  active_key      TINYINT GENERATED ALWAYS AS (
                    IF(status IN ('PENDING_PAYMENT','PAID','FULFILLED'), 1, NULL)
                  ) STORED,
  UNIQUE KEY uq_order_no (order_no),
  UNIQUE KEY uq_idem (user_id, idempotency_key),
  UNIQUE KEY uq_one_per_user (user_id, campaign_id, active_key),
  INDEX ix_expiry (status, reserved_until),
  INDEX ix_campaign_status (campaign_id, status, reserved_at),
  FOREIGN KEY (campaign_id) REFERENCES campaign(id)
) ENGINE=InnoDB;

CREATE TABLE payment_va (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id        BIGINT NOT NULL,
  provider        VARCHAR(32) NOT NULL,
  provider_ref    VARCHAR(64) NULL,                 -- diisi setelah gateway menjawab
  va_number       VARCHAR(32) NULL,
  amount          BIGINT NOT NULL,
  status          ENUM('REQUESTING','ACTIVE','PAID','EXPIRED','FAILED','UNKNOWN') NOT NULL,
  expires_at      DATETIME(3) NOT NULL,             -- = orders.reserved_until
  UNIQUE KEY uq_va_order (order_id),                -- satu VA per order
  UNIQUE KEY uq_va_provider (provider, provider_ref),
  FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB;

CREATE TABLE payment_events (                       -- log callback, dedup
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  provider          VARCHAR(32) NOT NULL,
  provider_event_id VARCHAR(64) NOT NULL,
  order_id          BIGINT NULL,
  payload           JSON NOT NULL,
  received_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_event (provider, provider_event_id)
) ENGINE=InnoDB;

CREATE TABLE inventory_ledger (                     -- append-only
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id    BIGINT NOT NULL,
  campaign_id BIGINT NOT NULL,
  entry_type  ENUM('RESERVE','RELEASE','CONFIRM') NOT NULL,
  qty         INT NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_once (order_id, entry_type),        -- pelepasan idempoten
  INDEX ix_ledger_campaign (campaign_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE order_events (                         -- audit transisi status
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_id   BIGINT NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status   VARCHAR(24) NOT NULL,
  actor      VARCHAR(48) NOT NULL,                  -- user:123 | worker:expire | gateway | ops:rina
  reason     VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  INDEX ix_events_order (order_id, created_at)
) ENGINE=InnoDB;

CREATE TABLE campaign_audit (                       -- kill switch & perubahan penting
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  campaign_id BIGINT NOT NULL,
  action      VARCHAR(32) NOT NULL,
  actor       VARCHAR(48) NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB;
```

### 1.3 Pola query kunci

**Reservasi** — lihat [`incident-live-commerce.md`](incident-live-commerce.md) §2.2.

**Konfirmasi pembayaran (callback)**
```sql
START TRANSACTION;
INSERT INTO payment_events (...) ...;                 -- duplicate key → balas 200, selesai
UPDATE orders SET status='PAID', paid_at=NOW(3)
 WHERE id=? AND status='PENDING_PAYMENT';
-- affected_rows = 1 → lanjut
UPDATE campaign SET reserved = reserved - 1, sold = sold + 1 WHERE id=?;
INSERT INTO inventory_ledger (order_id, campaign_id, entry_type) VALUES (?, ?, 'CONFIRM');
COMMIT;
-- affected_rows = 0 pada UPDATE orders: order sudah EXPIRED/CANCELLED → jalur REFUND_REQUIRED
```

**Kedaluwarsa (worker)**
```sql
SELECT id, campaign_id FROM orders
 WHERE status='PENDING_PAYMENT' AND reserved_until < NOW(3)
 ORDER BY reserved_until LIMIT 200 FOR UPDATE SKIP LOCKED;
-- per order, dalam transaksi: CAS → EXPIRED; INSERT ledger RELEASE (duplikat = lewati);
-- hanya bila ledger berhasil disisipkan: UPDATE campaign SET reserved = reserved - 1
```

### 1.4 Catatan operasional
- Urutan lock selalu `campaign` → `orders` untuk menghindari deadlock; transaksi sependek mungkin, tanpa panggilan jaringan di dalamnya.
- Tangani deadlock/timeout dengan retry terbatas (maks 2×) di service.
- Retensi `Idempotency-Key` ≥ 24 jam (disimpan di `uq_idem`).
- Backup + uji pemulihan sebelum kampanye besar. Migrasi skema pada kampanye aktif dilarang.

---

## 2. Room (Android)

- **Versi skema:** v1; ekspor skema JSON ke `app/schemas/`.
- **Foreign Keys:** definisi eksplisit dengan `CASCADE`/`SET_NULL`.
- **Indeks:** semua kolom filter dan FK.
- **Migrasi:** uji dengan `MigrationTestHelper`; `fallbackToDestructiveMigration` dilarang di produksi.
- **Thread safety:** DAO memakai `suspend fun` / `Flow<T>`.

### Entitas

| Tabel | Kolom penting | Fungsi |
|---|---|---|
| `product` | `id`, `name`, `price` (Long), `image_ref`, `category_id`, `updated_at` | Cache katalog |
| `cart_item` | `product_id` (PK), `qty`, `added_at` | Keranjang lokal |
| `order_cache` | `order_id`, `order_no`, `status`, `unit_price`, `va_number`, `va_expires_at`, `synced_at` | Riwayat & struk offline |
| `pending_purchase` | `idempotency_key` (PK), `campaign_id`, `state` (`SUBMITTING`/`UNKNOWN`/`DONE`), `created_at` | Menjamin retry memakai key yang sama |
| `server_clock` | `offset_ms`, `measured_at` | Koreksi waktu server |

Aturan:
- `order_cache` selalu ditimpa oleh respons server; klien tidak pernah menaikkan status sendiri (mis. `PAID`) tanpa konfirmasi server.
- `pending_purchase` dihapus hanya setelah hasil final (201/200/409) diterima.
- Seluruh nominal `Long` rupiah; pemformatan lewat `formatRupiah()` ([`DESIGN.md`](../DESIGN.md) §7.3).
