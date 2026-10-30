# Central Premium implementation and activation gates

## Source scope

Android changes are limited to PremiumManager and three new CentralPremium modules.
VersionName 4.8.7 / versionCode 94, applicationId, signing, streaming, repositories,
provider/plugin implementations and update/release mechanisms are unchanged.
Worker source is isolated under server/premium-api. It is not a deployed service.
Admin integration lives in the separate AdminPopUp main branch.

`CentralPremiumFeature.ENABLED=false` preserves the existing license storage and
Firebase GET recovery/activation path. It does not clear, normalize or truncate
legacy expiry, including abnormal values pending investigation. The active promo
entry point now explicitly pauses public-write claims and directs customers to
Admin assistance. The old AtomicPromo helper is retained for its existing tests
and historical reference but is no longer instantiated by PremiumManager.
No public-write fallback is introduced. This source change does not update installed
4.8.5/4.8.7 APKs and no APK/release is built or distributed by this implementation.

## Native implementation

CentralPremiumApi uses the fixed HTTPS Worker origin, Firebase session Bearer,
strict contractVersion 2/current-entitlement parsing, bounded response size/I/O,
no redirects and no automatic mutation retry. No administrator credential enters
Android. Device ID alone is never authenticated as an owner. A verified Firebase
session provider is an explicit interface; no provider or enrollment shortcut is
installed while owner identity/recovery is unresolved. The server requires a D1
VERIFIED_OWNER binding independently of all client flags/codes.

CentralPremiumCoordinator persists a private no-backup intent before submission.
Restart/retry inspects the original ID; an operation not yet created is submitted
with that same ID. Conflicts/unknown outcomes do not generate a replacement ID.
Only a matching receipt AND a fresh current entitlement can be recorded locally.
The shared PremiumRecovery generation prevents a stale response overwriting a
newer local decision. A receipt followed by a current ban records BANNED, not
Premium. Local save failure/corrupt intent retains the pending request for review.
No token or entitlement is written in the pending-intent file. Unlock codes in
that private intent must remain confidential. Clear Data can remove this intent;
server history/owner recovery must handle that case before activation.

## Customer compatibility boundaries

| Condition | Installed 4.8.5 / original 4.8.7 | Updated source, new path OFF | Gate / mitigation |
|---|---|---|---|
| Active normal Premium | No APK change; Emergency reads must remain available | Existing local/recovery logic unchanged | Physical-device checks still required |
| Extreme expiry, unresolved | No automatic correction | Legacy parser/storage unchanged | Preserve exact raw evidence; no heuristic downgrade |
| Expired account renewed by Admin | Existing GET/sync needed | Existing recovery unchanged | Validate real Admin credential in staging; no promise while offline |
| Free customer purchases Premium | Admin provision + legacy code/GET path | Same legacy activation path | Pre-provision record; do not rely on denied client registration writes |
| Banned | Existing server/local checks | Existing checks; new path uses current state | Offline legacy revocation delay remains; never clear ban |
| Promo previously used | Existing markers/ledgers remain | New claim paused | Reconcile both old ledgers before assisted grants |
| Promo in flight at rule change | Legacy partial writes may have occurred | No new public claim attempt | Preserve markers/quota; inspect receipt and delta before assistance |
| Restart / Clear Data | No recommendation to clear; recovery depends on existing GET/device identity | Existing recovery retained | New owner-session recovery remains BLOCKED until implemented |
| Temporarily offline | Existing cached-license policy | Existing policy unchanged | No forced downgrade on network error; actual device behavior unverified |
| Local-only entitlement | No database mutation performed | Existing secure/fallback preferences retained | Preserve device evidence; no forced sync/reset/mass expiry update |
| Free/Premium repository/provider availability | Installed code unchanged | No repository/provider files changed | Entitlement-dependent startup still requires device regression tests |

This is source/regression evidence, not certification of installed APK behavior.
Closing public reads is a separate migration: inventory every legacy GET and
activation/recovery dependency, establish independently verified owner enrollment,
restore sessions after Clear Data and provide an assisted path before restricting
reads. Emergency write denial alone does not prevent legacy customer-code exposure.

## Phase 6/7 production gates (not executed)

1. Confirm exact deployed Firebase project/instance/rules/App Check read-only;
   preserve full deployed rules, full snapshot, both private legacy ledgers and
   existing histories before separately approved changes.
2. Isolated staging project/instance, synthetic D1 and Worker route only. Confirm
   endpoint/binding/IAM allowlists before allowing credentials. Never test against
   customer data. Native Admin Google OAuth requires correct public client ID,
   registered package/SHA and server UID allowlist; ordinary Google login is not Admin.
3. Implement verified account/device owner enrollment and account recovery. A short
   Device ID or publicly readable legacy code is inadequate evidence. Resolve
   multi-device policy and binding/revocation coordination before enabling Android.
4. Provide protected server OAuth credentials and least feasible Firebase IAM;
   validate Admin renew/ban/unban/expiry-correction equivalents in staging. The
   legacy Admin functions remain unchanged until replacements pass. New backend
   renewal explicitly refuses banned/ambiguous/extreme records; it is not a generic
   ban/unban or forensic correction endpoint.
5. Stage additive D1 migration, reconcile promo inventory/quotas and both old
   ledgers. Stop or coordinate legacy root repair/claim writers and every ban or
   owner reassignment with new account locks. Do not reset markers or consumed quota.
6. Verify Firebase Auth revocation/disabled-user propagation into D1, monitoring,
   rate-limit retention, access logging, bounded receipt retention and recovery
   procedures. D1 audit records are not immutable against a privileged operator;
   preserve independent platform audit evidence. WAF/abuse protection is also an
   activation prerequisite; the implemented limiter applies after authentication.
7. Require device 4.8.5/original 4.8.7/new-client tests and full Android/native Admin
   builds. No baseline/source comparison can substitute for these tests.
8. Obtain explicit production activation approval. Changing a runtime environment
   variable alone cannot unlock Firebase; compile-time/server/client locks remain.

## Partial-failure and rollback procedure

D1/Firebase are not one transaction. D1 reserves quota and an account lock, stores
an immutable ETag/payload plan, then Firebase conditionally writes entitlement plus
an operation marker. D1 completes only after the marker is read back. A lost reply
reconciles by that marker; an unknown CAS conflict holds the lock/quota for review.
Do not expire locks, refund quotas or re-add days automatically. No manual-resolution
endpoint is exposed for ambiguous cases; operator evidence review is required.

On an outage: freeze new mutations, retain request IDs, inspect current Firebase
state, private legacy receipts, D1 state and operation markers. Confirm whether a
grant applied before retrying or assisting. Keep existing license reads/cached data
and legitimate Admin service available under their verified privilege boundary.
Never reopen public write, restore an old snapshot over new sales, erase a ban,
remove a receipt/marker, mass-move customers to Free or award an unverified bonus.
Restore backups first to an isolated environment and reconcile newer transactions;
production restore/correction requires a separate reviewed customer-specific plan.

## Validation performed for this implementation

- 22 Worker/real SQLite-D1 integration tests, including JWT signatures, nonadmin,
  expiry, concurrent claims, quota, idempotency, lost replies, stale ETags, D1
  finalization loss, historical receipt/current ban, promo revision rollback and
  actual Worker module-graph startup in workerd.
- 3 additional real Firebase Database Emulator + local D1 tests: Emergency legacy
  GET/public-write denial, backend promo/renewal preservation, CAS conflict and ban.
- 43 JVM JUnit tests (11 new central-client tests + 32 existing PremiumRecovery).
  New native core compiled with Kotlin 2.4.0, coroutines 1.11.0 and org.json 20260522.
- Admin has 98 targeted API/domain/service/security/UI/reconciliation tests passing.
  Release artwork/config tests require assets not present in the partial local
  checkout and were not certified; release/build/signing files were not changed.

NOT VERIFIED: full Gradle Android build (no SDK), native Admin build/Google login,
physical 4.8.5/4.8.7 devices, production Firebase auth/IAM/App Check/rules, live Worker
source/configuration, Cloudflare deploy or D1 migration. No Actions build or release
was triggered intentionally; source commits use [skip ci].

New promo creation is capped at the existing native ten-character input limit.
Paid Admin orders are unique per device/order_ref across renewal and code creation;
a genuinely separate purchase needs a separate business reference. Reusing a lost
request under a new ID cannot charge the same order into entitlement twice.
