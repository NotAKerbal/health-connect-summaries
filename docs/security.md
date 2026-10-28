# Security

## Trust boundary

The Site is built for ChatGPT Sites and depends on it:

- **Identity.** Browser and MCP routes trust the `oai-authenticated-user-id` header that Sites
  injects after sign-in and that visitors cannot forge. On any other host anyone could set it, so
  deploying elsewhere (for example with `wrangler deploy`) is unsafe without adding real
  authentication.
- **Private-Site gate.** Phone routes carry the Site's service-access token, which only passes the
  owner-private gate, plus a pairing or device token that the app checks.
- **Who can read the data.** The Site owner, through the dashboard and the plugin (after turning on
  ChatGPT access), and anyone who can administer the owner's Sites/hosting account or its
  database. Tokens are stored as SHA-256 hashes; health values are not encrypted at the
  application level.

## Generic and pinned app builds

A pinned build (`-PhcSiteUrl`) cannot be pointed at another Site. A generic build can pair with
any public HTTPS Site, so a pasted code from someone else could send your data to their Site.
The app therefore shows the hostname and requires **Connect** before it sends anything, and adds
a warning for hostnames outside `chatgpt.site`. Only paste codes you created on your own
dashboard. Prefer a pinned build for your own phone.

## Details


**Android app**

- **Permissions.** It requests the 6 `READ_*` Health Connect permissions, plus `READ_HEALTH_DATA_IN_BACKGROUND` only when you turn on Automatic sync, and only where the feature is available. There are no write or history permissions. `INTERNET` and `ACCESS_NETWORK_STATE` are used for sync. WorkManager adds `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED`. Its `FOREGROUND_SERVICE` is removed because sync never runs as a foreground service.
- **Network.** It sends data only to one Site origin: the built-in one in a pinned build, or, in a generic build, the one whose hostname the user confirmed when pairing. Codes for any other origin (pinned), or for non-HTTPS, IP-literal, local, special-use, or explicit-port origins (generic), are refused before any request, and a generic build refuses hostnames that resolve to non-public addresses. The status check is a credentialed `GET` with the same HTTPS-only, no-redirect, timeout, and response-cap rules; tokens are only ever sent in headers, never in URLs, logs, or the clipboard.
- **Stored credentials.** They are encrypted with an Android Keystore AES-GCM key and are never logged or displayed. The code input is a masked field with suggestions and autofill off.
- **Backups.** They are off: `allowBackup="false"`, and `dataExtractionRules` and `fullBackupContent` exclude every domain (including the credential preferences) from cloud backup and device transfer.
- **Optional exports.** These are private files in `files/exports/`, exposed only through a non-exported `FileProvider` with a per-share read grant.
- **Logging and screenshots.** There is no analytics and no logging of data. Release builds strip `android.util.Log` with R8 and set `FLAG_SECURE`. Debug builds leave `FLAG_SECURE` off for QA screenshots.
- **Rationale screen.** The rationale and privacy screen is registered for `ACTION_SHOW_PERMISSIONS_RATIONALE` (Android 13 and lower) and through an `activity-alias` for `VIEW_PERMISSION_USAGE` / `HEALTH_PERMISSIONS` (Android 14+).

**Site**

- **Authentication.** Browser routes and MCP rely on Sites authentication and the platform's guarantee that `oai-authenticated-*` headers are injected after sign-in and cannot be forged. Phone routes (`/api/sync/pair`, `POST`/`DELETE /api/sync`, `GET /api/sync/status`) need no visitor identity and ignore any identity headers; they reach the app through the platform service-access gate, and the app then authorizes them only by a hashed one-time pairing token or a hashed device token.
- **The service-access token.** It travels inside the connection code and is stored on the phone, so treat both as secrets. On its own it reaches only identity-free routes: the three phone routes, the privacy page, and static downloads. The dashboard, browser APIs, and MCP still require sign-in. It cannot upload or read connection status without a valid pairing or device token, and it cannot read health data. Rotating it (a hosting-side action) stops sync until the phone is paired again with a new code.
- **Owner isolation.** Every query is filtered by owner. Delete and revoke endpoints check ownership, so another owner's import or device ID returns 404.
- **CSRF.** Browser mutations require `Content-Type: application/json` and reject cross-site `Sec-Fetch-Site`. Deleting all data also requires an explicit confirmation token. Bodies are size-capped while streaming (pairing and redeem bodies at 1 KiB, snapshots at 64 KiB).
- **Encryption and administration.** There is no application-level encryption at rest. Data is protected only as the hosting platform provides, and the site owner's hosting account can administer the D1 database. Tokens are stored as SHA-256 hashes.
- **Data shared with ChatGPT.** When access is on, tool results enter your ChatGPT conversation and fall under your ChatGPT data settings. This is not ChatGPT Health, and ChatGPT Health's specific protections do not apply.
- **History limit.** Health Connect normally lets a newly granted app read up to 30 days before the first grant. The app never asks for older history.
- **Not medical.** This is not a medical device, and the tools return descriptive statistics only.

## Reporting

See [SECURITY.md](../SECURITY.md).
