# Configuration and signing

No GitHub repository secret values were queried. No keystore, certificate or
signing secret was created, replaced or rotated. The production application ID
remains `com.adixtream.app`; debug builds retain the existing `.debug` suffix.

Removed from the current source: hardcoded release signing password fallbacks,
the hardcoded alias fallback, the Gradle default XOR key fallback, and embedded SIMKL
client credentials. The earlier commits remain intact as requested, so this is
not a purge of historical Git objects. Initial inspection accidentally emitted
old credential content because a redaction filter was incomplete; no values are
reproduced in this document or new commit messages. Subsequent scanning emits
only paths and finding categories.

## Required configuration

The existing signed APK workflow retains `SIGNING_KEY`, `ALIAS`,
`KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `XOR_SECRET_KEY`, `PREMIUM_REPO_ENCODED`,
`FREE_REPO_ENCODED`, and `FIREBASE_URL_ENCODED`. `KEYSTORE_PATH` points to the
existing keystore decoded by the workflow. All four AdiXtream repository/backend
variables retain their existing XOR/BuildConfig/RepoProtector injection format.

The additional workflow secret names are `SIMKL_CLIENT_ID` and
`SIMKL_CLIENT_SECRET`. The owner must configure the **existing** SIMKL app values
if these names are not present. Availability was not queried. No fabricated
credentials or new registrations are supplied. Debug builds permit missing
credentials for offline tests; browser/PIN login returns unavailable when the
required config is absent. Existing token/account storage is unchanged.

Release prebuild/signing validation fails with missing **variable names only**;
it never falls back to another signing identity or an empty production backend.
Local builds can use ignored local.properties for the existing backend/SIMKL
configuration. Signing parameters always come from environment variables.
`MAL_KEY` and `ANILIST_KEY` remain the pre-existing optional build fields; the
existing public MAL/AniList client identifiers are retained in their providers.

The full-tree Gitleaks audit additionally found an embedded AniList client secret
and OpenSubtitles application API key. Both now use BuildConfig injected from
`ANILIST_CLIENT_SECRET` and `OPENSUBTITLES_API_KEY` (Actions secrets/environment,
or ignored local.properties for local builds). Production validation requires
these existing values as well, to avoid silently breaking account/subtitle access.
Account IDs, stored tokens, callback schemes and auth flows have not changed.
Their availability in repository settings was not queried. The owner must add
the existing values under the documented names if absent, without posting them
in chat. These injected values are also extractable from an APK.

Gitleaks' other candidates were reviewed: MAL's public OAuth client ID and two
extractor protocol constants (Videa token alphabet and NineAnime cipher alphabet)
are not owner account credentials. They remain for compatibility, with narrowly
scoped scanner exceptions by path/rule/variable. No finding value is documented.
Official AniList auth reference: https://docs.anilist.co/guide/auth/authorization-code

## SIMKL auth audit

Official sources checked on 2026-09-28:

- https://api.simkl.org/authentication
- https://github.com/SIMKL/API/blob/master/apiary.apib

`client_id` is a public application identifier. `client_secret` and user tokens
are sensitive. Both client fields are injected for consistent configuration;
only the secret is confidential. Moving a secret from repository source into
GitHub Actions and then BuildConfig **does not make it secret in the distributed
APK**. APK contents can be extracted.

The current Android implementation uses the existing V1 authorization-code
exchange, which supplies client_secret, and the existing TV PIN flow. Official
SIMKL docs describe public PKCE/mobile and device flows; their current AUTH V2
migration requires a separate registration/client ID and token handling changes.
Do not change the live registration or silently migrate stored users as part of
this upstream port. A planned PKCE/auth migration, tested with the owner's app
registration and existing account upgrade fixtures, is follow-up hardening.

No login/activation/promo request, Firebase mutation, or signing operation against
the production service has been used for migration testing.
