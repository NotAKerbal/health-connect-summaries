import { env } from "cloudflare:workers";
import type { SqlDatabase } from "./store.ts";

export function getHealthDb(): SqlDatabase {
  if (!env.DB) throw new Error("D1 binding DB is unavailable");
  return env.DB as unknown as SqlDatabase;
}
