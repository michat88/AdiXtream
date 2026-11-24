# AdiXtream checkout on Supabase

This deployment targets Supabase project `irompgoosrqjbmicyusa` and Firebase Auth
project `adixtream-checkout-sandbox`. The gateway accepts Midtrans Sandbox keys
and uses the Midtrans Sandbox API. Android needs a separate test configuration
before it can use this backend.

## Setup

Apply the migration in `migrations`, then deploy `checkout-sandbox`. The database
ledger lives in a private schema with row-level security. The checkout RPC is
callable only by the server role and applies each confirmed payment and its
Premium extension in one Postgres transaction.

Set the following as Supabase Edge Function secrets:

- `MIDTRANS_SANDBOX_SERVER_KEY`
- `MIDTRANS_SANDBOX_SIGNATURE_SECRET`
- `FIREBASE_SANDBOX_WEB_API_KEY` from the separate Firebase Auth test project

Enable Email/Password in the test Firebase project and use a verified test email.
Customer routes require a signed Firebase ID token and check current account status
through Identity Toolkit. The webhook verifies the Midtrans signature and reads
the authoritative payment status before updating Premium. These checks run
inside the function, so Supabase's gateway-level JWT requirement is disabled for
this one function.

The Midtrans Sandbox Payment Notification URL is:

```text
https://irompgoosrqjbmicyusa.supabase.co/functions/v1/checkout-sandbox/checkout/midtrans/webhook
```

## Local tests

```sh
npm ci --prefix test
npm --prefix test test
deno check functions/checkout-sandbox/index.ts
```

The tests use an in-memory PostgreSQL instance, synthetic Midtrans responses, and
signed test Firebase tokens. A completed QRIS simulator transaction on Midtrans
Sandbox must still be verified before Android integration.

The HTTP contract is the same as the [Firebase local checkout](../firebase/README.md)
under the Supabase function URL. Trial checkout uses Rp10,000 for 30 days.
