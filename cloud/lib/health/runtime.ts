import { env } from "cloudflare:workers";
import { getHealthDb } from "./db.ts";
import type { SyncDeps } from "./sync-api.ts";
import { isUsableServiceToken } from "./sync.ts";

/** Runtime dependencies for the sync routes. The service token never leaves the server except in a pairing response. */
export function syncDeps(): SyncDeps {
  return { db: getHealthDb(), nowMs: Date.now(), serviceToken: env.HC_SITE_SERVICE_TOKEN };
}

/** Whether pairing can work: reports presence of the service access key, never its value. */
export function syncConfigured(): boolean {
  return isUsableServiceToken(env.HC_SITE_SERVICE_TOKEN);
}
