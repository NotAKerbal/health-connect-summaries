# Set up your own private copy

Every person runs their own copy: their own private Site, database, plugin, and app build.

## What you need

- **ChatGPT with Sites.** An account where you can create Sites and use the private plugin that a
  Site with an MCP server gets. Availability depends on plan and region; check before starting.
- **An agent with the Sites plugin**, such as Codex. It creates the Site, configures its secret,
  and publishes. You don't paste secrets into the chat.
- **Android phone** with Health Connect (Android 9 or later; built in on Android 14+). Background
  sync needs Health Connect's background-read feature, typically Android 14+ with current updates.
- **A machine that can build the Android app.** The supplied `android/scripts/setup-toolchain.sh`
  is for Linux x86-64 and downloads about 2.5 GB into `.toolchain/`. On macOS or another platform,
  use Android Studio with JDK 17 and Android SDK 36 instead.

## Copy/paste prompt

Clone this repository, open it in your agent, and send:

```text
Set up my own private copy of Health Connect Summaries from this repository using the Sites
plugin. Follow AGENTS.md, section "Private copy setup", exactly. Create a NEW owner-private Site
(never reuse a project_id from anywhere else) and keep it private. Configure HC_SITE_SERVICE_TOKEN
from my Site's own service access through the native Sites tools without showing it. Build the
Android app pinned to my Site's URL, add it to the Site's downloads, and publish. Then give me
the Site URL, the phone steps, and anything you could not verify. Never ask me to paste a secret.
```

The agent follows [AGENTS.md](../AGENTS.md). In short it:

1. creates a Site checkout with the Sites Vinext starter, then copies `cloud/` onto it with
   `scripts/overlay-site.mjs`;
2. creates a new private Site and saves its `project_id` in the checkout (never in this repo);
3. installs dependencies and runs the tests;
4. sets the runtime secret `HC_SITE_SERVICE_TOKEN` to the Site's own service-access token;
5. builds the Android app with `-PhcSiteUrl=<your Site URL>` and puts it in the Site's
   `public/downloads/`;
6. publishes privately. Sites applies the database migrations and creates your private plugin.

This repository contains only the app's own Site files. The Sites starter supplies the build
integration (listed in `cloud/sites-starter-files.txt`), so a Site is always assembled through
the Sites plugin rather than built from this repository alone.

## On your phone

1. Open your Site, sign in, and download the app. Allow installing it when Android asks.
2. On the dashboard tap **Create connection code**, then **Copy**. In the app tap
   **Paste connection code**, then **Connect**.
3. Tap **Continue** and choose categories in Health Connect.
4. Tap **Turn on automatic sync**, then **Turn on ChatGPT access** (opens your dashboard).

In ChatGPT, use your Site's plugin (Plugins → Personal → Created by you, if it isn't installed).

## Which app build?

| Build | How | Use |
| --- | --- | --- |
| **Pinned** (recommended) | `./gradlew assembleDebug -PhcSiteUrl=https://your-site.example` | Talks only to that Site; codes for any other site are refused. |
| **Generic** | `./gradlew assembleDebug` | Accepts a code from any public HTTPS Site, after you confirm its hostname. For a future shared release. |

Both keep your own machine's debug signing key. To install updates over an existing install you
must build with the same key; see [releasing](releasing.md#signing-keys).

## Updating

Pull the new version, re-run `node scripts/overlay-site.mjs <your Site checkout>`, rebuild the
app with the same key and `-PhcSiteUrl`, and republish. Pairing, settings, and data are kept.
