# Repository and plugin recovery fixes

Baseline: `6544fd2a3f9a0bf363326358eb1ad9b8a927fd68`.
Android release: AdiXtream 4.8.8 (95).

## Changes tied to the audit

- **RP-01:** Premium access is checked in the common loader, after downloads and before provider calls. Foreground entitlement events also fire at expiry without a successful server GET. Unloading invalidates an in-progress initializer. Custom provider copies retain their source plugin identity. Network failure still does not create or persist a FREE license.
- **RP-02:** Startup/download/catalog diagnostics no longer interpolate internal repository metadata, addresses, paths, or transport exceptions. `RepositoryData.toString()` is redacted. Diagnostics remain enabled with generic messages.
- **RP-03:** New initial installs persist their pending plugin plan before downloads. Failed/interrupted work remains pending; subsequent startup retries it. A catalog list failure cannot be treated as an empty successful list. Completed initial installs do not re-add manually deleted plugins.
- **RP-04:** Plugin registry publication and saved versions follow successful initialization. Failed partial registrations are removed. Shared installation logic restores the previous file and, if still entitled, its provider when replacement activation fails. The active Compose downloader uses this same logic, including the digest-cache path.
- **RP-05:** Repository replacement waits for target plugin files and initializers. The target repository preference is committed successfully before previous repository cache deletion. If preparation fails, previous repository preferences/cache remain intact. Cache retention never grants an expired Premium license.

## Existing installs and persisted data

Application ID, signing, Device ID, activation/promo logic, entitlement storage keys, repository directory names and updater target are unchanged. No server/rules migration or hosting/billing activation is part of this change.

Old releases did not record whether a missing plugin was deliberately removed or never downloaded. An established pre-journal installation with installed target plugins is therefore preserved, rather than bulk-reinstalling its missing plugins on upgrade. The journal guarantees resumability for initial installs started by this release, known pending work, empty installations, and repository switches; it cannot reconstruct missing historical user intent. Its keys use a digest of the repository identity, not its address.

## Validation

Regression suites cover permission changes, expiry without server success, renewal cancelling an earlier expiry timer, partial install/restart, cancellation, persistence failure, registry rollback and retry, file rollback, and preserving completed-install deletions. Existing Premium recovery tests remain enabled.

The normal release workflow must build the signed APK, run all app unit tests, compare package/signature/version ordering against the previous release, and run release lint. No build/test/lint step is bypassed. Phone/TV runtime validation remains distinct from JVM and source validation.
