-- Additive schema. Do not run against customer production without separate approval.
CREATE TABLE IF NOT EXISTS premium_v1_bindings (
 device_id TEXT PRIMARY KEY, owner_uid TEXT NOT NULL,
 status TEXT NOT NULL CHECK(status IN ('VERIFIED_OWNER','REVOKED')),
 verified_by TEXT NOT NULL, verified_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS premium_v1_promos (
 code TEXT PRIMARY KEY, days INTEGER NOT NULL CHECK(days BETWEEN 1 AND 36500),
 max_quota INTEGER NOT NULL CHECK(max_quota>=0), used_count INTEGER NOT NULL DEFAULT 0 CHECK(used_count>=0 AND used_count<=max_quota),
 valid_until INTEGER NOT NULL, status TEXT NOT NULL CHECK(status IN ('aktif','paused')),
 revision INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS premium_v1_operations (
 request_id TEXT PRIMARY KEY, actor_uid TEXT NOT NULL, actor_role TEXT NOT NULL,
 fingerprint TEXT NOT NULL, kind TEXT NOT NULL, device_id TEXT NOT NULL,
 promo_code TEXT, days INTEGER NOT NULL CHECK(days BETWEEN 0 AND 36500),
 state TEXT NOT NULL CHECK(state IN ('PENDING','PREPARED','COMPLETED','RECONCILIATION_REQUIRED')),
 order_ref TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
 firebase_etag TEXT, firebase_before TEXT, firebase_after TEXT, firebase_payload TEXT,
 result_json TEXT, last_error TEXT,
 UNIQUE(request_id,actor_uid)
);
CREATE TABLE IF NOT EXISTS premium_v1_account_locks (
 device_id TEXT PRIMARY KEY, request_id TEXT UNIQUE NOT NULL REFERENCES premium_v1_operations(request_id)
);
CREATE TABLE IF NOT EXISTS premium_v1_claims (
 promo_code TEXT NOT NULL, device_id TEXT NOT NULL,
 request_id TEXT NOT NULL UNIQUE REFERENCES premium_v1_operations(request_id),
 PRIMARY KEY(promo_code,device_id)
);
CREATE INDEX IF NOT EXISTS premium_v1_history ON premium_v1_operations(device_id,created_at);
CREATE TABLE IF NOT EXISTS premium_v1_revocations (
 uid TEXT PRIMARY KEY, revoked_before INTEGER NOT NULL DEFAULT 0, disabled INTEGER NOT NULL DEFAULT 0 CHECK(disabled IN (0,1))
);
CREATE TABLE IF NOT EXISTS premium_v1_rate_limits (
 uid TEXT NOT NULL, bucket INTEGER NOT NULL, requests INTEGER NOT NULL CHECK(requests BETWEEN 1 AND 60), PRIMARY KEY(uid,bucket)
);
