# AdiXtream CloudStream migration — incomplete checkpoint

This branch is work in progress, not a releasable build. Do not merge or publish.

## Pinned sources

- AdiXtream master at start: `0864e972c9580dff9a2eb7a6a87aed5d43d98fa1`.
- CloudStream master at start: `e36ff7aa7997be86d47aac9412c174f06128fd0d`.
- Last CloudStream first-parent commit at/before 2026-08-21:
  `a72f9e6c3f2e25eb74ce0e7d6cc56dc33c130288` (2026-08-05).
- Common ancestor: `1fb6ce310d9453a91e28a7bd1cef16837df2041a`.
- Work branch: `migration/cloudstream-2026-09-compose-premium`.

The pinned range contains 45 commits (37 first-parent), changing 298 files.
The machine-readable inventory records the original fork/baseline/upstream blob
comparisons. It is a porting audit, not a claim that every item is validated.

## Recovery checkpoint

The previous transient checkout disappeared before any source commit reached
GitHub. The upstream port has been reconstructed from the pinned sources. New
upstream files and files identical to the baseline were copied selectively.
Upstream diff hunks were applied and conflicts resolved on custom fork files.
Other-language translations and upstream fastlane material are intentionally
retained/excluded to preserve the fork's en/id/in locale and branding scope.

Recovered: shared Compose build graph, library/HLS/subtitle/extractor fixes,
Compose settings scaffolding, player/account/search/setup/TV/resource changes.
Required older TV-clock resources and the AnimeSkip icon were added explicitly;
they were missing from the fork but required by the new settings/player code.

## Blocking security decision

Publishing `app/build.gradle.kts` was rejected because the pre-existing file
contains embedded signing fallback passwords and a SIMKL credential. No values
are included here. The file is intentionally excluded from remote checkpoints;
it still has its original contents on this branch. The local dependency/plugin
port cannot be published safely until the owner approves removing hardcoded
credentials and providing any missing SIMKL configuration through secrets.

No GitHub Actions secret values or keystore have been retrieved, changed, or
rotated. The original `buat_apk.yml` remains untouched. Production release and
signing operations have not been run.

## Outstanding work — do not treat this checkpoint as complete

- Safely finish the app Gradle dependency chain after the security decision.
- Integrate dedicated Compose activation/subscription page, preserving existing
  PremiumManager storage, device ID, validation, offline migration, and backend.
- Enforce internal-only repositories throughout setup, extensions, and deep links.
- Complete AdiXtream updater version, source, package, and certificate guards.
- Finish preference migration, compatibility tests, audit manifests, and CI.
- Run a clean Android build, unit tests, lint, and dependency/resource checks.
- Validate phone/TV focus and real signed install-over on authorized devices.
- Open a Draft PR when implementation is ready for review; never auto-merge.

The previous interrupted build did not finish; no successful application build,
unit-test suite, device test, or signed upgrade is claimed. The current recovered
checkout's Gradle bootstrap also encountered a network timeout. There is no APK
or phone/TV screenshot from this checkpoint.
