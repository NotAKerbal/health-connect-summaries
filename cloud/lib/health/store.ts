// Owner-scoped persistence on D1. Every statement filters by owner_id; callers pass the
// authenticated Site user ID and never a value taken from request bodies or tool arguments.

import { METRIC_KEYS, METRICS, isMetricKey, type MetricKey } from "./metrics.ts";
import { ACTIVE_DEVICE_GUARD, DeviceRevokedError, isDeviceActive, type AuthenticatedDevice, type SyncTrigger } from "./sync.ts";
import type { ValidExport } from "./validate.ts";

/** The subset of the D1 API this module uses (also implemented by the test adapter). */
export interface SqlStatement {
  bind(...values: unknown[]): SqlStatement;
  first<T = Record<string, unknown>>(): Promise<T | null>;
  all<T = Record<string, unknown>>(): Promise<{ results: T[] }>;
  run(): Promise<unknown>;
}
export interface SqlDatabase {
  prepare(sql: string): SqlStatement;
  batch(statements: SqlStatement[]): Promise<unknown[]>;
}

/** Superseded imports kept for the owner's import history. */
const IMPORT_HISTORY_LIMIT = 20;

export class StaleImportError extends Error {
  newerGeneratedAt: string;
  constructor(newerGeneratedAt: string) {
    super(
      `A newer export (generated ${newerGeneratedAt}) already covers part of this date range. Create a fresh export on your phone and upload that instead.`,
    );
    this.name = "StaleImportError";
    this.newerGeneratedAt = newerGeneratedAt;
  }
}

export type ImportOutcome = {
  status: "imported" | "duplicate";
  importId: string;
  rangeStart: string;
  rangeEnd: string;
  dayCount: number;
  categories: MetricKey[];
};

export type UploadMethod = "browser_upload" | "device_sync";

export type ImportRecord = {
  id: string;
  uploadMethod: UploadMethod;
  generatedAt: string;
  generatedAtMs: number;
  importedAt: string;
  timezone: string;
  rangeStart: string;
  rangeEnd: string;
  categories: MetricKey[];
  dayCount: number;
  /** Days whose stored values still come from this import (later imports may supersede days). */
  activeDays: number;
};

export type StoredDay = {
  date: string;
  importId: string;
  dayStart: string;
  dayEnd: string;
  dayEndMs: number;
  sleepWindowStart: string | null;
  sleepWindowEnd: string | null;
  generatedAt: string;
  generatedAtMs: number;
  timezone: string;
  categories: MetricKey[];
  uploadMethod: UploadMethod;
  values: Partial<Record<MetricKey, number>>;
};

type ImportRow = {
  id: string;
  upload_method: string;
  generated_at: string;
  generated_at_ms: number;
  imported_at: string;
  timezone: string;
  range_start: string;
  range_end: string;
  categories: string;
  day_count: number;
  active_days: number;
};

function parseCategories(json: string): MetricKey[] {
  try {
    const parsed: unknown = JSON.parse(json);
    return Array.isArray(parsed) ? parsed.filter(isMetricKey) : [];
  } catch {
    return [];
  }
}

function uploadMethodOf(value: unknown): UploadMethod {
  return value === "device_sync" ? "device_sync" : "browser_upload";
}

function newId(): string {
  return crypto.randomUUID();
}

export type DeviceUpload = {
  device: AuthenticatedDevice;
  trigger: SyncTrigger | null;
  autoSync: boolean | null;
};

/**
 * Applies an export as an authoritative snapshot of its date range, atomically:
 * every stored day in [rangeStart, rangeEnd] is replaced, so data deleted on the phone
 * or for categories no longer exported does not linger. Days outside the range are kept.
 *
 * For a paired-device upload (`meta.deviceUpload`), the device's active state is checked inside
 * the same transaction as the writes, so a revocation or "delete all" that commits after the
 * request was authenticated still prevents the write. Device uploads never change ChatGPT access.
 */
export async function importSnapshot(
  db: SqlDatabase,
  ownerId: string,
  data: ValidExport,
  meta: { sha256: string; payloadBytes: number; nowMs: number; enableSharing?: boolean; deviceUpload?: DeviceUpload },
): Promise<ImportOutcome> {
  const upload = meta.deviceUpload;
  if (upload && upload.device.ownerId !== ownerId) throw new Error("device owner mismatch");
  const enableSharing = Boolean(meta.enableSharing) && !upload;
  const importedAt = new Date(meta.nowMs).toISOString();
  const deviceGuard = upload ? ` AND ${ACTIVE_DEVICE_GUARD}` : "";
  const deviceBinds = upload ? [upload.device.id, upload.device.tokenHash, ownerId] : [];
  const touchDevice = (importId: string) =>
    db
      .prepare(
        `UPDATE sync_devices SET last_sync_at = ?, last_import_id = ?, last_attempt_at = ?, last_attempt_status = 'ok',
           last_trigger = COALESCE(?, last_trigger), auto_sync_reported = COALESCE(?, auto_sync_reported)
          WHERE id = ? AND token_hash = ? AND owner_id = ? AND revoked_at IS NULL
            AND EXISTS (SELECT 1 FROM health_imports WHERE id = ? AND owner_id = ?)`,
      )
      .bind(
        importedAt,
        importId,
        importedAt,
        upload!.trigger,
        upload!.autoSync === null ? null : upload!.autoSync ? 1 : 0,
        upload!.device.id,
        upload!.device.tokenHash,
        ownerId,
        importId,
        ownerId,
      );

  const existing = await findImportByHash(db, ownerId, meta.sha256);
  if (existing) return duplicate(db, ownerId, existing, data, meta.nowMs, enableSharing, upload, touchDevice);

  const newer = await db
    .prepare(
      `SELECT i.generated_at AS generated_at
         FROM health_days d JOIN health_imports i ON i.id = d.import_id AND i.owner_id = d.owner_id
        WHERE d.owner_id = ? AND d.date BETWEEN ? AND ? AND i.generated_at_ms > ?
        ORDER BY i.generated_at_ms DESC LIMIT 1`,
    )
    .bind(ownerId, data.rangeStart, data.rangeEnd, data.generatedAtMs)
    .first<{ generated_at: string }>();
  if (newer) throw new StaleImportError(newer.generated_at);

  const importId = newId();
  const statements: SqlStatement[] = [
    db
      .prepare(
        `INSERT INTO health_imports (id, owner_id, payload_sha256, schema_version, source, generated_at,
           generated_at_ms, timezone, range_start, range_end, categories, day_count, payload_bytes,
           imported_at, imported_at_ms, upload_method, device_id)
         SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
          WHERE NOT EXISTS (
            SELECT 1 FROM health_days d
              JOIN health_imports i ON i.id = d.import_id AND i.owner_id = d.owner_id
             WHERE d.owner_id = ? AND d.date BETWEEN ? AND ? AND i.generated_at_ms > ?
          )${deviceGuard}`,
      )
      .bind(
        importId,
        ownerId,
        meta.sha256,
        data.schemaVersion,
        data.source,
        data.generatedAt,
        data.generatedAtMs,
        data.timezone,
        data.rangeStart,
        data.rangeEnd,
        JSON.stringify(data.categories),
        data.days.length,
        meta.payloadBytes,
        importedAt,
        meta.nowMs,
        upload ? "device_sync" : "browser_upload",
        upload ? upload.device.id : null,
        ownerId,
        data.rangeStart,
        data.rangeEnd,
        data.generatedAtMs,
        ...deviceBinds,
      ),
    db
      .prepare(`DELETE FROM health_days WHERE owner_id = ? AND date BETWEEN ? AND ?
                  AND EXISTS (SELECT 1 FROM health_imports WHERE id = ? AND owner_id = ?)`)
      .bind(ownerId, data.rangeStart, data.rangeEnd, importId, ownerId),
  ];
  const columns = METRIC_KEYS.map((k) => METRICS[k].column);
  for (const day of data.days) {
    statements.push(
      db
        .prepare(
          `INSERT INTO health_days (owner_id, date, import_id, day_start, day_end, day_end_ms,
             sleep_window_start, sleep_window_end, ${columns.join(", ")})
           SELECT ?, ?, ?, ?, ?, ?, ?, ?, ${columns.map(() => "?").join(", ")}
            WHERE EXISTS (SELECT 1 FROM health_imports WHERE id = ? AND owner_id = ?)`,
        )
        .bind(
          ownerId,
          day.date,
          importId,
          day.start,
          day.end,
          day.endMs,
          day.sleepWindowStart,
          day.sleepWindowEnd,
          ...METRIC_KEYS.map((k) => day.metrics[k] ?? null),
          importId,
          ownerId,
        ),
    );
  }
  if (upload) statements.push(touchDevice(importId));
  if (enableSharing) {
    statements.push(
      db.prepare(`INSERT INTO owner_settings (owner_id, chatgpt_access, chatgpt_access_updated_at)
                    SELECT ?, 1, ? WHERE EXISTS (SELECT 1 FROM health_imports WHERE id = ? AND owner_id = ?)
                    ON CONFLICT(owner_id) DO UPDATE SET chatgpt_access = 1,
                      chatgpt_access_updated_at = excluded.chatgpt_access_updated_at`)
        .bind(ownerId, importedAt, importId, ownerId),
    );
  }
  // Bound history: drop superseded imports beyond the most recent IMPORT_HISTORY_LIMIT.
  statements.push(
    db
      .prepare(
        `DELETE FROM health_imports
          WHERE owner_id = ?
            AND id NOT IN (SELECT DISTINCT import_id FROM health_days WHERE owner_id = ?)
            AND id NOT IN (SELECT id FROM health_imports WHERE owner_id = ?
                            ORDER BY imported_at_ms DESC LIMIT ${IMPORT_HISTORY_LIMIT})`,
      )
      .bind(ownerId, ownerId, ownerId),
  );

  try {
    // The freshness condition and every dependent write are inside the same D1
    // transaction: a concurrent newer upload cannot be overwritten after the precheck.
    await db.batch(statements);
  } catch (error) {
    // A concurrent upload of the same payload violates the (owner_id, payload_sha256) index.
    const raced = await findImportByHash(db, ownerId, meta.sha256);
    if (raced) return duplicate(db, ownerId, raced, data, meta.nowMs, enableSharing, upload, touchDevice);
    throw error;
  }
  if (!(await findImportByHash(db, ownerId, meta.sha256))) {
    // Nothing was written: either the device was revoked meanwhile, or newer data exists.
    if (upload && !(await isDeviceActive(db, upload.device))) throw new DeviceRevokedError();
    const latest = await db.prepare(`SELECT MAX(generated_at) AS generated_at FROM health_imports WHERE owner_id = ?`)
      .bind(ownerId).first<{ generated_at: string }>();
    throw new StaleImportError(latest?.generated_at ?? "more recently");
  }
  return outcome("imported", importId, data);
}

async function duplicate(
  db: SqlDatabase,
  ownerId: string,
  importId: string,
  data: ValidExport,
  nowMs: number,
  enableSharing: boolean,
  upload: DeviceUpload | undefined,
  touchDevice: (importId: string) => SqlStatement,
): Promise<ImportOutcome> {
  if (upload) {
    await touchDevice(importId).run();
    if (!(await isDeviceActive(db, upload.device))) throw new DeviceRevokedError();
  }
  if (enableSharing) await setSharing(db, ownerId, true, nowMs);
  return outcome("duplicate", importId, data);
}

function outcome(status: ImportOutcome["status"], importId: string, data: ValidExport): ImportOutcome {
  return {
    status,
    importId,
    rangeStart: data.rangeStart,
    rangeEnd: data.rangeEnd,
    dayCount: data.days.length,
    categories: data.categories,
  };
}

async function findImportByHash(db: SqlDatabase, ownerId: string, sha256: string): Promise<string | null> {
  const row = await db
    .prepare(`SELECT id FROM health_imports WHERE owner_id = ? AND payload_sha256 = ?`)
    .bind(ownerId, sha256)
    .first<{ id: string }>();
  return row?.id ?? null;
}

export async function listImports(db: SqlDatabase, ownerId: string, limit = IMPORT_HISTORY_LIMIT): Promise<ImportRecord[]> {
  const { results } = await db
    .prepare(
      `SELECT i.id, i.upload_method, i.generated_at, i.generated_at_ms, i.imported_at, i.timezone, i.range_start,
              i.range_end, i.categories, i.day_count,
              (SELECT COUNT(*) FROM health_days d WHERE d.owner_id = i.owner_id AND d.import_id = i.id) AS active_days
         FROM health_imports i
        WHERE i.owner_id = ?
        ORDER BY i.imported_at_ms DESC
        LIMIT ?`,
    )
    .bind(ownerId, limit)
    .all<ImportRow>();
  return results.map((r) => ({
    id: r.id,
    uploadMethod: uploadMethodOf(r.upload_method),
    generatedAt: r.generated_at,
    generatedAtMs: Number(r.generated_at_ms),
    importedAt: r.imported_at,
    timezone: r.timezone,
    rangeStart: r.range_start,
    rangeEnd: r.range_end,
    categories: parseCategories(r.categories),
    dayCount: Number(r.day_count),
    activeDays: Number(r.active_days),
  }));
}

export async function getStoredDays(db: SqlDatabase, ownerId: string, start: string, end: string): Promise<StoredDay[]> {
  const { results } = await db
    .prepare(
      `SELECT d.*, i.generated_at AS generated_at, i.generated_at_ms AS generated_at_ms,
              i.timezone AS timezone, i.categories AS categories, i.upload_method AS upload_method
         FROM health_days d JOIN health_imports i ON i.id = d.import_id AND i.owner_id = d.owner_id
        WHERE d.owner_id = ? AND d.date BETWEEN ? AND ?
        ORDER BY d.date ASC`,
    )
    .bind(ownerId, start, end)
    .all<Record<string, unknown>>();
  return results.map((r) => {
    const categories = parseCategories(String(r.categories));
    const values: Partial<Record<MetricKey, number>> = {};
    for (const key of METRIC_KEYS) {
      const v = r[METRICS[key].column];
      if (typeof v === "number" && Number.isFinite(v)) values[key] = v;
    }
    return {
      date: String(r.date),
      importId: String(r.import_id),
      dayStart: String(r.day_start),
      dayEnd: String(r.day_end),
      dayEndMs: Number(r.day_end_ms),
      sleepWindowStart: (r.sleep_window_start as string | null) ?? null,
      sleepWindowEnd: (r.sleep_window_end as string | null) ?? null,
      generatedAt: String(r.generated_at),
      generatedAtMs: Number(r.generated_at_ms),
      timezone: String(r.timezone),
      categories,
      uploadMethod: uploadMethodOf(r.upload_method),
      values,
    };
  });
}

export type Coverage = { firstDate: string | null; lastDate: string | null; daysStored: number };

export async function getCoverage(db: SqlDatabase, ownerId: string): Promise<Coverage> {
  const row = await db
    .prepare(`SELECT MIN(date) AS first, MAX(date) AS last, COUNT(*) AS n FROM health_days WHERE owner_id = ?`)
    .bind(ownerId)
    .first<{ first: string | null; last: string | null; n: number }>();
  return { firstDate: row?.first ?? null, lastDate: row?.last ?? null, daysStored: Number(row?.n ?? 0) };
}

export async function deleteImport(db: SqlDatabase, ownerId: string, importId: string): Promise<boolean> {
  const exists = await db
    .prepare(`SELECT id FROM health_imports WHERE owner_id = ? AND id = ?`)
    .bind(ownerId, importId)
    .first<{ id: string }>();
  if (!exists) return false;
  await db.batch([
    db.prepare(`DELETE FROM health_days WHERE owner_id = ? AND import_id = ?`).bind(ownerId, importId),
    db.prepare(`DELETE FROM health_imports WHERE owner_id = ? AND id = ?`).bind(ownerId, importId),
  ]);
  return true;
}

/**
 * Deletes all of the owner's health data, turns ChatGPT access off, and disconnects every paired
 * device and pending pairing code in the same transaction, so a scheduled phone upload cannot
 * repopulate the data afterwards; the phone must be paired again.
 */
export async function deleteAllOwnerData(db: SqlDatabase, ownerId: string): Promise<void> {
  await db.batch([
    db.prepare(`DELETE FROM sync_pairings WHERE owner_id = ?`).bind(ownerId),
    db.prepare(`DELETE FROM sync_devices WHERE owner_id = ?`).bind(ownerId),
    db.prepare(`DELETE FROM health_days WHERE owner_id = ?`).bind(ownerId),
    db.prepare(`DELETE FROM health_imports WHERE owner_id = ?`).bind(ownerId),
    db.prepare(`DELETE FROM owner_settings WHERE owner_id = ?`).bind(ownerId),
  ]);
}

export type SharingState = { enabled: boolean; updatedAt: string | null };

export async function getSharing(db: SqlDatabase, ownerId: string): Promise<SharingState> {
  const row = await db
    .prepare(`SELECT chatgpt_access, chatgpt_access_updated_at FROM owner_settings WHERE owner_id = ?`)
    .bind(ownerId)
    .first<{ chatgpt_access: number; chatgpt_access_updated_at: string | null }>();
  return { enabled: Number(row?.chatgpt_access ?? 0) === 1, updatedAt: row?.chatgpt_access_updated_at ?? null };
}

export async function setSharing(db: SqlDatabase, ownerId: string, enabled: boolean, nowMs: number): Promise<SharingState> {
  const updatedAt = new Date(nowMs).toISOString();
  await db
    .prepare(
      `INSERT INTO owner_settings (owner_id, chatgpt_access, chatgpt_access_updated_at) VALUES (?, ?, ?)
       ON CONFLICT(owner_id) DO UPDATE SET chatgpt_access = excluded.chatgpt_access,
         chatgpt_access_updated_at = excluded.chatgpt_access_updated_at`,
    )
    .bind(ownerId, enabled ? 1 : 0, updatedAt)
    .run();
  return { enabled, updatedAt };
}
