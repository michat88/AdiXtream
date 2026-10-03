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
| Gitleaks 8.30.1 new commits `0864e972..89258915`, redacted | PASS: 0 findings |
| `git diff --check` | PASS |
| Full upstream path/commit audit | 298 paths and 45 commits covered |

Gitleaks has narrow reviewed exceptions for one public OAuth ID and two extractor
protocol constants, documented in SECURITY.md. Historical credentials before the
migration remain in Git history because rewriting history was forbidden. The scan
result does not claim their historical deletion or that APK BuildConfig is secret.

## Runtime and release limits

The emulator test is explicitly restricted to `com.adixtream.app.debug` with empty
Free/Premium/Firebase configuration. Network is disabled before activity launch;
no activation or promo code is submitted. It checks startup, root menu reachability,
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
