# Development

## Layout

| Path | Contents |
| --- | --- |
| `android/` | Kotlin app, no UI framework beyond the platform views. Pure logic (sync model, origin policy, status model) is unit-tested on the JVM. |
| `cloud/` | The app's Site files: `lib/health/` (validation, D1 store, queries, MCP, pairing and sync), `app/` (dashboard, privacy page, API routes, `/mcp`), `drizzle/` (migrations), `test/`. |
| `cloud/sites-starter-files.txt` | Build integration supplied unchanged by the Sites Vinext starter, and therefore not in this repository. |
| `scripts/overlay-site.mjs` | Copies `cloud/` onto a starter checkout. |
| `scripts/check-public-tree.py` | Fails on secrets, private files, or build output in a tree or archive. |
| `plugin/` | Usage guidance for the ChatGPT plugin's tools. |

## Checks

```bash
# Backend, standalone (what CI runs)
cd cloud && npm ci --ignore-scripts && npm test && npx tsc --noEmit -p tsconfig.backend.json

# Full Site checks need an assembled checkout (docs/setup.md, steps 1-2 and 4)
npm test && npm run typecheck && npm run lint && node <sites-plugin-root>/scripts/build-site.mjs

# Android (generic build; add -PhcSiteUrl=https://your-site.example for a pinned one)
android/scripts/setup-toolchain.sh && source .toolchain/env.sh
cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

CI (`.github/workflows/ci.yml`) runs the standalone backend checks, the Android checks, and the
privacy scan with read-only permissions and no secrets. It cannot build the full Site because the
starter files are not in the repository.

## Local Site preview

In an assembled checkout:

- **Local preview.** Put a placeholder `HC_SITE_SERVICE_TOKEN=...` in the checkout's ignored `.dev.vars`, run `npm run dev -- --port 5199`, apply the migrations with the starter README's local D1 commands, and sign in at `/signin-with-chatgpt?return_to=/` (simulated loopback identity). `node test/e2e/smoke.ts http://localhost:5199` exercises the real routes end to end, including pairing, device upload, and revocation, using synthetic test data.
- **Hosted unattended-path check.** `node scripts/sync-check.ts gate <origin>` reads the service token from hidden stdin and changes nothing. It confirms the gate admits the token, and that random pairing and device tokens get the app's JSON 401s. `node scripts/sync-check.ts probe <origin> --replace-paired-phone` reads a fresh connection code from hidden stdin. It pairs a temporary device, sends an invalid snapshot (422, nothing stored), disconnects the device, and confirms the credential is refused. This replaces the paired phone, so re-pair afterwards. Neither mode prints or saves credentials.

## Tests

- **Backend unit and endpoint tests.** `cloud/test/*.test.ts` (node:test, real SQLite running both migrations) cover validation, DST handling, snapshot replacement, idempotency, stale rejection, rollback, owner isolation, deletes, summaries, and the MCP protocol and tools. `cloud/test/sync.test.ts` calls the actual sync HTTP handlers with real `Request`s. It covers pairing: hash-only storage, expiry, replay, raced redemption, invalidation by a newer code, and forged owner fields or headers. It also covers device uploads: owner isolation, no consent side effect, idempotency, stale and oversized bodies, revocation committing between authentication and the write, delete-all racing an upload, re-pair replacement, self-disconnect, and mixed manual and device provenance in MCP output. The status route tests check the exact response keys, owner isolation (including forged identity headers), no health values, IDs, names or credentials, no writes, and `401 device_revoked` for missing, malformed, unknown, owner-revoked, replaced, and delete-all-revoked tokens, and for pairing or service tokens.
- **HTTP-level checks.** `cloud/test/e2e/smoke.ts` runs against a running local preview and includes pairing, device upload, and revocation. `cloud/scripts/sync-check.ts` checks the hosted unattended path.
- **Android status tests.** `HomeStatusTest` covers the screen model: synced with access off (shows the access-settings action), synced with access on (ready, with check time), never ready without a successful sync, first sync queued, waiting, retrying, running, failed, and blocked, a queued or running run after a success keeping the completion, an older Site without the flag (unknown), a failed check keeping the last known value labelled as such, checking, and revoked. `SiteStatusTest` covers status parsing (no flag without a valid time), the upload response flag, upgrades from 0.2.0 preferences, newest-answer-wins and clock-skew clamping, the refresher (offline, gate, and 5xx keep credentials; 404 means outdated, not revoked; `device_revoked` pauses and erases credentials; answers arriving after disconnect or re-pair are discarded). `SyncTransportTest` also sends the real status `GET` over loopback, including a redirect that must not be followed.
- **Android model tests.** `android/app/src/test/.../ExportModelTest.kt` (JUnit) covers local-day windows across DST and skipped midnight, sleep windows, JSON zero-versus-absent, number formatting, bounds, and zone support.
- **Android sync tests.** `SyncModelTest` covers connection-code parsing and origin pinning, response classification, the bounded retry policy, and status phases. `SyncStoresTest` covers AES-GCM credential storage (no plaintext, tamper, swap, and wrong-key handling), settings, and sync state. `SyncTransportTest` runs the real `HttpURLConnection` transport against a loopback server: headers, 401 and 5xx mapping, redirects never followed, timeouts, unreachable hosts, response caps, and HTTPS enforcement. `SyncEngineTest` covers the 30-day direct upload, missing or revoked background and category permissions sending nothing, unsupported background feature, disconnect mid-read, and 401, 409, and 5xx handling. `SyncSchedulerTest` checks the real WorkManager requests: 15 minutes, `CONNECTED`, exponential backoff.
- **Portable builds.** `PortableSiteTest` covers the build policies (pinned, generic, misconfigured), generic codes needing confirmation with nothing sent before **Connect**, cancel sending nothing, pairing only to the confirmed origin, pinned builds refusing other hosts, malformed and expired codes, the public-HTTPS origin rules (IP literals, local and special-use names, ports, case, trailing slash), stored pairings keeping their origin across 0.4 to 0.5 upgrades, refused credentials for non-public origins in the engine and status check, and the non-public address guard. `HomeStatusTest` covers the three first-run steps.
- **Not covered.** There is no instrumented or real-device test. Health Connect permission dialogs, background delivery timing, Keystore behavior on hardware, and the rendered layout and TalkBack behavior of the status screen still need a real phone.

## Rules for changes

- Synthetic data only, and only in tests. Never add real health data, exports, or databases.
- Keep owner scoping, the default-off ChatGPT access switch, and their tests.
- Append migrations; never edit applied ones.
- See [CONTRIBUTING.md](../CONTRIBUTING.md).
