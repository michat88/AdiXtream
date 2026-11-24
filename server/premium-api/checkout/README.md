# AdiXtream checkout sandbox

Use the [Firebase checkout](firebase/README.md) for AdiXtream Sandbox.
The Worker implementation below is retained for reference and regression tests.

This separate Worker entry point exercises checkout without routing through the existing
Premium Worker or writing to legacy Firebase RTDB. Its D1 entitlements are test data
keyed by Firebase Auth UID. The current Android app does not use this Worker.

## Staging setup

Use a separate Firebase Auth test project with verified test accounts and an isolated
D1 database for the `CHECKOUT_DB` binding. Apply `../migrations/0001_premium.sql` for the
shared auth/rate-limit tables and then `schema.sql` to that disposable database.
The handler requires `CHECKOUT_SANDBOX_ENABLED=enabled` and rejects the existing
`adixtream-premium` Firebase project. It contacts only the fixed Midtrans sandbox host.
Configure `MIDTRANS_SANDBOX_SERVER_KEY` and `MIDTRANS_SANDBOX_SIGNATURE_SECRET` as
server-side secrets in the test environment.

The trial catalog uses Rp10.000 for 30 days, matching the Android one-month display
price. Prices and durations come from the server. The QR window is 60 minutes.
A tester can seed `checkout_vouchers` with the SHA-256 hex of a random uppercase
voucher (12 to 40 characters from A–Z and 2–9), its duration and its expiry time
in UTC milliseconds.

## HTTP contract

| Method | Route | Result |
|---|---|---|
| GET | `/checkout/plans` | Sandbox packages |
| POST | `/checkout/orders` | Create or resume an order with `plan` and a UUID v4 `requestId` |
| GET | `/checkout/orders/open` | Resume an open order after restart |
| GET | `/checkout/orders/{id}` | Refresh an owned order and its QR, status and server time |
| GET | `/checkout/entitlement` | Current test membership status and expiry |
| POST | `/checkout/vouchers` | Redeem a test `code` once |
| POST | `/checkout/midtrans/webhook` | Signed gateway notification, verified again using Get Status |

Customer calls require a verified Firebase email account and bearer ID token.
Reuse the same request ID after a timeout. A verified settlement and its entitlement
extension commit together in D1. Retrying checkout, polling or receiving duplicate
webhooks cannot grant the same order twice.

## Verification and remaining work

Run `node --test test/checkout.test.mjs` from `server/premium-api` on Node 24.
Tests use an actual SQLite schema and a synthetic Midtrans transport. Live sandbox
checkout still requires test credentials and an isolated deployed environment.
Android registration, sign-in, email verification and checkout wiring, verified
legacy entitlement linking, refund/reconciliation policy and production activation
are outside this sandbox implementation.
