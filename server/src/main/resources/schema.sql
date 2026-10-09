-- Skema server simulasi (H2 mode MySQL). Padanan dari docs/database.md.
-- Invariant ditegakkan oleh database: CHECK dan UNIQUE.

CREATE TABLE users (
  id          BIGINT PRIMARY KEY,
  name        VARCHAR(64) NOT NULL,
  balance     BIGINT NOT NULL,
  CONSTRAINT ck_balance CHECK (balance >= 0)
);

CREATE TABLE products (
  id            BIGINT PRIMARY KEY,
  name          VARCHAR(128) NOT NULL,
  category      VARCHAR(48) NOT NULL,
  unit_label    VARCHAR(48) NOT NULL,
  origin        VARCHAR(64) NOT NULL,
  price         BIGINT NOT NULL,
  compare_price BIGINT NULL,
  stock         INT NOT NULL,
  description   VARCHAR(512) NOT NULL,
  CONSTRAINT ck_stock CHECK (stock >= 0),
  CONSTRAINT ck_price_pos CHECK (price > 0)
);
CREATE INDEX ix_products_category ON products(category);

CREATE TABLE campaign (
  id            BIGINT PRIMARY KEY,
  product_id    BIGINT NOT NULL,
  seller_name   VARCHAR(64) NOT NULL,
  promo_price   BIGINT NOT NULL,
  normal_price  BIGINT NOT NULL,
  allocation    INT NOT NULL,
  reserved      INT NOT NULL DEFAULT 0,
  sold          INT NOT NULL DEFAULT 0,
  payment_window_sec INT NOT NULL DEFAULT 3600,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  starts_at     TIMESTAMP(3) NOT NULL,
  ends_at       TIMESTAMP(3) NOT NULL,
  CONSTRAINT ck_alloc_pos   CHECK (allocation >= 0),
  CONSTRAINT ck_counters    CHECK (reserved >= 0 AND sold >= 0),
  CONSTRAINT ck_no_oversell CHECK (reserved + sold <= allocation),
  CONSTRAINT ck_price       CHECK (promo_price > 0 AND promo_price <= normal_price),
  CONSTRAINT ck_window      CHECK (starts_at < ends_at),
  CONSTRAINT ck_cstatus     CHECK (status IN ('SCHEDULED','ACTIVE','PAUSED','ENDED'))
);

CREATE TABLE orders (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no        VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL,
  campaign_id     BIGINT NOT NULL,
  status          VARCHAR(24) NOT NULL,
  unit_price      BIGINT NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  reserved_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reserved_until  TIMESTAMP(3) NOT NULL,
  paid_at         TIMESTAMP(3) NULL,
  -- NULL untuk status tidak aktif: UNIQUE mengizinkan banyak NULL, jadi pelanggan
  -- boleh membeli lagi setelah ordernya kedaluwarsa/dibatalkan.
  active_key      TINYINT GENERATED ALWAYS AS
                    (CASE WHEN status IN ('PENDING_PAYMENT','PAID','FULFILLED') THEN 1 ELSE NULL END),
  CONSTRAINT ck_ostatus CHECK (status IN ('PENDING_PAYMENT','PAID','FULFILLED','EXPIRED',
                                          'REFUND_REQUIRED','REFUNDED','CANCELLED','VOIDED')),
  CONSTRAINT uq_order_no UNIQUE (order_no),
  CONSTRAINT uq_idem UNIQUE (user_id, idempotency_key),
  CONSTRAINT uq_one_per_user UNIQUE (user_id, campaign_id, active_key),
  CONSTRAINT fk_order_campaign FOREIGN KEY (campaign_id) REFERENCES campaign(id)
);
CREATE INDEX ix_expiry ON orders(status, reserved_until);
CREATE INDEX ix_campaign_status ON orders(campaign_id, status, reserved_at);

CREATE TABLE payment_va (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id      BIGINT NOT NULL,
  provider      VARCHAR(32) NOT NULL,
  provider_ref  VARCHAR(64) NULL,
  va_number     VARCHAR(32) NULL,
  bank          VARCHAR(16) NULL,
  amount        BIGINT NOT NULL,
  status        VARCHAR(16) NOT NULL,
  expires_at    TIMESTAMP(3) NOT NULL,
  CONSTRAINT uq_va_order UNIQUE (order_id),
  CONSTRAINT fk_va_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE TABLE payment_events (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  provider          VARCHAR(32) NOT NULL,
  provider_event_id VARCHAR(64) NOT NULL,
  order_id          BIGINT NULL,
  payload           VARCHAR(2000) NOT NULL,
  received_at       TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_event UNIQUE (provider, provider_event_id)
);

CREATE TABLE inventory_ledger (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT NOT NULL,
  campaign_id BIGINT NOT NULL,
  entry_type  VARCHAR(16) NOT NULL,
  qty         INT NOT NULL DEFAULT 1,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_once UNIQUE (order_id, entry_type),
  CONSTRAINT ck_entry CHECK (entry_type IN ('RESERVE','RELEASE','CONFIRM'))
);

CREATE TABLE order_events (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT NOT NULL,
  from_status VARCHAR(24) NULL,
  to_status   VARCHAR(24) NOT NULL,
  actor       VARCHAR(48) NOT NULL,
  reason      VARCHAR(255) NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE INDEX ix_events_order ON order_events(order_id, created_at);

CREATE TABLE campaign_audit (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  campaign_id BIGINT NOT NULL,
  action      VARCHAR(32) NOT NULL,
  actor       VARCHAR(48) NOT NULL,
  reason      VARCHAR(255) NOT NULL,
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- Toko reguler (checkout dengan Saldo Simulasi)
CREATE TABLE shop_orders (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_no        VARCHAR(32) NOT NULL,
  user_id         BIGINT NOT NULL,
  idempotency_key VARCHAR(64) NOT NULL,
  shipping        VARCHAR(8) NOT NULL,
  subtotal        BIGINT NOT NULL,
  shipping_fee    BIGINT NOT NULL,
  total           BIGINT NOT NULL,
  created_at      TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uq_shop_no UNIQUE (order_no),
  CONSTRAINT uq_shop_idem UNIQUE (user_id, idempotency_key)
);
CREATE TABLE shop_order_items (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  shop_order_id BIGINT NOT NULL,
  product_id  BIGINT NOT NULL,
  name        VARCHAR(128) NOT NULL,
  qty         INT NOT NULL,
  unit_price  BIGINT NOT NULL,
  CONSTRAINT fk_item_order FOREIGN KEY (shop_order_id) REFERENCES shop_orders(id)
);

-- Implementasi LAMA (bug) untuk reproduksi insiden. Sengaja tanpa CHECK dan tanpa transaksi.
CREATE TABLE legacy_stock (
  campaign_id BIGINT PRIMARY KEY,
  allocation  INT NOT NULL,
  stock       INT NOT NULL
);
CREATE TABLE legacy_va (
  campaign_id BIGINT NOT NULL,
  user_id     BIGINT NOT NULL,
  PRIMARY KEY (campaign_id, user_id)
);
