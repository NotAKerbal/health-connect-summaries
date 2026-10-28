# Architecture

```
Browser (signed in): dashboard ──"Create connection code" (once)──> paste into the app
Phone: Health Connect ──(aggregate API, WorkManager background job)──> app ──HTTPS POST /api/sync──> D1
Phone: app ──HTTPS GET /api/sync/status (no health data)──> connected? last sync? ChatGPT access on?
ChatGPT: private plugin ──MCP tools/call──> /mcp (read-only, owner-scoped, only if you turned access on)
```

**Sync is automatic, but not real time.** Turning on Automatic sync schedules a periodic
WorkManager job (every 15 minutes at most, needing a network connection). Android treats that
interval as a minimum and often runs the job later: battery saver, Doze, app standby, and
missing connectivity all delay it. Background reading needs Health Connect's
`FEATURE_READ_HEALTH_DATA_IN_BACKGROUND`, which the app checks at runtime. It is typically
available on Android 14 and later with current updates. Where it is unavailable, the app says
so plainly and offers **Sync now** while the app is open. Every tool result includes the
snapshot time, a fresh or stale label, and how the snapshot arrived.

## Pairing and device credentials

1. **Create a code.** On the signed-in dashboard, **Create connection code** sends a
   same-site JSON `POST /api/sync/pairings`. The owner is taken from `oai-authenticated-user-id`.
   The server stores only the SHA-256 of a random one-time pairing token, bound to that owner,
   for 5 minutes. Creating a new code invalidates any earlier unused one.
2. **What the code contains.** The response (`no-store`) contains a code:
   `HCS1.` + base64url JSON `{v, origin, serviceToken, pairingToken, expiresAt}`. `serviceToken`
   is the Site's existing platform service-access token, read from the runtime secret
   `HC_SITE_SERVICE_TOKEN`. The code is shown only after that click: never server-rendered, put
   in a URL, or logged. It is masked until you choose **Show**, and copied with an explicit
   **Copy to clipboard** next to a warning and an expiry countdown.
3. **Redeem it on the phone.** A pinned build (`-PhcSiteUrl`) accepts a code only for that exact
   HTTPS origin. A generic build accepts a code only for a public DNS name on the default HTTPS
   port (no IP literals, local or special-use names, or explicit ports) and first shows the
   hostname for the user to confirm; **Cancel** sends nothing. Before connecting, a generic build
   also refuses a hostname that resolves to a loopback, private, or link-local address. The app
   then calls `POST /api/sync/pair` with
   `OAI-Sites-Authorization: Bearer <serviceToken>`. That header passes the private Site's
   gate, but it carries **no visitor identity**. In one D1 transaction, redemption claims the
   unexpired, unused pairing row; revokes the owner's previous device; and inserts a new
   device. The device's owner is copied from the pairing row. A concurrent second redemption
   claims nothing. The response returns a random device token once; only its hash is stored.
4. **Store it on the phone.** The origin is stored with the pairing; every later credentialed
   request goes only to that origin, and a stored pairing is never moved to another host. The
   app stores the service token and device token encrypted
   with AES-256-GCM, using a non-exportable Android Keystore key, with each field name bound as
   associated data. Backup and device transfer are off for all app data. After pairing, the app
   clears the clipboard if it still holds the code.
5. **Upload.** `POST /api/sync` carries the gate header plus `Authorization: Bearer <device token>`.
   The owner comes **only** from the hashed device record, never from the body or headers.
   The body is a schemaVersion 1 snapshot, validated and imported exactly like a browser upload.
   The device's active state is checked **inside the same D1 transaction** as the writes. A
   revocation or "delete all" that commits after authentication therefore still blocks the
   write. A successful upload response also reports `chatgptAccessEnabled` and `checkedAt`
   (the owner's current ChatGPT access switch, read after the import and never changed by it).
6. **Connection status.** `GET /api/sync/status` carries the same gate header and device token
   and returns exactly `{deviceConnected, lastSuccessfulSyncAt, chatgptAccessEnabled, checkedAt}`:
   that this device is connected, this device's last successful sync time (or null), the
   owner's ChatGPT access switch, and the server time it was read. The owner comes only from the
   hashed device record. It returns no health values, imports, coverage, browser identity,
   email, owner or device IDs, device name, or credentials, and it writes nothing. An unknown,
   malformed, or revoked token gets `401` with code `device_revoked` (body and `X-HC-Error`).
   The app uses it to show whether ChatGPT can read the synced data; it reads no Health
   Connect data and does not need background access.

The device token can upload snapshots, disconnect itself (`DELETE /api/sync`), and read that
connection status. It cannot read health data, change ChatGPT access, create codes, or use any
browser or MCP route, because those require a signed-in Sites identity.

Each owner has one active device (enforced by a partial unique index), which avoids mixed
snapshot authority between phones. Pairing again, for example on a new phone, disconnects the
previous one. The dashboard lists the paired phone, its last successful sync, and recent
disconnected phones, and **Disconnect phone** revokes it immediately. **Delete everything**
also deletes all devices and pending codes in the same transaction, so scheduled jobs cannot
repopulate the data until you pair again. Pairing does not turn on ChatGPT access; that remains
a separate dashboard switch.

## Snapshot format (schemaVersion 1)

Automatic sync and the optional export file use the same JSON document.

```json
{
  "schemaVersion": 1,
  "source": "android-health-connect",
  "generatedAt": "2026-10-27T19:30:00Z",
  "timezone": "America/Chicago",
  "rangeStart": "2026-10-21",
  "rangeEnd": "2026-10-27",
  "categories": ["steps", "sleepMinutes", "weightKg"],
  "days": [
    {"date": "2026-10-21", "start": "2026-10-21T00:00:00-05:00", "end": "2026-10-22T00:00:00-05:00",
     "sleepWindowStart": "2026-10-20T18:00:00-05:00", "sleepWindowEnd": "2026-10-21T18:00:00-05:00",
     "steps": 8123, "sleepMinutes": 412.5, "weightKg": 80.25},
    {"date": "2026-10-22", "start": "...", "end": "...", "sleepWindowStart": "...", "sleepWindowEnd": "...", "steps": 0}
  ]
}
```

| Field | Meaning |
| --- | --- |
| `steps` | `StepsRecord.COUNT_TOTAL` for the local day (midnight to midnight). |
| `sleepMinutes` | `SleepSessionRecord.SLEEP_DURATION_TOTAL` from 18:00 the previous day to 18:00 this day, so a night's sleep counts toward the date you wake up. |
| `heartRateAvgBpm` | `HeartRateRecord.BPM_AVG`: the average of all samples that day. It is not resting heart rate. |
| `weightKg` | `WeightRecord.WEIGHT_AVG`: the average of that day's entries. It is not the latest entry. |
| `distanceMeters` | `DistanceRecord.DISTANCE_TOTAL`. |
| `activeCaloriesKcal` | `ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL` (excludes basal energy). |

- **Absent is not zero.** A missing field means Health Connect returned no data for that day; `0` means a recorded zero. `null` is rejected.
- **Every date appears.** `days` contains one entry per date from `rangeStart` to `rangeEnd` (1 to 30 days), even when a day has no values.
- **Day boundaries are local.** `start` and `end` are the first instants of the local day and the next day in `timezone`. They are computed with `LocalDate.atStartOfDay(zone)`, which handles 23- and 25-hour DST days and zones that skip midnight.
- **`categories` is the snapshot's scope.** It lists exactly what was read. The app refuses to sync or export unless every selected category is granted.

Validation lives in `cloud/lib/health/validate.ts`. The server rejects:

- unknown fields;
- payloads over 64 KiB or covering more than 30 days;
- wrong `schemaVersion` or `source`;
- invalid timezones or dates;
- `start`/`end` values that are not that local day's boundaries;
- missing, duplicate, or out-of-order dates;
- metrics that are not listed in `categories`;
- non-finite, negative, fractional-step, or unrealistic values (bounds in `cloud/lib/health/metrics.ts`);
- `generatedAt` in the future;
- a range ending after the export's local date.

## Import semantics

These rules apply to automatic sync and manual uploads alike.

- **Snapshot replace.** In one D1 batch transaction, an import deletes and rewrites every stored day in its range. Data you deleted on the phone, or categories you stopped syncing, therefore do not linger for those dates. Days outside the range are kept.
- **Idempotent.** Re-sending an identical snapshot (SHA-256 of the normalized payload, unique per owner) returns `duplicate` and changes nothing.
- **No stale overwrite.** A snapshot older than data already stored for an overlapping date is rejected with HTTP 409. The check runs inside the import transaction.
- **Provenance.** Each import records `upload_method` (`device_sync` or `browser_upload`) and the device. Rows imported before this version default to `browser_upload`.
- **Bounded history.** At most 20 superseded import records, and the 5 most recent disconnected devices, are kept per owner.
- **Only daily aggregates are stored.** No raw samples or source apps. For devices, only a display name such as the phone model is kept, plus pairing, last-attempt, and last-sync times.

## ChatGPT tools (MCP)

`POST /mcp` speaks JSON-RPC. It supports MCP `2026-07-28` (`server/discover`, `tools/list`, `tools/call`) and also answers classic `initialize` and `ping`; notifications return 202.

Every tool is annotated `readOnlyHint: true`, `destructiveHint: false`, `idempotentHint: true`, `openWorldHint: false`, and declares `additionalProperties: false`, so a caller cannot pass an owner ID.

| Tool | Returns |
| --- | --- |
| `get_health_connection_status` | Whether ChatGPT access is on; sync status (`sync.mode` is `automatic`, `paired_manual`, `paired_awaiting_first_sync`, or `not_paired`, plus the device name, last successful sync, last attempt and trigger, and a scheduling note); stored coverage; the latest snapshot's time, age, freshness, timezone, categories, and `uploadMethod`; and metric units. It returns no health values or credentials. |
| `get_daily_health_metrics` | Per-day values with a status for each metric: `value`, `no_data`, `not_exported`, or `not_imported`. Also partial-day flags, day and sleep windows, units, and the per-day `uploadMethod`. `exportsUsed` lists the snapshots used, each with its freshness and `uploadMethod`. Defaults to the 7 days ending at the latest stored date; at most 92 days. |
| `get_health_metric_summary` | For each metric: days with data, gap counts, mean, min, max, latest value, and an earlier-versus-later descriptive trend (at least 4 points; changes under 5% are "flat"). Partial days are excluded. Defaults to 30 days; at most 366. |

- **Owner scoping.** The owner is always the `oai-authenticated-user-id` header that Sites injects.
- **Consent gate.** Until you turn on "ChatGPT plugin access" on the dashboard, or tick the consent box during a manual upload, the data tools return `isError` with an explanation. Pairing and device uploads never turn it on.
- **No credentials from tools.** No tool creates connection codes or returns tokens or device IDs; pairing starts only in the signed-in browser.
- **Wording.** Results state that the data is not from ChatGPT Health and is not medical advice.

## Android sync behavior

- **What each run sends.** Each run reads the selected categories for the last 30 local days with the same aggregate reader as manual export: source-priority deduplication for activity and sleep, `LocalDate.atStartOfDay` windows for DST, and absent values rather than zeros. It POSTs the JSON directly; no file is written.
- **When it pauses.** A run never sends a partial or empty snapshot. If a selected category or background access is not granted (including revocation mid-read), Health Connect is unavailable, or the Site revoked the device or refused the access key, the run records the reason, cancels periodic work, and does not retry. Opening the app resumes automatically once permissions and categories are fixed; a revoked device needs a new pairing.
- **When it retries.** Network errors, timeouts, HTTP 408, 425, 429, 5xx, and Health Connect errors retry with exponential backoff (30 s base), up to 4 attempts per run, then wait for the next period. Stale (409) and invalid (4xx) responses do not retry.
- **When settings change.** Changing categories restarts queued work with the current selection. Every pairing or disconnect increments a generation counter, so a run that was already reading cannot upload with old credentials.
- **How requests are made.** HTTPS only (cleartext disabled), redirects never followed on credentialed requests, connect and read timeouts of 15 s and 30 s, and response reads capped at 16 KiB.
