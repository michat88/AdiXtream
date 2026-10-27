# Promo client preparation and ownership gate

Status: prepared for integration, not enabled in the active consumer UI.
The prepared owner/receipt client remains inactive. The active compatible promo
route now has a separate [atomic consistency fix](ATOMIC_PROMO_COMPATIBILITY.md);
automatic Premium recovery and the existing identity contract are preserved.
No production URL, verifier, new login flow, owner binding, Firebase rule or data
migration is added by this stage.

## Existing ownership evidence

The consumer derives the existing short business ID from `ANDROID_ID`. It does
not provide a server-verifiable ownership credential that survives Clear Data.
The Admin source examined at `187c3e4a942a79dad7fad373031e0eaa9ac70fa7`
has operator credentials and local app-lock verification, not a customer-owner
identity/recovery service. The previously audited backup had no owner bindings
or independently verified recovery identity under the inspected schema.

This is a genuine integration gate. Do not turn a hash of Device ID, a newly
created UID, an APK signature, a local Premium flag or an unverified HTTP header
into `VERIFIED_OWNER`. App Check is an origin/abuse signal, not proof that the
caller owns the requested legacy customer record. A Keystore installation key
alone also does not meet the requirement to recover when app-local state is gone.

The user's product choice remains automatic recovery on the existing Device ID,
without forced login/reactivation after Clear Data. No identity algorithm or
factory-reset detector is introduced. The current compatible recovery remains
available while the stricter ownership mechanism is unresolved.

## Prepared components

| Component | Responsibility |
| --- | --- |
| PromoClaimProtocol | Strict request/response schema and distinct current entitlement versus historical receipt |
| PromoClaimIntentFileStore | Compare-and-set pending intent with file lock, synced temporary write, rename and readback |
| PromoClaimCoordinator | Shared IO work, persist-before-network, receipt-first retry, explicit acknowledgement |

All three are internal and have no active UI/manager call site. No live
`PromoClaimTransport` implementation is supplied, so there is no hidden
fallback to a public Device-ID-only endpoint.

Future Android wiring must construct the store under `context.noBackupFilesDir`
to keep pending intent out of OS/cloud/device-transfer backup. The store contains
only protocol version, existing business ID, entered promo code, random intent
key and dispatch marker. It is not a credential store and never changes Premium
preferences. Its errors omit raw content, paths and identifiers.

The coordinator uses an application-owned coroutine scope and forces its work
onto `Dispatchers.IO`. A screen cancellation does not cancel the shared claim.
It serializes unresolved work per stored intent; a different code/device cannot
silently replace an uncertain operation. No request is sent if persistence fails.

After a timeout or process restart it queries the receipt with the original key.
Only a valid `UNKNOWN_RESULT` lookup allows a claim retry using that same key.
Malformed responses, transport failures and missing ownership stay pending.
The coordinator never computes an expiry or treats a network failure as Free.

`APPLIED` and `REPLAYED` describe the historical operation. The current
entitlement may be BANNED, EXPIRED, FREE or UNKNOWN. A successful receipt is not
permission to set Premium true. UNKNOWN preserves the pending operation.

## Future integration sequence

1. Supply a server-verifiable owner session and the appropriate binding/recovery
   mechanism. This step is not fulfilled by a test fixture or a Device ID lookup.
2. Supply the authenticated transport to the prepared server API, with bounded
   request/response deadlines and no raw response/URL logging.
3. Route the existing promo UI through one app-scoped coordinator. A new code is
   not allowed to replace an unresolved operation; provide retry/receipt recovery
   and explicit handling of terminal business errors.
4. Apply the **current** authoritative entitlement through PremiumRecovery's
   generation/storage protection, including BANNED/EXPIRED decisions. Do not
   grant from `receiptExpiry` or add promo days locally. Resolve any concurrent
   newer activation/revocation before accepting a response. This wiring has not
   been implemented or claimed complete in this stage.
5. Acknowledge the exact intent only after its result has been handled and the
   relevant entitlement persisted. A failed acknowledgement can be retried;
   stale acknowledgement cannot delete a newer intent.
6. Verify real Android Phone/TV update, network loss, process death and Clear Data
   scenarios, plus Admin/client coexistence, before production cutover.

The shared fixture `app/src/test/resources/adixtream-promo-contract-v1.json`
is checked by Kotlin parsing tests and by actual HTTP responses from the Node
server core. All identities and promo codes in it are synthetic. It covers a
new grant, replay while banned, uncertain result, missing ownership and an
ambiguous legacy claim. It does not prove a real ownership provider exists.

## Validation and limits

New Kotlin tests cover protocol rejection, persist-before-send, disk failures,
parallel submissions, caller cancellation, receipt lookup after restart,
same-key retry, current ban, unresolved entitlement, stale acknowledgement,
actual file roundtrip, corruption preservation and competing file-store instances.
The existing Premium recovery and server transaction suites remain required.

JVM disk roundtrip tests are not physical Android Clear Data tests or a guarantee
about every OEM/filesystem under power loss. Server pair receipts still provide
deduplication if local state is lost, after valid ownership is available.

No APK version bump is needed for this inactive preparation. Application ID,
signing, Device ID, branding, repository gating/bootstrap and current Premium
restoration remain unchanged. This stage does not solve public legacy rules or
complete the ownership gate; do not deploy a fabricated verifier to bypass it.

Primary references:

- https://firebase.google.com/docs/app-check
- https://developer.android.com/identity/user-data-ids
- https://developer.android.com/reference/android/provider/Settings.Secure#ANDROID_ID
