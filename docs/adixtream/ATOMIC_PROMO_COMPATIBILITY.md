# Active promo consistency fix

The active `PremiumManager.activatePromoWithCode` route now uses
`AtomicPromoClaim` and `AtomicPromoRestTransport`. Both the subscription screen
and the legacy settings caller reach this route. This is a compatibility fix for
the existing REST/rules contract, separate from the prepared verified-owner API.

## Defect and resulting behavior

Previously, three independent requests wrote a redemption marker, quota and
expiry, in that order. Losing the connection after the first request could leave
a permanent marker without a bonus. The quota response was also ignored.

The replacement sends one root multi-location PATCH containing exactly the
device/code marker, promo counter, device expiry and last_update. It does not
replace root data, a complete user object, a promo object, or other customer data.
The existing child rules authorize these paths and Firebase either accepts all
four changes or rejects them all. No split-write fallback exists.

An explicit permissions rejection causes at most one fresh read/retry. This
handles a stale quota counter. A timeout, transport error or server error instead
causes a read-back, never an automatic write replay. Success requires the marker,
the expected bonus reflected in the current expiry, a current Premium decision,
a server clock and successful local license synchronization. A newer local
recovery/activation/ban decision prevents an older verification GET overwriting
it through the recovery revision guard. Unknown outcomes retain existing local
entitlement and do not manufacture a Free/Premium decision.

The application owns one shared operation. Identical concurrent inputs await the
same result; a different promo cannot overlap unresolved work. Screen destruction
does not cancel persistence. The screen no longer releases its promo busy state
on the old arbitrary 30-second UI timer. Each actual REST exchange has a 6-second
call deadline, 5-second connect/read/write limits, no automatic network retries,
no redirects, no cache, no Admin credential and a bounded response body.

## Compatibility and limits

- Existing future numeric millisecond expiries use a Firebase server increment,
  preserving compatible numeric renewals and other bonuses committed after the
  initial read. Local expiry always comes from a verification GET.
- Free/expired, missing, numeric-second and ISO records retain the existing
  calculation `max(server time, existing expiry) + bonus`, in milliseconds.
  These use an absolute expiry. Arbitrary concurrent Admin/older-client changes
  during that conversion are not fully serialized by the existing rules.
  A privileged server transaction and a coordinated rules transition are still
  needed for that broader guarantee. Atomic PATCH is not a root CAS transaction.
- Current app callers reject known banned users and respect a ban observed during
  verification. The legacy rules still do not enforce ownership or ban checking
  on the server. This fix must not be described as closing those security gaps.
- Promo dates use `valid_until`; a conflicting optional `expired_at` alias,
  malformed numbers, unsupported dates and unsafe expiry arithmetic fail closed
  without consuming a marker. Oversized legacy paid expiries remain readable by
  automatic recovery; this claim path does not rewrite them.
- Existing true markers have no receipt proving that the old bonus was delivered.
  They are retained and return an explicit message to check expiry/contact Admin.
  This release does not delete markers, reset quota or automatically compensate
  historical partial claims. Those require individual reconciliation against
  current server data and the retained backup before an Admin adjustment.
- Clear Data recovery remains based on the same Device ID and existing entitlement
  fields. There is no new login, identifier, data migration or recovery requirement.
  A committed atomic claim remains recoverable from the server after process death
  or Clear Data even when its response never reached the original screen.

No production Firebase data or rules are changed as part of preparing, testing or
pushing this code. No backend deployment is needed for this compatibility route.
The stricter prepared owner/receipt API is still inactive; its unresolved owner
identity and deployment gates remain documented separately.

## Validation scope

Kotlin tests cover the actual active claim engine, application-owned sharing,
screen cancellation, unknown/lost results, legacy formats, malformed data, bans,
local persistence and recovery revision ordering. Real loopback HTTP tests cover
paths, PATCH override, no credential, no redirects/replay, size limit and timeout.

The shared synthetic payload is checked against the Kotlin request generator and
the official RTDB emulator using the exact previously supplied legacy rules.
Emulator tests cover atomic rejection, duplicate requests, last-quota contention,
numeric renewal/concurrent bonuses, incomplete HTTP body, lost reply and legacy
partial markers. They use only synthetic data under a demo namespace; the literal
emulator owner credential is used only for fixture/rule setup and inspection.
Actual claim requests in these compatibility tests are unprivileged.

These checks do not constitute physical Android phone/TV testing or a production
security audit. Release CI also compiles the signed Android app, runs its unit
tests and lint, and checks existing production package/signing compatibility.
