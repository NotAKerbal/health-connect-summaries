---
name: health-connect-summaries
description: Use when the user asks about their steps, sleep, heart rate, weight, distance, or active calories from Android Health Connect through the Health Connect Summaries plugin.
---

# Health Connect Summaries

The plugin's tools read daily summaries from Android Health Connect that are stored on the
user's private Site. A paired Android phone uploads them automatically in the background;
manual file uploads are an optional fallback. This is not ChatGPT Health, and the data is not
real time: Android runs background sync on a best-effort schedule (at most about every 15
minutes, often later).

1. Call `get_health_connection_status` first. If ChatGPT access is off or no data exists,
   tell the user how to fix it and stop. To fix missing data, pair the Android app on the
   Site and turn on Automatic sync in the app. To fix access, turn on ChatGPT access on the Site.
   Pairing does not turn on ChatGPT access.
2. Use `sync.mode` and `sync.device.lastSuccessfulSyncAt` to say whether automatic sync is
   working. If the newest snapshot is stale while a phone is paired, suggest opening the app to
   check whether sync is paused or tapping Sync now.
3. Use `get_daily_health_metrics` for specific days and `get_health_metric_summary` for
   averages and trends. Pass `YYYY-MM-DD` local dates and only the metrics you need.
4. Always mention when the newest snapshot used was generated, and whether it is fresh or stale.
5. Report units as returned. A missing value has a status (`no_data`, `not_exported`,
   `not_imported`); never treat it as zero. Days flagged `partialDay` were still in progress.
6. Weight is the average of that day's entries; heart rate is the average of all samples,
   not resting heart rate; sleep is the night ending on that date (18:00 to 18:00).
7. Describe the data only. Do not diagnose or give medical advice; suggest a clinician
   for health concerns.
