# AdiXtream CloudStream migration

The implementation is on `migration/cloudstream-2026-09-compose-premium` for a
[Draft PR #1](https://github.com/michat88/AdiXtream/pull/1). No master/main write, merge, force push, public release, production
signing operation or production activation/promo request has been performed.

## Pinned sources and review inventory

- AdiXtream master at start: `0864e972c9580dff9a2eb7a6a87aed5d43d98fa1`.
- CloudStream HEAD at work start: `e36ff7aa7997be86d47aac9412c174f06128fd0d`.
- Last first-parent CloudStream commit at/before 2026-08-21:
  `a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288` (2026-08-05).
- Common ancestor: `1fb6ce310d9453a91e28a7bd1cef16837df2041a`.
- The pinned range has 45 commits, 37 on first-parent history, and 298 changed paths.

[COMMITS.md](COMMITS.md) covers every commit. [FILES.md](FILES.md) lists every
upstream-changed file and additional integration files as direct replace, added,
manual merge or protected/intentionally retained. The machine-readable
[file manifest](file-manifest.json) includes original fork/baseline/upstream blob
comparisons. [Original inventory](upstream-inventory.json) is retained for review.
Existing checkpoint commits `566ab721` and `c8f26cba` remain in history.
[MIGRATION_COMMITS.md](MIGRATION_COMMITS.md) records the structured migration commits.

## Implemented upstream functionality

- Complete Compose/shared Android build graph, plugins/catalog dependencies,
  Kotlin/JVM compatibility, and upstream library test infrastructure.
- Compose Settings and search, subtitle settings and custom paths, settings
  performance fixes, scroll/focus handling and additional bottom space.
- Player start-paused preference, subtitle delay convention, HLS resolution,
  library ABI/Java corrections, extractor additions/fixes, search suggestions,
  account avatars, setup language/media and TV D-pad fixes.
- Compose updater integrated with AdiXtream release origin and the existing
  fallback updater. APK package/certificate/version guards apply to both paths.
- Required earlier TV-clock resources and AnimeSkip icon are explicitly included
  as prerequisites. Optional desktop/shared structure is present for the project
  graph; the desktop demo is not an AdiXtream desktop product.

Changes were ported on top of the fork. Only files identical to the old upstream
were direct replacements. Diverged files were merged manually. Other-language
translations and upstream fastlane branding outside the packaged en/id/in locale
scope are intentionally retained/excluded, not claimed as product changes.

## AdiXtream Settings and repositories

The scrollable root has exactly this order: Umum, Pemutar, Antarmuka pengguna,
Update dan Cadangan, Akun dan Keamanan, Ekstensi, Aktivasi dan Langganan. The last
entry's subtitle is “Status, Aktivasi, Promo, Langganan”. Build information and
About AdiXtream remain accessible below the menus without reducing font sizes.

The dedicated Compose subscription page shows local active/free/expired status,
expiry, the existing Device ID and copy action, activation and promo forms with
loading/result handling, original package prices, QRIS and Telegram contact.
It delegates to the existing PremiumManager methods. The original promo API still
keeps its restart behavior; the new screen uses a compatible overload and an
explicit apply/reload action after success. No second licensing system exists.

Only the configured FREE_REPO and PREMIUM_REPO pass the repository policy;
premium access depends on existing local eligibility. Add-repository forms and
phone/TV buttons are removed/hidden. Setup, fetch, insertion and deep links all
apply the same restriction. Existing premium navigation gating and startup
repository reconciliation are retained. Existing users' installed plugin data
is not migrated into another store.

Both updater paths retain `michat88/AdiXtream`, reject unrelated download URLs,
and check downloaded package identity, installed signing certificate set and
non-decreasing versionCode before installation. Neither path targets official
CloudStream releases. AdiXtream name, Android artwork, splash, website, contacts,
internal repository injection and CloudStream credits remain.

## Upgrade and premium compatibility

Production application ID is `com.adixtream.app`. VersionCode 90 and versionName
4.8.3 are unchanged; debug retains the existing `.debug` suffix and is isolated
from customer installs. Production signing still uses the owner's existing
keystore/alias from Actions secrets. No key/certificate/alias was generated,
changed, retrieved or rotated.

The same encrypted `premium_secure_data`, fallback `premium_fallback_prefs`,
`is_premium_user`, `premium_expiry_date`, obfuscated fallback keys and Device ID
algorithm remain. Activation, promo validation, server mappings, banned/expired
checks and old offline-user migration still use the original backend contract.
The old migration is still called at startup. A normal update with the existing
certificate therefore preserves the storage and device mapping by construction;
a real signed install-over on an existing customer device has not been run.

PremiumManager changes are additive local status/expiry display, bounded activation
network waiting, and a backward-compatible promo UI callback. Corrupt encrypted
store cleanup now guards the API-24 method and clears only that same already
corrupt store on API 23. Valid encrypted preferences and the fallback store are
untouched. The display-only expiry history never grants access. Hash-based source
contracts compare the critical licensing methods to the original, with these
narrowly documented adaptations; they are not substitutes for a device upgrade test.

Settings migration copies only the old DNS and search-quality preference values
when the new keys are absent, retaining old keys for backup/rollback. Account,
backup, extension and licensing stores are not renamed or cleared.

## Security and validation

The owner approved removal of embedded credentials. Gradle now injects existing
account configuration from environment/Actions secrets, removes sensitive signing
fallbacks and fails release validation on missing configuration. See
[SECURITY.md](SECURITY.md) for required variable names, historical exposure limits,
SIMKL public-client audit and the fact that BuildConfig secrets remain extractable
from an APK. Secret values are not included in these documents.

[VALIDATION.md](VALIDATION.md) records actual task results, CI links and remaining
device/production checks. The dedicated validation workflow uses no production
secrets, performs a clean build, unit/library tests, lint and isolated emulator
smoke tests, and uploads reports/debug artifacts. It does not sign with the
production key or create a release. Keep the PR Draft until the owner reviews
these results and the remaining signed upgrade/device checks.
