# AdiXtream checkout on Firebase

This checkout uses Cloud Functions, Firebase Authentication and Firestore. The
Firebase project for a future Midtrans Sandbox deployment is
`adixtream-checkout-sandbox`. The previously released Android app still uses
its existing Premium integration.

## Local verification

Run from this directory with Node.js 24 and Java 21.

```sh
npm ci --prefix functions
npx --yes firebase-tools@15.37.0 emulators:exec --only auth,firestore \
  --project demo-adixtream-checkout 'npm --prefix functions test'
```

The tests use real Auth and Firestore emulators. They call the HTTP function over
loopback and inject a simulated Midtrans transport into the checkout handler.
No Midtrans credentials, Cloud Secret Manager access or cloud database are needed.

## HTTP contract

Use `/checkout` under the `checkoutSandbox` HTTP function. Customer requests
use a Firebase ID token for a verified email account.

| Method | Path | Use |
|---|---|---|
| GET | `/checkout/plans` | Test plan of Rp10,000 for 30 days |
| POST | `/checkout/orders` | Create or resume a QRIS order |
| GET | `/checkout/orders/open` | Resume the user's open order |
| GET | `/checkout/orders/{id}` | Read the user's order and refresh payment status |
| GET | `/checkout/entitlement` | Read the user's Premium expiry |
| POST | `/checkout/vouchers` | Redeem a test voucher |
| POST | `/checkout/midtrans/webhook` | Receive signed Midtrans HTTP notifications |

For order creation, send the plan ID `premium-30d` and a UUID v4 `requestId`.
Reuse the request ID after a timeout. Firestore commits each confirmed payment
or voucher redemption and the associated Premium extension in one transaction.

## Future hosted test

After the separate Firebase project is approved for Blaze, configure Email/Password
Authentication, Firestore with these server-only rules, and the two Cloud Functions
secrets in that project. Their names are `MIDTRANS_SANDBOX_SERVER_KEY` and
`MIDTRANS_SANDBOX_SIGNATURE_SECRET`. Set `CHECKOUT_SANDBOX_ENABLED=enabled`
only in that test deployment. The backend accepts the configured Sandbox project ID
and uses only the Midtrans Sandbox API.

Configure Midtrans Sandbox Notification URL with the deployed function's HTTPS URL
followed by `/checkout/midtrans/webhook`. A hosted webhook and a Midtrans simulator
transaction must be verified before wiring in an Android test build.
