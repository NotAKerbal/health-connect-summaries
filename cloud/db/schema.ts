import { sql } from "drizzle-orm";
import { index, integer, primaryKey, real, sqliteTable, text, uniqueIndex } from "drizzle-orm/sqlite-core";

// Every table is keyed by owner_id, the Site-scoped `oai-authenticated-user-id`
// injected by Sites. Only daily aggregates are stored; raw Health Connect samples never are.

export const healthImports = sqliteTable(
  "health_imports",
  {
    id: text("id").primaryKey(),
    ownerId: text("owner_id").notNull(),
    payloadSha256: text("payload_sha256").notNull(),
    schemaVersion: integer("schema_version").notNull(),
    source: text("source").notNull(),
    generatedAt: text("generated_at").notNull(),
    generatedAtMs: integer("generated_at_ms").notNull(),
    timezone: text("timezone").notNull(),
    rangeStart: text("range_start").notNull(),
    rangeEnd: text("range_end").notNull(),
    /** JSON array of exported metric keys (the categories that were granted and selected). */
    categories: text("categories").notNull(),
    dayCount: integer("day_count").notNull(),
    payloadBytes: integer("payload_bytes").notNull(),
    importedAt: text("imported_at").notNull(),
    importedAtMs: integer("imported_at_ms").notNull(),
    /** How the snapshot arrived: "browser_upload" (manual file) or "device_sync" (paired phone). */
    uploadMethod: text("upload_method").notNull().default("browser_upload"),
    /** The paired device that uploaded a device_sync snapshot; null for browser uploads. */
    deviceId: text("device_id"),
  },
  (t) => [
    uniqueIndex("health_imports_owner_hash").on(t.ownerId, t.payloadSha256),
    index("health_imports_owner_imported").on(t.ownerId, t.importedAtMs),
  ],
);

export const healthDays = sqliteTable(
  "health_days",
  {
    ownerId: text("owner_id").notNull(),
    date: text("date").notNull(),
    importId: text("import_id").notNull(),
    dayStart: text("day_start").notNull(),
    dayEnd: text("day_end").notNull(),
    dayEndMs: integer("day_end_ms").notNull(),
    sleepWindowStart: text("sleep_window_start"),
    sleepWindowEnd: text("sleep_window_end"),
    steps: integer("steps"),
    sleepMinutes: real("sleep_minutes"),
    heartRateAvgBpm: real("heart_rate_avg_bpm"),
    weightKg: real("weight_kg"),
    distanceMeters: real("distance_meters"),
    activeCaloriesKcal: real("active_calories_kcal"),
  },
  (t) => [
    primaryKey({ columns: [t.ownerId, t.date] }),
    index("health_days_owner_import").on(t.ownerId, t.importId),
  ],
);

export const ownerSettings = sqliteTable("owner_settings", {
  ownerId: text("owner_id").primaryKey(),
  /** 1 when the owner has explicitly allowed the ChatGPT plugin tools to read their summaries. */
  chatgptAccess: integer("chatgpt_access").notNull().default(0),
  chatgptAccessUpdatedAt: text("chatgpt_access_updated_at"),
});

/**
 * One-time pairing codes created in the signed-in browser. Only the SHA-256 of the random
 * token is stored. A row is single-use (redeemed_at_ms) and expires after a few minutes.
 */
export const syncPairings = sqliteTable(
  "sync_pairings",
  {
    tokenHash: text("token_hash").primaryKey(),
    ownerId: text("owner_id").notNull(),
    createdAtMs: integer("created_at_ms").notNull(),
    expiresAtMs: integer("expires_at_ms").notNull(),
    redeemedAtMs: integer("redeemed_at_ms"),
    deviceId: text("device_id"),
  },
  (t) => [index("sync_pairings_owner").on(t.ownerId)],
);

/**
 * Paired phones. The owner comes only from the pairing row the device redeemed; the device
 * token can only upload, disconnect itself, and read its own connection status (no health
 * data); it is stored as a SHA-256 hash. At most one active device per owner.
 */
export const syncDevices = sqliteTable(
  "sync_devices",
  {
    id: text("id").primaryKey(),
    ownerId: text("owner_id").notNull(),
    tokenHash: text("token_hash").notNull(),
    name: text("name").notNull(),
    pairedAt: text("paired_at").notNull(),
    pairedAtMs: integer("paired_at_ms").notNull(),
    revokedAt: text("revoked_at"),
    revokedReason: text("revoked_reason"),
    lastSyncAt: text("last_sync_at"),
    lastImportId: text("last_import_id"),
    lastAttemptAt: text("last_attempt_at"),
    lastAttemptStatus: text("last_attempt_status"),
    lastTrigger: text("last_trigger"),
    autoSyncReported: integer("auto_sync_reported"),
  },
  (t) => [
    uniqueIndex("sync_devices_token_hash").on(t.tokenHash),
    index("sync_devices_owner").on(t.ownerId, t.pairedAtMs),
    uniqueIndex("sync_devices_one_active").on(t.ownerId).where(sql`revoked_at IS NULL`),
  ],
);
