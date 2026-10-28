# Health Connect Summaries (Site)

Owner-private ChatGPT Sites app. It receives daily Health Connect snapshots from the paired
Android app (or an optional browser upload), shows them on a dashboard, and exposes them
read-only to ChatGPT through the MCP server at `/mcp`.

- `lib/health/`: validation, D1 store, queries and summaries, MCP handler, pairing and sync
- `app/`: dashboard (`/`), privacy page (`/privacy`), browser APIs, phone APIs
  (`/api/sync/pair`, `/api/sync`, `/api/sync/status`), and `/mcp`
- `drizzle/`: append-only D1 migrations, applied by Sites on publish
- `test/`: `npm test` (node:test with SQLite running the real migrations) and `test/e2e/smoke.ts`
- `scripts/sync-check.ts`: credential-safe check of the hosted phone path (hidden stdin)

The runtime secret `HC_SITE_SERVICE_TOKEN` (the Site's own service-access token) is required for
pairing. Configure it through the native Sites tools, never in source.

The Sites Vinext starter supplies the build integration listed in `sites-starter-files.txt`.
See `docs/setup.md`, `docs/architecture.md`, and `docs/development.md` in the repository.
