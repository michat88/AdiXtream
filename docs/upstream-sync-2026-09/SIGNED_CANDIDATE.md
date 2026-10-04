# Signed release candidate status

Requested deliverable: a release APK signed with the existing AdiXtream production
keystore, application ID `com.adixtream.app`. A debug APK is not a substitute.
No signed candidate has been produced as of the completed release attempt below.

## Current blocker

[Build Signed APK, source 99c3e1be](https://github.com/michat88/AdiXtream/actions/runs/37182939927)
failed at `:app:verifyReleaseConfiguration` because these existing account/service
configuration names are missing:

- `SIMKL_CLIENT_ID` (public application identifier)
- `SIMKL_CLIENT_SECRET`
- `ANILIST_CLIENT_SECRET`
- `OPENSUBTITLES_API_KEY`

Add the existing values directly under repository Settings → Secrets and variables
→ Actions. Do not post them in chat, commits or PR comments. No replacement values
were fabricated and no account registration/auth-flow change was made.

Existing keystore decode and cleanup succeeded. The original signing identity and
secret names remain `SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`.
Backend/repository injection remains `XOR_SECRET_KEY`, `PREMIUM_REPO_ENCODED`,
`FREE_REPO_ENCODED`, `FIREBASE_URL_ENCODED`. Their values are not printed here.

## Build and delivery once configured

Rerun the failed `Build Signed APK` job for the reviewed migration commit. The
migration branch runs `:app:assembleStableRelease` and uploads only an Actions
artifact named `AdiXtream-signed-candidate`; it does not publish a GitHub release.
Before upload the workflow checks:

1. `apksigner verify` succeeds on generated release APKs.
2. The package is exactly `com.adixtream.app`.
3. `aapt dump badging` has no `application-debuggable` flag.

The release build type also explicitly sets `isDebuggable = false`. No debug
signing fallback or newly generated keystore is used. The temporary decoded
keystore is removed even when the build fails.

Those artifact checks have not run yet because configuration validation blocked
the build. Do not claim a downloadable APK, verified signature, successful release
install or install-over until the actual artifact exists and those checks run.
A signed install-over with old customer preferences/certificate is still required;
the isolated emulator UI tests do not establish production upgrade success.

Secret injection into BuildConfig removes hardcoded values from source but does
not make them confidential inside a distributed APK. See [SECURITY.md](SECURITY.md).
