# AdiXtream promo claim server core

Status: implemented and tested locally against synthetic fixtures and the
Realtime Database Emulator. **Not deployed and not connected to either APK.**
This package has no production URL, credential loader, public listener or deploy
command. Its only database transport constructs a loopback URL and requires a
`demo-` namespace. No Firebase production rules are supplied or changed.

## Purpose

The existing consumer marks a promo, increments its counter and extends expiry
in separate REST requests. This core instead commits the marker, counter, user
expiry/revision and private receipt in one conditional write. It preserves the
existing `users` and `promo_codes` locations; the new private ledger is
`_adixtream_claims_v1` at their common ancestor.

An ETag conflict re-reads the complete latest snapshot and repeats validation.
There is no process-local lock dependency, no unconditional root PUT, and no
fallback to three PATCH calls. Retry callbacks have no side effects outside the
conditional write. Root transactions remain a production design gate: this
implementation does **not** approve their cost or capacity for a live database.

## Run the tests

Requires Node 24 (source-aware `JSON.parse` / `JSON.rawJSON`) and, for integration
tests, Java 17. There are no npm dependencies and no login or Firebase CLI is
needed. Tests do not load customer backups.

```bash
cd server/promo-claims
npm test
```

Download the official database emulator JAR 4.11.2 to a location outside the
repository and run:

```bash
ADIXTREAM_TEST_EMULATOR_JAR=/absolute/path/firebase-database-emulator-v4.11.2.jar npm run test:emulator
```

Official download:
https://storage.googleapis.com/firebase-preview-drop/emulator/firebase-database-emulator-v4.11.2.jar

The runner requires SHA-256
`b70d99344caf17c98b6f910fa8f6edf32a7c016cb1035e8915f70d38901eb97f`.
It starts the emulator on loopback port 19002, uses
`demo-adixtream-promo-v1`, sets deny-all rules for unauthenticated test clients,
and removes fixtures/stops the emulator afterward. Do not run another test on
that port concurrently. The literal `Bearer owner` is an emulator-only privilege,
never a production credential. Synthetic identity fixtures exist only under
`test/`; they are not an authentication adapter.

The additional GitHub workflow runs the same tests with no secrets or deployment.
The existing Android release workflow is unchanged.

## API boundary

`createPromoHandler({ service, verifySession })` is an unbound Node HTTP handler.
If no verifier is supplied it denies every request before database access. A
hosting adapter must verify a real session and return a trusted principal;
`deviceId`, a JSON flag, an arbitrary UID, local Premium state and an App Check
token alone are not ownership proof. Do not deploy with the test verifier.

| Route | Method | Purpose |
| --- | --- | --- |
| `/v1/promo/claim` | POST | Claim or replay one device/promo operation |
| `/v1/promo/receipt` | POST | Read a prior pair receipt after an uncertain result or lost local intent |

Both routes accept only `application/json`, at most 4096 bytes, with exactly:

```json
{
  "contractVersion": 1,
  "deviceId": "11111111",
  "promoCode": "EXAMPLE",
  "idempotencyKey": "persistent_random_intent_key"
}
```

The example is synthetic. Device IDs keep the existing 1–8 digit format and the
existing Kotlin `Int.MIN_VALUE` edge case `-2147483`. No algorithm is changed.
Promo codes are trimmed and uppercased, then must contain 1–64 ASCII letters,
digits, underscores or hyphens. Intent keys are 16–100 characters in
`[A-Za-z0-9_-]`, generated/persisted by the future client before sending.
Owner, bonus days, expiry, quota and time supplied in request JSON are rejected.

Responses contain only contract version, operation ID, outcome, server time,
retryability, minimal receipt and **current** entitlement. They exclude activation
codes, raw user records, credentials and repository URLs. Errors contain fixed
identifiers, never exception messages. Responses use `Cache-Control: no-store`.

| Outcome | Meaning |
| --- | --- |
| APPLIED | One new atomic commit succeeded |
| REPLAYED | Existing receipt; no extra bonus or quota use |
| AUTH_REQUIRED / OWNERSHIP_REQUIRED | No verified session/binding; never create a first-claim binding |
| IDEMPOTENCY_CONFLICT | Same principal/key used for a different device/promo |
| LEGACY_RECONCILIATION_REQUIRED | Legacy marker exists without an authoritative receipt; do not regrant |
| BANNED / PAUSED / EXPIRED / QUOTA_EXHAUSTED / PROMO_NOT_FOUND | Specific business rejection |
| PROMO_DATE_CONFLICT | Existing expiry aliases disagree; operator review required |
| DATA_INVALID / DATA_RECONCILIATION_REQUIRED | Invalid or unrepresentable business data; do not guess |
| UNKNOWN_RESULT | Write response uncertain or receipt not yet found; retry/lookup with the same intent |
| CONTENTION_RETRY_REQUIRED / TEMPORARY_UNAVAILABLE | Bounded retries exhausted or service unavailable |

A missing receipt is not proof that another in-flight operation cannot commit.
Never create a new client intent automatically after timeout. Lookup can also
find the pair receipt with a new key after local state loss, **after ownership
verification**. Replaying an old receipt for a now-banned user returns current
`BANNED`; receipt success must never be interpreted as a new Premium grant.

## Transaction invariants and compatibility

- Principal verification happens before database access, and the persisted
  `ownerBindings[deviceId]` status/principal is checked inside every CAS attempt.
  This package never creates or recovers bindings.
- User status, current promo state, both date aliases, quota, prior receipt and
  legacy claim marker are revalidated from the latest snapshot. A ban, pause,
  renewal or binding revocation concurrent with the read forces revalidation.
- Expiry is `max(latest_user_expiry, server_time) + days * 86400000`; no client
  clock is accepted. The server timestamp is sampled after each read. This is
  decision time, not a guarantee that network completion precedes the promo
  deadline to the millisecond.
- Identical concurrent requests and different keys for the same device/code
  resolve to one receipt. Two different promos on one device retain both grants.
- Private pair receipts survive counter reset or deletion/recreation of the
  user/promo if operators preserve the ledger. A deleted user cannot be silently
  recreated by a claim. Retention/administrative deletion policy is still a
  production gate; do not delete the ledger to reset a campaign.
- Existing fields, activation codes, preference names and device IDs are not
  rewritten. Successful claim updates only the relevant marker, counter, expiry,
  revision, last_update, date aliases and private receipt/request map.
- Legacy promo customers with absent status and a real boolean-true previous
  marker can claim a different code without status backfill. A marker for the
  requested code always requires reconciliation if no private receipt exists.
- Numeric seconds, millisecond values, supported numeric strings and strict UTC
  ISO timestamps are recognized. Conflicting promo date aliases are never
  resolved by choosing the larger. Missing aliases are filled only in a
  successful claim. The 36500-day upper bound matches the current Admin validator.
- Every existing JSON number is read with its original token and serialized
  without IEEE-754 rounding. Large/decimal values elsewhere in the root do not
  block a claim or change silently. An unsafe **target** expiry requires
  reconciliation and is not clamped, added to or normalized in place.

## Remaining gates before any live use

1. Provide a verified ownership/recovery mechanism compatible with automatic
   recovery after Clear Data. The currently short Device ID alone is not proof;
   no login requirement or identifier migration is introduced here.
2. Choose/measure server hosting, restricted service credentials, read/write
   scope, root size, latency, contention, rate limits and receipt retention. This
   repo includes no production credential or production transport to enable by
   changing one flag. Local synthetic load is not production capacity evidence.
3. Integrate a persisted pending intent and minimal result mapping in AdiXtream,
   and preview/versioned operations in Admin. Test old/new client coexistence.
4. Reconcile ambiguous legacy claims without deleting valid Premium state or
   automatically counting old markers as an unpaid bonus.
5. Only after verified recovery, compatible clients, backups and writer
   inventory pass, schedule the separate rules cutover. Old direct writers can
   overwrite new decisions while old permissions remain, even though each new
   server transaction itself is atomic.

No production cutover, dependency upgrade, APK version bump, Android source
change, Admin source change, Firebase data migration or secrets change is part
of this preparation stage. The working Premium recovery in 4.8.6 remains intact.

## Primary technical references

- Firebase conditional requests / ETag:
  https://firebase.google.com/docs/database/rest/save-data#section-conditional-requests
- Realtime Database Emulator:
  https://firebase.google.com/docs/emulator-suite/connect_rtdb
- Source-preserving JSON parse and raw JSON:
  https://tc39.es/proposal-json-parse-with-source/
