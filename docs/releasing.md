# Releasing

## Versions

`android/app/build.gradle.kts` holds `versionCode` and `versionName`; the dashboard shows
`APP_VERSION` from `cloud/app/page.tsx`. Bump both, add a `CHANGELOG.md` entry, and keep the
application ID `dev.hcsummaries.export`, preference names, Keystore alias, and WorkManager work
names unchanged so updates keep the pairing and settings.

## Signing keys

Android installs an update only if it is signed with the **same key** as the installed app.
Changing the key means uninstalling, which erases the pairing and settings (data on the Site is
kept; pair again).

- **Debug builds** use the build machine's `~/.android/debug.keystore`. Fine for your own phone;
  back that file up and keep using the same machine or key.
- **Release builds** are signed only from environment variables:
  `HC_RELEASE_STORE_FILE`, `HC_RELEASE_STORE_PASSWORD`, `HC_RELEASE_KEY_ALIAS`,
  `HC_RELEASE_KEY_PASSWORD`. With none set, `assembleRelease` makes an unsigned, minified APK.
  With some set, or with `-PhcRequireReleaseSigning=true` and any missing, the build fails and
  names the missing variables. Never commit a keystore or its passwords.
- **A shared generic release** for other people needs one stable publisher key, kept offline and
  backed up, used for every future version. Losing it means no further updates for existing
  installs.

## Workflows

| Workflow | Trigger | Does |
| --- | --- | --- |
| `ci.yml` | push, pull request | Backend tests and typecheck, Android tests, lint, and a generic debug build, privacy scan. Read-only, no secrets. |
| `release.yml` | manual (`workflow_dispatch`) | Unsigned release candidate, or a signed release in the protected `release` environment. Uploads a workflow artifact only; never publishes. |

For signed releases, create a `release` environment with required reviewers and these secrets:
`HC_RELEASE_KEYSTORE_BASE64` (the keystore, base64), `HC_RELEASE_STORE_PASSWORD`,
`HC_RELEASE_KEY_ALIAS`, `HC_RELEASE_KEY_PASSWORD`.

## Checklist

Done in this repository:

- [x] MIT license, third-party notices, security policy, contributing guide, templates.
- [x] Public source without Site identity, binaries, signing keys, or local data; privacy scan.
- [x] Generic build with host confirmation; pinned build unchanged; unit tests for both.
- [x] Env-only release signing that fails clearly when incomplete.
- [x] CI and manual release workflows with pinned actions and read-only permissions.

Still to do before offering a shared app to other people:

- [ ] Create and safeguard a stable publisher signing key; configure the `release` environment.
- [ ] Run CI on GitHub (workflows have not run there yet).
- [ ] Set up a second, independent ChatGPT account from this repository end to end.
- [ ] Test on real phones: pairing confirmation, Health Connect dialogs, background sync timing,
      upgrades, TalkBack, and rotation.
- [ ] Decide on distribution (for example GitHub releases or an app store) and its requirements.
