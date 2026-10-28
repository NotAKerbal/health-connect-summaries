// HTTP handlers for pairing and device sync. Route files under app/api/sync pass in the D1
// database and runtime configuration; tests call these handlers with real Requests and SQLite.

import { BodyTooLargeError, json, ownerIdFrom, readTextCapped, rejectCrossSite } from "./http.ts";
import { StaleImportError, getSharing, importSnapshot } from "./store.ts";
import type { SqlDatabase } from "./store.ts";
import {
  DeviceRevokedError,
  authenticateDevice,
  createPairing,
  deviceLastSyncAt,
  encodeConnectionCode,
  isUsableServiceToken,
  recordDeviceAttempt,
  redeemPairing,
  revokeDeviceByOwner,
  revokeDeviceSelf,
  type AuthenticatedDevice,
  type SyncTrigger,
  CONNECTION_CODE_VERSION,
} from "./sync.ts";
import { MAX_EXPORT_BYTES, parseAndValidateExport, sha256Hex } from "./validate.ts";

export type SyncDeps = {
  db: SqlDatabase;
  nowMs: number;
  /** The Site's platform service-access token (runtime secret HC_SITE_SERVICE_TOKEN). */
  serviceToken?: string | undefined;
};

const MAX_SMALL_BODY_BYTES = 1024;
const TRIGGER_HEADER = "x-hc-sync-trigger";
const AUTO_SYNC_HEADER = "x-hc-auto-sync";

function jsonContentType(request: Request): boolean {
  return (request.headers.get("content-type") ?? "").toLowerCase().startsWith("application/json");
}

async function readSmallJson(request: Request): Promise<Record<string, unknown> | Response> {
  let text: string;
  try {
    text = await readTextCapped(request, MAX_SMALL_BODY_BYTES);
  } catch (error) {
    if (error instanceof BodyTooLargeError) return json({ error: "Request body too large" }, 413);
    return json({ error: "Request body is not valid UTF-8" }, 400);
  }
  try {
    const parsed: unknown = text.trim() === "" ? {} : JSON.parse(text);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new Error();
    return parsed as Record<string, unknown>;
  } catch {
    return json({ error: "Expected a JSON object" }, 400);
  }
}

function bearer(request: Request): string | null {
  const match = /^Bearer ([A-Za-z0-9_-]{1,128})$/.exec(request.headers.get("authorization") ?? "");
  return match ? match[1] : null;
}

const DEVICE_REVOKED = { error: "This device is not connected. Pair it again from your private Site.", code: "device_revoked" };

/**
 * Error responses the phone must tell apart from the private-Site gate's own 401/403 also carry
 * the code in a header, because some HTTP clients drop the body of a 401 response.
 */
function coded(body: { error: string; code: string }, status: number): Response {
  return json(body, status, { "x-hc-error": body.code });
}

/**
 * Browser: POST /api/sync/pairings. Creates a one-time pairing code for the signed-in owner.
 * The response is the only place the code appears; it is never rendered server-side or logged.
 */
export async function handleCreatePairing(request: Request, deps: SyncDeps): Promise<Response> {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;
  const body = await readSmallJson(request);
  if (body instanceof Response) return body;
  if (Object.keys(body).length > 0) return json({ error: "Expected an empty JSON object" }, 400);
  if (!isUsableServiceToken(deps.serviceToken)) {
    return json({ error: "Automatic sync is not configured on this Site yet: its service access key is missing.", code: "sync_not_configured" }, 503);
  }
  const origin = new URL(request.url).origin;
  const { token, expiresAtMs } = await createPairing(deps.db, ownerId, deps.nowMs);
  const expiresAt = new Date(expiresAtMs).toISOString();
  const code = encodeConnectionCode({
    v: CONNECTION_CODE_VERSION,
    origin,
    serviceToken: deps.serviceToken,
    pairingToken: token,
    expiresAt,
  });
  return json({ code, expiresAt }, 201);
}

/**
 * Phone: POST /api/sync/pair, reached through the private Site's service-access gate (no
 * visitor identity). Body: {"pairingToken": "...", "deviceName": "..."}. The owner is taken
 * from the stored pairing, never from the request. Returns the device token exactly once.
 */
export async function handleRedeemPairing(request: Request, deps: SyncDeps): Promise<Response> {
  if (!jsonContentType(request)) return json({ error: "Expected application/json" }, 415);
  const body = await readSmallJson(request);
  if (body instanceof Response) return body;
  for (const key of Object.keys(body)) {
    if (key !== "pairingToken" && key !== "deviceName") return json({ error: `Unknown field: ${key}` }, 400);
  }
  if (typeof body.pairingToken !== "string") return json({ error: "pairingToken is required" }, 400);
  if (body.deviceName !== undefined && typeof body.deviceName !== "string") return json({ error: "deviceName must be a string" }, 400);
  const redeemed = await redeemPairing(deps.db, body.pairingToken, body.deviceName ?? "", deps.nowMs);
  if (!redeemed) {
    return coded(
      { error: "This connection code is invalid, expired, or already used. Create a new one on your private Site.", code: "pairing_invalid" },
      401,
    );
  }
  return json({ deviceId: redeemed.deviceId, deviceToken: redeemed.deviceToken, deviceName: redeemed.name }, 201);
}

function syncReport(request: Request): { trigger: SyncTrigger | null; autoSync: boolean | null } {
  const t = request.headers.get(TRIGGER_HEADER);
  const a = request.headers.get(AUTO_SYNC_HEADER);
  return {
    trigger: t === "scheduled" || t === "manual" ? t : null,
    autoSync: a === "on" ? true : a === "off" ? false : null,
  };
}

async function deviceFrom(request: Request, db: SqlDatabase): Promise<AuthenticatedDevice | null> {
  const token = bearer(request);
  return token ? authenticateDevice(db, token) : null;
}

/**
 * Phone: POST /api/sync with `Authorization: Bearer <device token>`. Body: one schemaVersion 1
 * snapshot, validated and imported exactly like a browser upload (snapshot replace, idempotent,
 * stale-protected), attributed to the device. The response carries no health values; it reports only the import
 * result and the owner's current ChatGPT access switch (read, never changed).
 */
export async function handleSyncUpload(request: Request, deps: SyncDeps): Promise<Response> {
  const device = await deviceFrom(request, deps.db);
  if (!device) return coded(DEVICE_REVOKED, 401);
  if (!jsonContentType(request)) return json({ error: "Expected application/json" }, 415);
  const report = syncReport(request);

  let text: string;
  try {
    text = await readTextCapped(request, MAX_EXPORT_BYTES);
  } catch (error) {
    await recordDeviceAttempt(deps.db, device, "rejected_too_large", report, deps.nowMs);
    if (error instanceof BodyTooLargeError) return json({ error: `Snapshots are limited to ${MAX_EXPORT_BYTES / 1024} KiB` }, 413);
    return json({ error: "The snapshot is not valid UTF-8 text" }, 400);
  }
  const result = parseAndValidateExport(text, deps.nowMs);
  if (!result.ok) {
    await recordDeviceAttempt(deps.db, device, "rejected_invalid", report, deps.nowMs);
    return json({ error: "This is not a valid Health Connect snapshot", issues: result.issues }, 422);
  }
  try {
    const outcome = await importSnapshot(deps.db, device.ownerId, result.value, {
      sha256: await sha256Hex(JSON.stringify(result.value)),
      payloadBytes: new TextEncoder().encode(text).byteLength,
      nowMs: deps.nowMs,
      deviceUpload: { device, ...report },
    });
    // The owner's ChatGPT access switch, read after the import, so the app can tell
    // "synced" apart from "ChatGPT can read it". Never changed by a device upload.
    const sharing = await getSharing(deps.db, device.ownerId);
    return json(
      {
        status: outcome.status,
        rangeStart: outcome.rangeStart,
        rangeEnd: outcome.rangeEnd,
        dayCount: outcome.dayCount,
        receivedAt: new Date(deps.nowMs).toISOString(),
        chatgptAccessEnabled: sharing.enabled,
        checkedAt: new Date(deps.nowMs).toISOString(),
      },
      outcome.status === "imported" ? 201 : 200,
    );
  } catch (error) {
    if (error instanceof DeviceRevokedError) return coded(DEVICE_REVOKED, 401);
    if (error instanceof StaleImportError) {
      await recordDeviceAttempt(deps.db, device, "rejected_stale", report, deps.nowMs);
      return coded({ error: error.message, code: "stale" }, 409);
    }
    return json({ error: "Could not save the snapshot. Nothing was changed; try again." }, 500);
  }
}

/**
 * Phone: GET /api/sync/status with the device token. Read-only connection metadata for the
 * app's status screen: whether this device is still connected, this device's last successful
 * sync, and whether the owner's ChatGPT access switch is on. It returns no health values,
 * imports, browser identity, credentials, or owner or device IDs, and writes nothing. The owner
 * is taken only from the hashed device record.
 */
export async function handleSyncStatus(request: Request, deps: SyncDeps): Promise<Response> {
  const device = await deviceFrom(request, deps.db);
  if (!device) return coded(DEVICE_REVOKED, 401);
  const [lastSyncAt, sharing] = await Promise.all([deviceLastSyncAt(deps.db, device), getSharing(deps.db, device.ownerId)]);
  // Revoked between authentication and the read: report it as revoked, not as connected.
  if (lastSyncAt === undefined) return coded(DEVICE_REVOKED, 401);
  return json({
    deviceConnected: true,
    lastSuccessfulSyncAt: lastSyncAt,
    chatgptAccessEnabled: sharing.enabled,
    checkedAt: new Date(deps.nowMs).toISOString(),
  });
}

/** Phone: DELETE /api/sync with the device token. Revokes that device only. */
export async function handleSyncSelfRevoke(request: Request, deps: SyncDeps): Promise<Response> {
  const device = await deviceFrom(request, deps.db);
  // Idempotent: an unknown or already-revoked token is already disconnected.
  if (device) await revokeDeviceSelf(deps.db, device, deps.nowMs);
  return json({ disconnected: true });
}

/** Browser: DELETE /api/sync/devices/[id]. Revokes one of the signed-in owner's devices. */
export async function handleOwnerRevokeDevice(request: Request, deviceId: string, deps: SyncDeps): Promise<Response> {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;
  if (!/^[0-9a-f-]{36}$/.test(deviceId)) return json({ error: "Not found" }, 404);
  const revoked = await revokeDeviceByOwner(deps.db, ownerId, deviceId, deps.nowMs);
  return revoked ? json({ revoked: true }) : json({ error: "Not found" }, 404);
}
