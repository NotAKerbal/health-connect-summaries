// Device pairing and narrowly scoped device credentials for automatic sync from the Android app.
//
// Trust model:
// - A signed-in browser (owner from `oai-authenticated-user-id`) creates a short-lived,
//   single-use pairing token. Only its SHA-256 hash is stored, bound to the owner.
// - The phone redeems it once (through the private Site's platform service-access gate, which
//   supplies no visitor identity). The owner is copied from the pairing row, never from the
//   request. Redemption returns a random device token; only its hash is stored.
// - The device token can upload snapshots, revoke itself, and read its own connection status
//   (connected, its last successful sync, whether ChatGPT access is on). It cannot read health
//   data, change ChatGPT access, or reach any browser route (those require a signed-in visitor).
// - Each owner has at most one active device; pairing again revokes the previous one.

import type { SqlDatabase } from "./store.ts";
import { sha256Hex } from "./validate.ts";

export const PAIRING_TTL_MS = 5 * 60_000;
export const CONNECTION_CODE_PREFIX = "HCS1.";
export const CONNECTION_CODE_VERSION = 1;
const PAIRING_PREFIX = "hcp_";
const DEVICE_PREFIX = "hcd_";
const TOKEN_BYTES = 32;
const DEVICE_HISTORY_LIMIT = 5;
const MAX_DEVICE_NAME = 64;
const MAX_SERVICE_TOKEN = 4096;

/** 43 base64url characters encode 32 random bytes. */
const TOKEN_BODY = "[A-Za-z0-9_-]{43}";
export const PAIRING_TOKEN_RE = new RegExp(`^${PAIRING_PREFIX}${TOKEN_BODY}$`);
export const DEVICE_TOKEN_RE = new RegExp(`^${DEVICE_PREFIX}${TOKEN_BODY}$`);
const SERVICE_TOKEN_RE = /^[\x21-\x7e]{8,4096}$/;

export class DeviceRevokedError extends Error {
  constructor() {
    super("This device is no longer connected to the Site. Pair it again from the dashboard.");
    this.name = "DeviceRevokedError";
  }
}

export type AuthenticatedDevice = { id: string; ownerId: string; tokenHash: string; name: string };

export type SyncTrigger = "scheduled" | "manual";

export type DeviceRecord = {
  id: string;
  name: string;
  pairedAt: string;
  revokedAt: string | null;
  revokedReason: string | null;
  lastSyncAt: string | null;
  lastAttemptAt: string | null;
  lastAttemptStatus: string | null;
  lastTrigger: SyncTrigger | null;
  /** What the phone last reported about its automatic sync switch; null before the first upload. */
  autoSyncReported: boolean | null;
  active: boolean;
};

type DeviceRow = {
  id: string;
  name: string;
  paired_at: string;
  revoked_at: string | null;
  revoked_reason: string | null;
  last_sync_at: string | null;
  last_attempt_at: string | null;
  last_attempt_status: string | null;
  last_trigger: string | null;
  auto_sync_reported: number | null;
};

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomToken(prefix: string): string {
  return prefix + base64Url(crypto.getRandomValues(new Uint8Array(TOKEN_BYTES)));
}

export function isUsableServiceToken(value: unknown): value is string {
  return typeof value === "string" && value.length <= MAX_SERVICE_TOKEN && SERVICE_TOKEN_RE.test(value);
}

/** Printable, single-line, bounded display name; never trusted for anything else. */
export function sanitizeDeviceName(value: unknown): string {
  const raw = typeof value === "string" ? value : "";
  const cleaned = raw.replace(/[^\p{L}\p{N}\p{P}\p{Zs}]/gu, "").replace(/\s+/g, " ").trim().slice(0, MAX_DEVICE_NAME).trim();
  return cleaned || "Android device";
}

export type ConnectionCode = {
  v: typeof CONNECTION_CODE_VERSION;
  origin: string;
  serviceToken: string;
  pairingToken: string;
  expiresAt: string;
};

/** The pasteable code shown once in the browser. It is a credential: never log or link it. */
export function encodeConnectionCode(code: ConnectionCode): string {
  return CONNECTION_CODE_PREFIX + base64Url(new TextEncoder().encode(JSON.stringify(code)));
}

export function decodeConnectionCode(text: string): ConnectionCode | null {
  const trimmed = text.trim();
  if (!trimmed.startsWith(CONNECTION_CODE_PREFIX) || trimmed.length > 8192) return null;
  try {
    const b64 = trimmed.slice(CONNECTION_CODE_PREFIX.length).replace(/-/g, "+").replace(/_/g, "/");
    const json = new TextDecoder("utf-8", { fatal: true }).decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)));
    const parsed = JSON.parse(json) as Record<string, unknown>;
    if (
      parsed.v !== CONNECTION_CODE_VERSION ||
      typeof parsed.origin !== "string" ||
      !isUsableServiceToken(parsed.serviceToken) ||
      typeof parsed.pairingToken !== "string" ||
      !PAIRING_TOKEN_RE.test(parsed.pairingToken) ||
      typeof parsed.expiresAt !== "string"
    ) {
      return null;
    }
    return parsed as unknown as ConnectionCode;
  } catch {
    return null;
  }
}

/**
 * Creates a new single-use pairing token for the owner, invalidating any earlier unused one.
 * Returns the plaintext token exactly once; only its hash is stored.
 */
export async function createPairing(db: SqlDatabase, ownerId: string, nowMs: number): Promise<{ token: string; expiresAtMs: number }> {
  const token = randomToken(PAIRING_PREFIX);
  const expiresAtMs = nowMs + PAIRING_TTL_MS;
  await db.batch([
    db.prepare(`DELETE FROM sync_pairings WHERE owner_id = ? OR expires_at_ms < ?`).bind(ownerId, nowMs),
    db
      .prepare(`INSERT INTO sync_pairings (token_hash, owner_id, created_at_ms, expires_at_ms) VALUES (?, ?, ?, ?)`)
      .bind(await sha256Hex(token), ownerId, nowMs, expiresAtMs),
  ]);
  return { token, expiresAtMs };
}

/**
 * Redeems a pairing token exactly once. Everything happens in one transaction: claiming the
 * pairing row, revoking the owner's previous device, and inserting the new device, whose owner
 * is copied from the claimed row. A concurrent second redemption claims nothing and inserts
 * nothing. Returns null when the token is unknown, expired, or already used.
 */
export async function redeemPairing(
  db: SqlDatabase,
  pairingToken: string,
  deviceName: string,
  nowMs: number,
): Promise<{ deviceId: string; deviceToken: string; name: string } | null> {
  if (!PAIRING_TOKEN_RE.test(pairingToken)) return null;
  const pairingHash = await sha256Hex(pairingToken);
  const deviceId = crypto.randomUUID();
  const deviceToken = randomToken(DEVICE_PREFIX);
  const name = sanitizeDeviceName(deviceName);
  const nowIso = new Date(nowMs).toISOString();
  const claimed = `SELECT owner_id FROM sync_pairings WHERE token_hash = ? AND device_id = ?`;
  await db.batch([
    db
      .prepare(
        `UPDATE sync_pairings SET redeemed_at_ms = ?, device_id = ?
          WHERE token_hash = ? AND redeemed_at_ms IS NULL AND expires_at_ms > ?`,
      )
      .bind(nowMs, deviceId, pairingHash, nowMs),
    db
      .prepare(
        `UPDATE sync_devices SET revoked_at = ?, revoked_reason = 'replaced'
          WHERE revoked_at IS NULL AND owner_id = (${claimed})`,
      )
      .bind(nowIso, pairingHash, deviceId),
    db
      .prepare(
        `INSERT INTO sync_devices (id, owner_id, token_hash, name, paired_at, paired_at_ms)
         SELECT ?, owner_id, ?, ?, ?, ? FROM sync_pairings WHERE token_hash = ? AND device_id = ?`,
      )
      .bind(deviceId, await sha256Hex(deviceToken), name, nowIso, nowMs, pairingHash, deviceId),
    // Bound history: keep only the most recent revoked devices for the owner.
    db
      .prepare(
        `DELETE FROM sync_devices
          WHERE revoked_at IS NOT NULL AND owner_id = (${claimed})
            AND id NOT IN (SELECT id FROM sync_devices WHERE owner_id = (${claimed})
                            ORDER BY paired_at_ms DESC LIMIT ${DEVICE_HISTORY_LIMIT})`,
      )
      .bind(pairingHash, deviceId, pairingHash, deviceId),
  ]);
  const created = await db
    .prepare(`SELECT id FROM sync_devices WHERE id = ? AND revoked_at IS NULL`)
    .bind(deviceId)
    .first<{ id: string }>();
  return created ? { deviceId, deviceToken, name } : null;
}

/** Looks up an active device by its bearer token. The owner comes only from the stored record. */
export async function authenticateDevice(db: SqlDatabase, deviceToken: string): Promise<AuthenticatedDevice | null> {
  if (!DEVICE_TOKEN_RE.test(deviceToken)) return null;
  const tokenHash = await sha256Hex(deviceToken);
  const row = await db
    .prepare(`SELECT id, owner_id, name FROM sync_devices WHERE token_hash = ? AND revoked_at IS NULL`)
    .bind(tokenHash)
    .first<{ id: string; owner_id: string; name: string }>();
  return row ? { id: row.id, ownerId: row.owner_id, tokenHash, name: row.name } : null;
}

/** SQL fragment (3 binds: id, token_hash, owner_id) that is true only while the device is active. */
export const ACTIVE_DEVICE_GUARD = `EXISTS (SELECT 1 FROM sync_devices
  WHERE id = ? AND token_hash = ? AND owner_id = ? AND revoked_at IS NULL)`;

export async function isDeviceActive(db: SqlDatabase, device: AuthenticatedDevice): Promise<boolean> {
  const row = await db
    .prepare(`SELECT 1 AS ok WHERE ${ACTIVE_DEVICE_GUARD}`)
    .bind(device.id, device.tokenHash, device.ownerId)
    .first<{ ok: number }>();
  return Boolean(row);
}

/** This active device's last successful sync time (null before the first); undefined once it is revoked. */
export async function deviceLastSyncAt(db: SqlDatabase, device: AuthenticatedDevice): Promise<string | null | undefined> {
  const row = await db
    .prepare(`SELECT last_sync_at FROM sync_devices WHERE id = ? AND token_hash = ? AND owner_id = ? AND revoked_at IS NULL`)
    .bind(device.id, device.tokenHash, device.ownerId)
    .first<{ last_sync_at: string | null }>();
  return row ? row.last_sync_at : undefined;
}

/** Records a rejected or failed upload attempt for the dashboard; no-op for revoked devices. */
export async function recordDeviceAttempt(
  db: SqlDatabase,
  device: AuthenticatedDevice,
  status: string,
  report: { trigger: SyncTrigger | null; autoSync: boolean | null },
  nowMs: number,
): Promise<void> {
  await db
    .prepare(
      `UPDATE sync_devices SET last_attempt_at = ?, last_attempt_status = ?,
         last_trigger = COALESCE(?, last_trigger), auto_sync_reported = COALESCE(?, auto_sync_reported)
        WHERE id = ? AND token_hash = ? AND owner_id = ? AND revoked_at IS NULL`,
    )
    .bind(
      new Date(nowMs).toISOString(),
      status,
      report.trigger,
      report.autoSync === null ? null : report.autoSync ? 1 : 0,
      device.id,
      device.tokenHash,
      device.ownerId,
    )
    .run();
}

export async function revokeDeviceSelf(db: SqlDatabase, device: AuthenticatedDevice, nowMs: number): Promise<void> {
  await db
    .prepare(
      `UPDATE sync_devices SET revoked_at = ?, revoked_reason = 'disconnected_on_device'
        WHERE id = ? AND token_hash = ? AND revoked_at IS NULL`,
    )
    .bind(new Date(nowMs).toISOString(), device.id, device.tokenHash)
    .run();
}

/** Owner-initiated revocation from the dashboard. Returns false for unknown or other owners' devices. */
export async function revokeDeviceByOwner(db: SqlDatabase, ownerId: string, deviceId: string, nowMs: number): Promise<boolean> {
  const exists = await db
    .prepare(`SELECT id FROM sync_devices WHERE id = ? AND owner_id = ?`)
    .bind(deviceId, ownerId)
    .first<{ id: string }>();
  if (!exists) return false;
  await db
    .prepare(
      `UPDATE sync_devices SET revoked_at = ?, revoked_reason = 'revoked_by_owner'
        WHERE id = ? AND owner_id = ? AND revoked_at IS NULL`,
    )
    .bind(new Date(nowMs).toISOString(), deviceId, ownerId)
    .run();
  return true;
}

export async function listDevices(db: SqlDatabase, ownerId: string): Promise<DeviceRecord[]> {
  const { results } = await db
    .prepare(
      `SELECT id, name, paired_at, revoked_at, revoked_reason, last_sync_at, last_attempt_at,
              last_attempt_status, last_trigger, auto_sync_reported
         FROM sync_devices WHERE owner_id = ? ORDER BY paired_at_ms DESC LIMIT ?`,
    )
    .bind(ownerId, DEVICE_HISTORY_LIMIT + 1)
    .all<DeviceRow>();
  return results.map((r) => ({
    id: r.id,
    name: r.name,
    pairedAt: r.paired_at,
    revokedAt: r.revoked_at,
    revokedReason: r.revoked_reason,
    lastSyncAt: r.last_sync_at,
    lastAttemptAt: r.last_attempt_at,
    lastAttemptStatus: r.last_attempt_status,
    lastTrigger: r.last_trigger === "scheduled" || r.last_trigger === "manual" ? r.last_trigger : null,
    autoSyncReported: r.auto_sync_reported === null ? null : Number(r.auto_sync_reported) === 1,
    active: r.revoked_at === null,
  }));
}

/** Plain-language scheduling limits, shared by the dashboard, MCP status, and docs. */
export const SCHEDULING_NOTE =
  "Automatic sync uses Android background scheduling (WorkManager). It is best effort, not real time: Android runs it at most about every 15 minutes and often later, and delays it for battery saver, Doze, app standby, or no network. It needs Android's background health-read permission, which only some devices support.";
