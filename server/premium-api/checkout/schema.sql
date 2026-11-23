-- Isolated checkout sandbox database. Apply after migrations/0001_premium.sql.
CREATE TABLE IF NOT EXISTS checkout_entitlements (
 uid TEXT PRIMARY KEY NOT NULL,
 expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS checkout_orders (
 id TEXT PRIMARY KEY NOT NULL,
 uid TEXT NOT NULL,
 request_id TEXT NOT NULL,
 amount INTEGER NOT NULL CHECK (amount > 0),
 days INTEGER NOT NULL CHECK (days > 0),
 state TEXT NOT NULL DEFAULT 'CREATING' CHECK (state IN ('CREATING','PENDING','PAID','EXPIRED','CANCELED')),
 transaction_id TEXT UNIQUE,
 qr_url TEXT,
 created_at INTEGER NOT NULL,
 expires_at INTEGER NOT NULL,
 paid_at INTEGER,
 retry_at INTEGER NOT NULL DEFAULT 0,
 UNIQUE (uid, request_id)
);

CREATE UNIQUE INDEX IF NOT EXISTS checkout_one_open_order ON checkout_orders(uid)
 WHERE state IN ('CREATING','PENDING');

CREATE TRIGGER IF NOT EXISTS checkout_apply_payment AFTER UPDATE OF state ON checkout_orders
 WHEN OLD.state <> 'PAID' AND NEW.state = 'PAID'
 BEGIN
  INSERT INTO checkout_entitlements(uid, expires_at)
  VALUES (NEW.uid, NEW.paid_at + NEW.days * 86400000)
  ON CONFLICT(uid) DO UPDATE SET expires_at = MAX(expires_at, NEW.paid_at) + NEW.days * 86400000;
 END;

CREATE TABLE IF NOT EXISTS checkout_vouchers (
 code_hash TEXT PRIMARY KEY NOT NULL,
 days INTEGER NOT NULL CHECK (days BETWEEN 1 AND 365),
 valid_until INTEGER NOT NULL,
 redeemed_uid TEXT,
 redeemed_at INTEGER
);

CREATE TRIGGER IF NOT EXISTS checkout_apply_voucher AFTER UPDATE OF redeemed_uid ON checkout_vouchers
 WHEN OLD.redeemed_uid IS NULL AND NEW.redeemed_uid IS NOT NULL
 BEGIN
  INSERT INTO checkout_entitlements(uid, expires_at)
  VALUES (NEW.redeemed_uid, NEW.redeemed_at + NEW.days * 86400000)
  ON CONFLICT(uid) DO UPDATE SET expires_at = MAX(expires_at, NEW.redeemed_at) + NEW.days * 86400000;
 END;
