# Agent instructions

Health Connect Summaries is an Android app (`android/`) plus a ChatGPT Sites app (`cloud/`) that
serves a read-only MCP server. Each person deploys their **own owner-private copy**.

## Rules

- **Never reuse a Site identity.** This repository's `cloud/.openai/hosting.json` has no
  `project_id`. Create a new Site and store its ID only in that Site's checkout. Never copy a
  `project_id`, Site URL, signing key, or APK from another person's copy.
- **Keep the Site owner-private.** Don't change its audience.
- **Deploy only through Sites.** Browser and MCP routes trust the `oai-authenticated-user-*`
  headers that Sites injects after sign-in, and the phone routes rely on the private-Site gate.
  Don't deploy with `wrangler deploy` or any other host: there, anyone could forge those headers.
- **Secrets stay out of everything visible.** `HC_SITE_SERVICE_TOKEN` is set only through the
  native Sites tools. Never put it, connection codes, device tokens, or keystore passwords in
  source, chat, logs, URLs, shell arguments, or files. Don't ask the user to paste them.
- **No real health data.** Use synthetic fixtures, and only under `cloud/test/` and
  `android/app/src/test/`. Never commit exports, databases, APKs, or keystores.
- **Keep the privacy defaults.** Every query is owner-scoped; ChatGPT access is off until the
  owner turns it on; pairing and uploads never turn it on. Keep the tests that check this.
- **Migrations are append-only.** Never edit an applied file in `cloud/drizzle/`.
- **Don't overclaim.** No statements about store approval, plugin-directory listing, medical use,
  or regulatory compliance.

## Checks

```bash
cd cloud && npm test && npx tsc --noEmit -p tsconfig.backend.json   # standalone backend checks
# In an assembled Site checkout (see below): npm test && npm run typecheck && npm run lint
source .toolchain/env.sh && cd android && ./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
python3 scripts/check-public-tree.py                                  # no secrets or private files
```

## Private copy setup

Use the Sites plugin's workflow. `<plugin-root>` is the installed Sites plugin directory (it has
`scripts/project-setup.mjs`); `<repo>` is this repository; `<checkout>` is a new empty directory
outside `<repo>`. Pass credentials through stdin or native tools only.

1. **Starter.** In `<checkout>`: `node <plugin-root>/scripts/project-setup.mjs`.
2. **App source.** `node <repo>/scripts/overlay-site.mjs <checkout>`. It refuses checkouts that
   lack the starter files listed in `cloud/sites-starter-files.txt`.
3. **New Site.** Call native `create_site` (it starts private and unpublished). Immediately run
   `node <plugin-root>/scripts/set-project-id.mjs --project-id <returned-id>` in `<checkout>`
   (Worker build: no `--static-directory`). Note the Site URL.
4. **Dependencies and tests.** `node <plugin-root>/scripts/install-dependencies.mjs`, then
   `npm test` and `npm run typecheck` in `<checkout>`.
5. **Service access secret.** Call `get_site`. Set the Site's runtime secret
   `HC_SITE_SERVICE_TOKEN` to its `siwc_bypass_bearer_token` with the native Sites secret tool,
   passing the value directly between tools. If `get_site` omits it, inspect the supported
   connection options; don't create or rotate credentials just to check. Without the secret the
   dashboard says pairing isn't configured.
6. **Android app.** `<repo>/android/scripts/setup-toolchain.sh`, `source <repo>/.toolchain/env.sh`,
   then in `<repo>/android`:
   `./gradlew --no-daemon testDebugUnitTest assembleDebug -PhcSiteUrl=<Site URL>`.
   Copy `app/build/outputs/apk/debug/app-debug.apk` to
   `<checkout>/public/downloads/health-connect-export.apk`. Optionally add the source:
   `git -C <repo> archive --format=zip -o <checkout>/public/downloads/health-connect-source.zip HEAD`.
   Tell the user to keep `~/.android/debug.keystore`: updates must use the same key.
7. **Publish.** Package with `node <plugin-root>/scripts/site-workflow.mjs --project-id <id>`
   (credential from `create_site` or `create_source_repository_write_credential` via stdin;
   `commands: [["node", "<plugin-root>/scripts/build-site.mjs"]]`; an absolute `archivePath`
   outside `<repo>`). Then `save_version_and_deploy_private`. Sites applies the migrations and,
   because `hosting.json` declares `mcp`, creates the owner's private plugin. If the secret
   tool needed a deployed version first, set the secret now and redeploy.
8. **Plugin.** If the tools aren't available, `get_site` with `include_mcp_connection: true`,
   then `plugin_management.suggest_plugins` with the returned plugin ID.
9. **Hand off.** Give the Site URL and the phone steps from `docs/setup.md`. Say what was not
   verified (for example, no phone was available).

To update an existing copy: open the Site with the Sites workflow (existing `project_id`), re-run
step 2, rebuild the app with the same key and `-PhcSiteUrl`, and publish. Keep the audience.
