# Validation evidence

## Completed verification

CI run [36464083767](https://github.com/michat88/AdiXtream/actions/runs/36464083767)
at `a5851175453e05572ff2ea4c82bd72f386867b27` completed the following:

| Task / check | Result |
| --- | --- |
| `clean` | PASS |
| `:shared:compileAndroidMain` | PASS |
| `:app:compileStableDebugKotlin` including Settings/subscription Compose | PASS |
| `:app:assembleStableDebug` | PASS |
| `:app:testStableDebugUnitTest` | PASS: 15 tests, 0 failed, 0 skipped |
| `:library:jvmTest` | PASS: 476 tests, 0 failed, 0 skipped |
| `:app:checkStableDebugDuplicateClasses` | PASS |
| Android resource/manifest processing and linking | PASS |
| `:app:lintStableDebug` | Initially failed (8 errors); fixed and PASS at `89258915` |

The app tests cover subtitle selection plus subscription state/callback/timeout,
internal repository policy, updater origin and version comparison. Library tests
cover M3U8, JS interpreter, string helpers and episode dates. They use synthetic
inputs and do not activate licenses or write production customer data.

Eight lint errors were caused by a missing default plural/strings, the existing
legal notice in a do-not-translate file, Android 12 splash attributes in base
resources, and an API-24 corruption-recovery call on minSdk 23. Fixes retain the
existing text/splash appearance and premium fallback storage. No lint severity
was reduced to hide these findings.

CI run [36498854866](https://github.com/michat88/AdiXtream/actions/runs/36498854866)
at `89258915051635ae1582280f9c2c319676a37cea` completed with these GitHub job-step results:

| Step | Result |
| --- | --- |
| Offline compatibility and credential checks | PASS |
| Clean build, tests and lint without production secrets | PASS |
| Missing release configuration fails closed | PASS |
| KVM setup | PASS |
| Offline phone/TV emulator smoke | FAIL; not counted as verified UI |
| Reports and isolated debug APK upload | PASS at run time |

On 2026-10-03 GitHub still exposes the step results, but returns HTTP 410 for this
job's logs and lists no retained artifacts. The configured 14-day artifact request
was limited by repository retention. The failed run also cannot be retried (GitHub
403: this workflow run cannot be retried). A new branch commit starts a fresh run
so the emulator failure can be diagnosed from fresh logs, without claiming it passed.

| Offline check | Result |
| --- | --- |
| `python scripts/verify_migration.py --report` | PASS: 115/115 contracts |
| `python scripts/scan_credentials.py` | PASS: no findings in tracked current source |
| Gitleaks 8.30.1 current tracked tree, redacted | PASS: 0 findings |
| Gitleaks 8.30.1 new commits `0864e972..26ac6746`, redacted | PASS: 0 findings |
| `git diff --check` | PASS |
| Full upstream path/commit audit | 298 paths and 45 commits covered |

Gitleaks has narrow reviewed exceptions for one public OAuth ID and two extractor
protocol constants, documented in SECURITY.md. Historical credentials before the
migration remain in Git history because rewriting history was forbidden. The scan
result does not claim their historical deletion or that APK BuildConfig is secret.

## Runtime and release limits

The emulator test is explicitly restricted to `com.adixtream.app.debug` with empty
Free/Premium/Firebase configuration. No activation or promo code is submitted. Wi-Fi and mobile data were disabled
before launch, but retained QRIS screenshots show that public image access still
worked on the emulator; those runs were not fully network-isolated. Backend
configuration was empty throughout. A follow-up adds an emulator outbound firewall. It checks startup, root menu reachability,
build stamp scrolling, phone portrait/landscape, TV layout, D-pad movement, Device ID
copy, disabled empty-code submission, and reachable plans/contact. TV layout on a
phone AVD is not equivalent to testing a physical Android TV remote/device.

Not yet verified on production-configured devices: signed install-over from an old
APK, persisted active/expired/banned customer licenses, offline unlock migration
against Firebase, live activation/promo, internal repository downloads, production
account login, real updater installation, playback/subtitles/casting/downloads,
backup/restore and setup end-to-end. Existing source contracts and offline tests
cover parts of these areas; they do not establish end-to-end success.

A production signed APK/upgrade test is not run: the production keystore and old
installed customer state are intentionally not accessed here. The additional
SIMKL/AniList/OpenSubtitles secret names are wired but their presence in repository
settings is unknown; absent values block the production build/login integration.
No fake values, production backend test writes, public APK release or stable release
were created. Debug artifacts are isolated test builds and cannot replace a
production customer installation.

## Feature verification boundaries

| Area | Evidence / remaining check |
| --- | --- |
| MainActivity startup / Settings HP portrait and landscape | PASS on Android 15 AVD in phonePortraitAndLandscape at e761ba3a |
| Settings TV and remote D-pad | Menu/build-stamp reachability, D-pad center copy and focus movement passed at e761ba3a; complete TV method still failed capture bounds; physical TV untested |
| Subscription status / copy / forms | Compiles; fake-service unit tests pass; phone portrait/landscape status, copy and empty-code guards PASS at e761ba3a |
| Active/expired customer license and Device ID | Original algorithm/store/migration source contracts pass; signed customer install-over not run |
| Old offline unlock migration | Original method and startup call retained; no production Firebase request made |
| Activation / promo | Existing API and validation retained; loading/duplicate/error/timeout unit tests pass; no production code submitted |
| Free/Premium repository and premium gating | Offline policy tests pass; no arbitrary repository entry; actual configured-repo download untested |
| Updater | AdiXtream URL/version tests pass; package/certificate checks compile; actual update installation untested |
| Player / subtitle / HLS | Android build plus subtitle-selection and library/M3U8 tests pass; real media playback/casting untested |
| Search / account / download / backup / setup | Upstream changes compile and existing storage contracts retained; end-to-end device/network checks untested |
| Splash | Original source/layout retained; the smoke harness launches MainActivity directly, so Splash runtime is not covered |
| Production signing | Existing secret/keystore wiring retained; no production key access, signing or certificate comparison performed |

Screenshots demonstrate actual emulator rendering. The `f4b6fafc` plans image
also shows the existing public QRIS image loaded; it does not establish payment
processing or premium backend availability. Firewall-isolated runs cannot test
remote image availability.

## Fresh evidence on 2026-10-03

Run [37117147359](https://github.com/michat88/AdiXtream/actions/runs/37117147359)
at `e0acdc320e642890a76b98dce89f8077a0380746`:

- Clean build/Compose/assemble, 15 app tests and 476 library tests: PASS.
- Lint: PASS with 0 errors, 657 warnings and 2 hints (not warning-free).
- Fail-closed release configuration: PASS.
- Two emulator tests: FAIL at root menu lookup. Retained logcat shows Android's
  GrantPermissionsActivity covering the app; no settings screenshots were reached.
- Debug APK upload: [artifact 11272351672](https://github.com/michat88/AdiXtream/actions/runs/37117147359/artifacts/11272351672).
- [Reports artifact 11272281703](https://github.com/michat88/AdiXtream/actions/runs/37117147359/artifacts/11272281703).

Commit `f5f03f25` grants only the isolated test app's notification permission before
launch, captures a screenshot on test failure, and allows reverse scroll from the
build stamp to the subscription entry. Production app behavior/permissions did
not change. A follow-up run verifies the corrected harness. CI now deduplicates
push/PR runs and skips documentation-only changes; final documentation commits
therefore share the verified production source tree.

Run [37118067765](https://github.com/michat88/AdiXtream/actions/runs/37118067765)
at `f5f03f25` again passed clean build/unit tests/lint and release fail-closed checks.
The permission dialog was resolved. Phone smoke reached subscription status and
Device ID copy, but failed to reach the promo card; TV smoke failed a menu lookup.
These are failures, not runtime passes. The harness could choose the TV navigation
rail or skip short content with a full-page accessibility scroll. Screenshots in
app-private external storage were removed when AGP uninstalled the tested APK.

Commit `f4b6fafc` chooses the largest scrollable content area with overlapping
swipes, names any missing menu in assertions, and saves screenshots through the
emulator shell under Downloads so they survive APK uninstall. It changes only
validation code and CI caching, with no production code or permission change.

Run [37135661389](https://github.com/michat88/AdiXtream/actions/runs/37135661389)
at `f4b6fafc` passed clean build, all 15 app and 476 library tests, lint (657 warnings,
2 hints, no errors), and fail-closed release checks. Both instrumented test methods
still failed: TV missed “Updates and Backup” after scrolling past it; the phone
method completed portrait but failed build-stamp lookup after rotating landscape.
Screenshots were retained successfully. The phone portrait sub-flow reached all
menus, build stamp, free status/Device ID copy, empty-code guards, promo and plans.
This partial success is not counted as a passing whole phone/TV test.

- [Reports](https://github.com/michat88/AdiXtream/actions/runs/37135661389/artifacts/11278598880)
- [Original screenshots](https://github.com/michat88/AdiXtream/actions/runs/37135661389/artifacts/11278593825)
- Selected screenshots are retained in [evidence/README.md](evidence/README.md).

Commit `e761ba3a` refreshes accessibility nodes, clips gestures to the current
window, uses slower stationary-ended drags to avoid flinging past short rows,
scrolls to the Device ID copy action on short viewports, and records hierarchy XML.
No failing assertion was removed and no production UI was changed by this commit.

Run [37136748122](https://github.com/michat88/AdiXtream/actions/runs/37136748122)
at `e761ba3a` passed clean build/unit/lint/release-gate checks. Instrumentation:
**phonePortraitAndLandscape PASS; tvLayoutAndDpad FAIL** (2 tests, 1 failure).
TV reached all seven menus and build stamp, opened subscription, focused Salin,
activated it with D-pad center, and moved focus with D-pad down. Failure was in
`capture()` after reaching promo: it compared nodes against targetContext's stale
portrait display metrics after rotation. The harness now measures the actual
screenshot dimensions and dismisses the input keyboard before subsequent captures.
It retains the bounds assertion and saves evidence before asserting. Hierarchy
XML now uses MediaStore Downloads rather than unsupported shell redirection.

Commit `26ac6746` also blocks emulator IPv4/IPv6 outbound traffic using root-only
iptables in the disposable Google APIs AVD. This changes no production app network
behavior and uses no production credentials.

Run [37137549829](https://github.com/michat88/AdiXtream/actions/runs/37137549829)
at `26ac6746` passed clean build/unit/lint and fail-closed release checks. The
Google APIs emulator accepted `adb root` and both IPv4/IPv6 OUTPUT rejection rules.
Phone portrait/landscape passed again. TV still failed the immediate D-pad focus
comparison; the assertion read focus before Compose/accessibility could settle.
Commit `6618eab5` adds a bounded three-second event/cache settling wait, retaining
the same required focus change and saving a screenshot before the assertion.
This diagnosis remains subject to the follow-up result; it is not a TV pass yet.

## Debug APK for owner trial

[26ac6746 debug APK ZIP](https://github.com/michat88/AdiXtream/actions/runs/37137549829/artifacts/11278454635)
contains the installable `.apk`; extract the ZIP on Android and install that file.
The artifact expires at 2026-10-04 16:37 UTC (2026-10-05 01:37 WIT). This is
`com.adixtream.app.debug`, separate from the production app and its customer data.
It is suitable for Settings/navigation/local UI trial. Repository/backend/account
credentials are absent: it cannot validate live Free/Premium content, activation,
promo or customer upgrade. Do not treat it as a production upgrade candidate.
The direct file transfer from the execution environment was rejected; the
GitHub artifact link is the available download, with no public release created.
