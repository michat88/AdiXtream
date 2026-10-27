# Server promo cutover status — 28 October 2026

**Production cutover remains BLOCKED.** This document is a source review, not a
record of a deployment, live database inspection or customer-data migration.

Consumer source baseline: `7fb3f8cff76e053dcd43dddbcb94953498b36618` on `master`.
Admin source reviewed: `e7cfc397ad24a66dee78b6f5d286ede9eab86acc` on `main`.
No production Firebase URL, token, customer identifier or activation code is
included here. GitHub access does not supply Firebase operator credentials.

## Observed writers and readers

| Active component | Relevant behavior | Cutover consequence |
| --- | --- | --- |
| Consumer `PremiumManager` / `AtomicPromoClaim` | Direct unauthenticated atomic multi-location promo PATCH through existing rules | Existing APKs cannot claim after those write permissions are removed; migrate and verify the active caller first |
| Consumer Premium recovery and code activation | Read the existing customer record by the unchanged business Device ID | Preserve recovery compatibility; that lookup is not a server-verifiable ownership credential |
| Admin subscription generation/renewal/reset | Authenticated per-user ETag read and conditional PUT, then read-back | Root CAS observes these writes as conflicts; stale user previews are rejected |
| Admin status, expiry correction, promo edit/reset/delete and customer cleanup | Authenticated PATCH/DELETE operations; expiry correction deliberately resets from the operator's selected date | Inventory these intentional controls; do not reinterpret reset as additive renewal or delete correction/claim receipts during cleanup |
| Admin legacy promo correction | Authenticated root CAS plus durable `_adixtream_admin_promo_repairs_v1` evidence | Server grant path must retain and consult this evidence, including after marker loss or customer recreation |
| Prepared server claim/receipt API | Verified session context plus stored owner binding checked on every CAS attempt | No live session verifier, binding issuer/recovery mechanism, production transport or host is configured |

The supplied legacy rules allow unprivileged numeric `expired_at` writes and
promo counter changes independently of verified ownership. Their defaults do not
override permissive child rules. The atomic consumer fix addresses partial writes;
it does not turn those permissions into an authorization boundary. This stage
neither tests live attack requests nor deploys replacement rules.

## Changes validated in this stage

1. The server checks durable Admin correction evidence before any new grant and
   before reporting a missing server receipt as an unknown operation. Any entry
   for that device/code blocks regrant and requires review, including malformed
   evidence. A repair never becomes a fabricated server receipt.
2. A concurrent Admin correction invalidates the server's root ETag; the next
   attempt checks the new evidence and does not add a second bonus.
3. Root reads include Firebase export metadata. Conditional root writes preserve
   unrelated object/primitive priorities and existing exact numeric values.
4. Existing ownership checks, current ban handling, quota contention, timeout
   receipt recovery and old marker handling remain required. No test is disabled.

Tests use synthetic data only. An emulator owner token is a fixture tool, not a
production authentication mechanism. No customer correction, entitlement,
repository configuration or Firebase rule is changed by this source commit.

## Required before enabling the active caller

- **Identity and automatic recovery:** supply independently verified ownership
  and a recovery credential/channel compatible with Clear Data. The current
  short Device ID, its hash, anonymous UID, public activation code, APK identity
  or App Check token alone must not be promoted to `VERIFIED_OWNER`. Bindings
  cannot be created by the first caller who knows a customer ID. The product's
  automatic recovery choice remains in force; no forced customer login or
  reactivation is silently substituted.
- **Live service:** configure an authenticated host and restricted runtime
  credentials without putting them in GitHub source, APKs or reports. Measure
  actual root size, contention and latency before approving root CAS capacity.
  Emulator success is not production capacity or credential verification.
- **Active app integration:** supply the authenticated transport, wire the
  existing persisted-intent coordinator into the current promo UI, and apply
  only current authoritative entitlement through the Premium recovery guard.
  A timeout must keep the original intent; it must never grant from a historical
  receipt alone, downgrade UNKNOWN to FREE or use a public direct-write fallback.
- **Coexistence and cutover:** verify the new app, Admin operations and automated
  Clear Data recovery together, retain a fresh protected backup of both ledgers,
  and decide how older direct-writing APKs are handled before closing writes.
  Keep the existing public-read exposure as a separate unresolved security item;
  do not claim that closing promo writes also secures all customer records.

Do not publish a rules-only change now. It would block working promo callers
without supplying a verified replacement. Do not delete an old redeemed marker
or a receipt to make a failed retry appear successful. No new customer test is
required merely to establish that the current server API is not yet live.

Primary references:

- https://firebase.google.com/docs/reference/rest/database
- https://firebase.google.com/docs/database/rest/save-data
- https://firebase.google.com/docs/app-check
- https://firebase.google.com/docs/auth/admin/verify-id-tokens
