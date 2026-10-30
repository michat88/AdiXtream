# Central Premium API v2 — source implementation, production locked

This is an additive Worker wrapper around the previously supplied stage-12 pilot.
It has NOT been deployed by this change. Existing pilot paths are delegated unchanged.
`wrangler.example.toml` is an example, not the deployed configuration. Preserve all
existing bindings/secrets/routes if an operator later prepares a deployment.

## Implemented contract

All v2 requests require a verified Firebase RS256 ID token for the configured
project. Admin requires the independently configured `ADMIN_UID`; ordinary Google
login is insufficient. D1 revocation records and a per-UID 60/minute limiter apply.

- `GET /api/v2/admin/capabilities`: authenticated readiness; no Firebase call.
- `GET /api/v2/admin/customers/{deviceId}`: entitlement and legacy unlock code, Admin only.
- `GET /api/v2/admin/history/{deviceId}`, `/admin/promos`: bounded Admin reads.
- `POST /api/v2/admin/renew`, `/admin/unlock`: request_id UUID v4, device_id,
  days (1..36500), order_ref. The resulting expiry must also fit the stricter
  five-year horizon and year-2100 ceiling. Existing unlock codes cannot be reset.
- `POST /api/v2/admin/promos`: request_id, code, days, max_quota, valid_until,
  status (`aktif`/`paused`), expected_revision (0=create), order_ref.
- `POST /api/v2/premium/entitlement`: device_id. Read-only business operation.
- `POST /api/v2/premium/unlock`: request_id, device_id, unlock_code. Activates
  recognition of an existing valid entitlement; does not add days.
- `POST /api/v2/premium/promo`: request_id, device_id, promo_code. Server selects days.
- `GET /api/v2/{admin|premium}/operations/{requestId}` and `POST .../{requestId}/retry`
  with `{}`: same-operation inspection/reconciliation, never a new grant request.

Customer routes additionally require a D1 VERIFIED_OWNER binding for that UID and
device. No enrollment endpoint is exposed. Device ID, public legacy activation
code, APK identity and local flags are NOT ownership evidence. Enrollment and
post-Clear-Data account recovery remain explicit production blockers.

Responses carry contractVersion 2. Mutations succeed only with outcome APPLIED,
a confirmed receipt and a freshly read currentEntitlement. Historical receipts
are not current licenses. PENDING/UNKNOWN_RESULT is never success. Clients must
retain the request ID after any uncertain result; there is no Firebase fallback.

## D1 / Firebase boundary

`0001_premium.sql` adds namespaced tables without changing the pilot tables.
D1 batch atomically reserves request, account lock, promo quota and unique claim.
A prepared Firebase full-record PUT preserves exact JSON numeric lexemes and uses
ETag compare-and-set. An operation marker in the same user record allows recovery
if Firebase applied but the response/D1 completion was lost. Only then is D1 marked
COMPLETED. This is a recoverable workflow, NOT an atomic transaction across services.

No automatic lock expiry, quota refund, delta replay after conflict or old-snapshot
restore is allowed. RECONCILIATION_REQUIRED keeps the reservation/lock for review;
it must not be cleared without evidence. The same operation can confirm an already
applied matching marker. Markers are retained; D1 receipts contain actor/order data.
With legacy public per-user reads, Firebase operation markers (fingerprint, expiry,
revision, time) are also readable. No token/operator UID/order/activation code is
added to these markers. Existing legacy customer data remains publicly readable
under Emergency Rules; write containment does not provide confidentiality.

Legacy promo markers plus both private repair/claim ledgers are checked before a
grant. This does not make concurrent old root-level repair writers atomic with D1.
Before activation, old promo repairs/server claims must be disabled or coordinated
through the same lock, and promo configuration/quota must have one authoritative
writer. Ban/revocation/ownership changes must likewise coordinate account locks;
ETags prevent stale overwrites but cannot provide a global cross-service lock.

## Locks and deployment gates

`PRODUCTION_FIREBASE_ENABLED=false` is a compile-time lock, not an environment
switch. The handler additionally permits data operations only in explicitly enabled
synthetic scope. The transport accepts only localhost demo Firebase Emulator while
locked. Production credentials are never needed/read for tests. Future server-only
OAuth credentials belong in Worker secrets, never Git or either application.

The OAuth transport is prepared but production IAM, credential type, App Check,
live rules and instance binding have NOT been verified. Auth signature validation
is tested with synthetic keys; a Firebase Auth revocation/disabled-user feed into
D1 and live Google/native OAuth tests remain required. No production activation,
D1 migration or Worker deploy is performed by this repository change.

Before activation: isolated staging, actual Firebase Emulator transport tests,
owner enrollment/recovery policy and implementation, native Admin login, device
4.8.5/4.8.7 compatibility, backup of full deployed rules/data/both legacy ledgers,
new D1 ledger/markers, synthetic fault reconciliation, operator approval and
monitoring. Roll back clients by disabling the new path; never reopen public write,
remove markers/receipts/bans or overwrite newer transactions with an old backup.

## Local validation

`npm install` then `npm test` in this directory uses Miniflare's real SQLite/D1
binding and synthetic token signing. Firebase in this suite is an injected exact
JSON/ETag transport with fault injection, NOT production and NOT a Firebase Emulator
end-to-end test. The legacy stage-12 test series is not repeated unchanged.
No Android APK/device, production authentication or Cloudflare deployment is proven
by these tests.

Additional transport validation: set `FIREBASE_EMULATOR_JAR` to a local Database
Emulator jar and run `npm run test:firebase`. The runner starts Java and tests in
one local process environment, binds only 127.0.0.1:19185 and uses a fresh `demo-`
namespace. Three integration tests exercise the real transport, Emergency reads,
public-write denial, local D1 grants, ETag conflicts and bans. `Bearer owner` is an
emulator-only test bypass and proves nothing about production administrator IAM.
No production URL or production credential is accepted by this runner.

New promo codes are limited to 1–10 uppercase characters/digits/underscore/hyphen
to fit the existing native input. Each paid Admin decision requires a distinct
order_ref per device; a unique D1 index prevents double grants under different
request IDs for the same order. There is no automatic override of that guard.
