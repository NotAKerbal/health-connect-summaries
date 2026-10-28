/* eslint-disable @typescript-eslint/no-explicit-any -- JSON responses are inspected loosely in tests. */
// Pairing and device sync: real SQLite running the migrations, and the actual HTTP handlers
// invoked with real Request objects (the route files are thin wrappers around these).
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { handleMcpMessage } from "../lib/health/mcp.ts";
import { loadConnectionStatus, loadDailyRows } from "../lib/health/query.ts";
import { deleteAllOwnerData, getSharing, importSnapshot, listImports, setSharing, type SqlDatabase } from "../lib/health/store.ts";
import {
  PAIRING_TTL_MS,
  authenticateDevice,
  createPairing,
  decodeConnectionCode,
  redeemPairing,
  revokeDeviceByOwner,
  sanitizeDeviceName,
} from "../lib/health/sync.ts";
import {
  handleCreatePairing,
  handleOwnerRevokeDevice,
  handleRedeemPairing,
  handleSyncSelfRevoke,
  handleSyncStatus,
  handleSyncUpload,
  type SyncDeps,
} from "../lib/health/sync-api.ts";
import { sha256Hex, validateExport, type ValidExport } from "../lib/health/validate.ts";
import { buildExport, createTestDb } from "./helpers.ts";

const NOW = Date.parse("2026-10-27T20:00:00Z");
const ORIGIN = "https://health.example.test";
const SERVICE = "svc-test-token-not-a-real-credential";

const deps = (db: SqlDatabase, nowMs = NOW, serviceToken: string | undefined = SERVICE): SyncDeps => ({ db, nowMs, serviceToken });

function browser(path: string, owner: string | null, init: RequestInit & { site?: string } = {}) {
  const headers = new Headers(init.headers);
  if (owner) headers.set("oai-authenticated-user-id", owner);
  headers.set("content-type", "application/json");
  headers.set("sec-fetch-site", init.site ?? "same-origin");
  return new Request(ORIGIN + path, { method: "POST", body: "{}", ...init, headers });
}

function phone(path: string, body: unknown, opts: { token?: string; method?: string; headers?: Record<string, string> } = {}) {
  const headers = new Headers({ "content-type": "application/json", ...opts.headers });
  if (opts.token) headers.set("authorization", `Bearer ${opts.token}`);
  return new Request(ORIGIN + path, {
    method: opts.method ?? "POST",
    headers,
    body: opts.method === "DELETE" ? undefined : typeof body === "string" ? body : JSON.stringify(body),
  });
}

async function pairVia(db: SqlDatabase, owner: string, name = "Pixel 9", nowMs = NOW) {
  const created = await handleCreatePairing(browser("/api/sync/pairings", owner), deps(db, nowMs));
  assert.equal(created.status, 201);
  const { code } = (await created.json()) as { code: string };
  const decoded = decodeConnectionCode(code)!;
  const redeemed = await handleRedeemPairing(phone("/api/sync/pair", { pairingToken: decoded.pairingToken, deviceName: name }), deps(db, nowMs));
  assert.equal(redeemed.status, 201);
  return (await redeemed.json()) as { deviceId: string; deviceToken: string; deviceName: string };
}

const snapshot = (generatedAt: string, steps: number, extra: Partial<Parameters<typeof buildExport>[0]> = {}) =>
  buildExport({
    rangeStart: "2026-10-25",
    rangeEnd: "2026-10-27",
    generatedAt,
    categories: ["steps"],
    values: { "2026-10-25": { steps } },
    ...extra,
  });

const upload = (db: SqlDatabase, token: string, body: unknown, nowMs = NOW, headers: Record<string, string> = {}) =>
  handleSyncUpload(phone("/api/sync", body, { token, headers }), deps(db, nowMs));

function valid(e: Record<string, unknown>): ValidExport {
  const r = validateExport(e, NOW);
  assert.ok(r.ok, JSON.stringify(!r.ok && r.issues));
  return (r as { ok: true; value: ValidExport }).value;
}

describe("pairing codes", () => {
  it("requires a signed-in same-site JSON request and stores only a hash", async () => {
    const db = createTestDb();
    assert.equal((await handleCreatePairing(browser("/api/sync/pairings", null), deps(db))).status, 401);
    assert.equal((await handleCreatePairing(browser("/api/sync/pairings", "alice", { site: "cross-site" }), deps(db))).status, 403);
    assert.equal((await handleCreatePairing(browser("/api/sync/pairings", "alice", { body: "x".repeat(2000) }), deps(db))).status, 413);
    assert.equal((await handleCreatePairing(browser("/api/sync/pairings", "alice", { body: '{"ownerId":"bob"}' }), deps(db))).status, 400);

    const res = await handleCreatePairing(browser("/api/sync/pairings", "alice"), deps(db));
    assert.equal(res.status, 201);
    assert.match(res.headers.get("cache-control") ?? "", /no-store/);
    const body = (await res.json()) as { code: string; expiresAt: string };
    const decoded = decodeConnectionCode(body.code)!;
    assert.equal(decoded.v, 1);
    assert.equal(decoded.origin, ORIGIN);
    assert.equal(decoded.serviceToken, SERVICE);
    assert.equal(Date.parse(decoded.expiresAt), NOW + PAIRING_TTL_MS);
    const rows = db.raw.prepare("SELECT * FROM sync_pairings").all() as Record<string, unknown>[];
    assert.equal(rows.length, 1);
    assert.equal(rows[0].owner_id, "alice");
    assert.equal(rows[0].token_hash, await sha256Hex(decoded.pairingToken));
    assert.ok(!JSON.stringify(rows).includes(decoded.pairingToken), "plaintext pairing token is not stored");
  });

  it("refuses to issue a code when the service access key is not configured", async () => {
    const db = createTestDb();
    const res = await handleCreatePairing(browser("/api/sync/pairings", "alice"), { db, nowMs: NOW, serviceToken: undefined });
    assert.equal(res.status, 503);
    assert.equal(((await res.json()) as any).code, "sync_not_configured");
    assert.equal((db.raw.prepare("SELECT COUNT(*) AS n FROM sync_pairings").get() as any).n, 0);
  });

  it("is single use: a replayed code fails and the first device keeps working", async () => {
    const db = createTestDb();
    const { token } = await createPairing(db, "alice", NOW);
    const first = await redeemPairing(db, token, "Pixel", NOW + 1000);
    assert.ok(first);
    assert.equal(await redeemPairing(db, token, "Attacker", NOW + 2000), null);
    const replay = await handleRedeemPairing(phone("/api/sync/pair", { pairingToken: token }), deps(db, NOW + 3000));
    assert.equal(replay.status, 401);
    assert.equal(((await replay.json()) as any).code, "pairing_invalid");
    assert.ok(await authenticateDevice(db, first.deviceToken));
  });

  it("expires after the TTL", async () => {
    const db = createTestDb();
    const { token } = await createPairing(db, "alice", NOW);
    assert.equal(await redeemPairing(db, token, "Pixel", NOW + PAIRING_TTL_MS), null);
    assert.equal((db.raw.prepare("SELECT COUNT(*) AS n FROM sync_devices").get() as any).n, 0);
  });

  it("invalidates an earlier unused code when a new one is created", async () => {
    const db = createTestDb();
    const older = await createPairing(db, "alice", NOW);
    const newer = await createPairing(db, "alice", NOW + 1000);
    assert.equal(await redeemPairing(db, older.token, "Pixel", NOW + 2000), null);
    assert.ok(await redeemPairing(db, newer.token, "Pixel", NOW + 2000));
  });

  it("creates exactly one device when two redemptions race", async () => {
    const db = createTestDb();
    const { token } = await createPairing(db, "alice", NOW);
    let raced: Awaited<ReturnType<typeof redeemPairing>> = null;
    const racingDb: SqlDatabase = {
      prepare: (sql) => db.prepare(sql),
      async batch(statements) {
        // The competing redemption commits between this request's start and its transaction.
        if (!raced) raced = await redeemPairing(db, token, "Racer", NOW + 500);
        return db.batch(statements);
      },
    };
    const mine = await redeemPairing(racingDb, token, "Mine", NOW + 600);
    assert.ok(raced, "the first redemption succeeds");
    assert.equal(mine, null, "the second redemption claims nothing");
    const devices = db.raw.prepare("SELECT * FROM sync_devices").all() as any[];
    assert.equal(devices.length, 1);
    assert.equal(devices[0].name, "Racer");
  });

  it("rejects malformed redeem bodies and does not accept an owner from the phone", async () => {
    const db = createTestDb();
    const { token } = await createPairing(db, "alice", NOW);
    const bad = [
      phone("/api/sync/pair", { pairingToken: token, ownerId: "bob" }),
      phone("/api/sync/pair", { deviceName: "x" }),
      phone("/api/sync/pair", "not json"),
      phone("/api/sync/pair", { pairingToken: token }, { headers: { "content-type": "text/plain" } }),
      phone("/api/sync/pair", { pairingToken: token, deviceName: "x".repeat(2000) }),
    ];
    for (const req of bad) assert.ok((await handleRedeemPairing(req, deps(db))).status >= 400);
    // A forged identity header is irrelevant: the owner comes from the pairing row.
    const forged = phone("/api/sync/pair", { pairingToken: token, deviceName: "Pixel" }, { headers: { "oai-authenticated-user-id": "bob" } });
    const res = await handleRedeemPairing(forged, deps(db));
    assert.equal(res.status, 201);
    const device = await authenticateDevice(db, ((await res.json()) as any).deviceToken);
    assert.equal(device?.ownerId, "alice");
  });

  it("sanitizes device names", () => {
    assert.equal(sanitizeDeviceName("Pixel\u0000 9\n Pro"), "Pixel 9 Pro");
    assert.equal(sanitizeDeviceName(""), "Android device");
    assert.equal(sanitizeDeviceName("x".repeat(100)).length, 64);
  });
});

describe("device sync uploads", () => {
  it("imports snapshots for the paired owner only, attributed to the device", async () => {
    const db = createTestDb();
    const alice = await pairVia(db, "alice");
    await pairVia(db, "bob");
    // The body cannot select an owner (unknown fields are rejected) and identity headers are ignored.
    const res = await upload(db, alice.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 4321), NOW, {
      "oai-authenticated-user-id": "bob",
      "x-hc-sync-trigger": "scheduled",
      "x-hc-auto-sync": "on",
    });
    assert.equal(res.status, 201, await res.clone().text());
    assert.equal((await loadDailyRows(db, "alice", "2026-10-25", "2026-10-25"))[0].metrics.steps?.value, 4321);
    assert.equal((await loadDailyRows(db, "bob", "2026-10-25", "2026-10-25"))[0].covered, false);
    const withOwner = { ...snapshot("2026-10-27T13:05:00-05:00", 1), ownerId: "bob" };
    assert.equal((await upload(db, alice.deviceToken, withOwner)).status, 422);

    const [imp] = await listImports(db, "alice");
    assert.equal(imp.uploadMethod, "device_sync");
    const status = await loadConnectionStatus(db, "alice", NOW);
    assert.equal(status.sync.mode, "automatic");
    assert.equal(status.sync.activeDevice?.name, "Pixel 9");
    assert.equal(status.sync.activeDevice?.lastTrigger, "scheduled");
    assert.equal(status.sync.lastSuccessfulSyncAt, new Date(NOW).toISOString());
    assert.equal(status.latestImport?.uploadMethod, "device_sync");
  });

  it("never enables ChatGPT access", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const res = await handleSyncUpload(
      new Request(`${ORIGIN}/api/sync?allowChatGPT=1`, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: `Bearer ${d.deviceToken}` },
        body: JSON.stringify(snapshot("2026-10-27T13:00:00-05:00", 1)),
      }),
      deps(db),
    );
    assert.equal(res.status, 201);
    assert.equal((await getSharing(db, "alice")).enabled, false);
  });

  it("is idempotent and rejects stale snapshots", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const body = snapshot("2026-10-27T13:00:00-05:00", 10);
    assert.equal((await upload(db, d.deviceToken, body)).status, 201);
    const dup = await upload(db, d.deviceToken, JSON.stringify(body, null, 2));
    assert.equal(dup.status, 200);
    assert.equal(((await dup.json()) as any).status, "duplicate");
    const stale = await upload(db, d.deviceToken, snapshot("2026-10-27T12:00:00-05:00", 99));
    assert.equal(stale.status, 409);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-25", "2026-10-25"))[0].metrics.steps?.value, 10);
    assert.equal((await listImports(db, "alice")).length, 1);
    const status = await loadConnectionStatus(db, "alice", NOW);
    assert.equal(status.sync.activeDevice?.lastAttemptStatus, "rejected_stale");
  });

  it("rejects missing, malformed, or unknown device tokens and oversized bodies", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const body = snapshot("2026-10-27T13:00:00-05:00", 1);
    assert.equal((await handleSyncUpload(phone("/api/sync", body), deps(db))).status, 401);
    assert.equal((await upload(db, "hcd_" + "A".repeat(43), body)).status, 401);
    assert.equal((await upload(db, d.deviceToken.slice(0, -1), body)).status, 401);
    const huge = await upload(db, d.deviceToken, "x".repeat(70 * 1024));
    assert.equal(huge.status, 413);
    assert.equal((await listImports(db, "alice")).length, 0);
  });

  it("a device token cannot read data or reach browser or MCP operations", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    // Browser routes and MCP authenticate only by the Sites identity header, which a phone does not have.
    const asBrowser = phone("/api/sync/pairings", {}, { token: d.deviceToken });
    assert.equal((await handleCreatePairing(asBrowser, deps(db))).status, 401);
    const revokeOther = new Request(`${ORIGIN}/api/sync/devices/${d.deviceId}`, {
      method: "DELETE",
      headers: { authorization: `Bearer ${d.deviceToken}`, "content-type": "application/json" },
      body: "{}",
    });
    assert.equal((await handleOwnerRevokeDevice(revokeOther, d.deviceId, deps(db))).status, 401);
    // The sync response itself contains no stored health values.
    const res = (await (await upload(db, d.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 777))).json()) as any;
    assert.deepEqual(Object.keys(res).sort(), [
      "chatgptAccessEnabled",
      "checkedAt",
      "dayCount",
      "rangeEnd",
      "rangeStart",
      "receivedAt",
      "status",
    ]);
    assert.equal(res.chatgptAccessEnabled, false);
    assert.equal(res.checkedAt, new Date(NOW).toISOString());
    assert.ok(!JSON.stringify(res).includes("777"));
  });

  it("upload responses report the owner's current ChatGPT access without changing it", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    await setSharing(db, "alice", true, NOW - 1000);
    await setSharing(db, "bob", false, NOW - 1000);
    const res = await upload(db, d.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 5));
    assert.equal(res.status, 201);
    assert.equal(((await res.json()) as any).chatgptAccessEnabled, true);
    const dup = await upload(db, d.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 5));
    assert.equal(dup.status, 200);
    assert.equal(((await dup.json()) as any).chatgptAccessEnabled, true);
    assert.equal((await getSharing(db, "alice")).updatedAt, new Date(NOW - 1000).toISOString());
  });

  it("re-pairing replaces the previous device", async () => {
    const db = createTestDb();
    const oldDevice = await pairVia(db, "alice", "Old phone");
    const newDevice = await pairVia(db, "alice", "New phone");
    const rejected = await upload(db, oldDevice.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 1));
    assert.equal(rejected.status, 401);
    assert.equal(((await rejected.json()) as any).code, "device_revoked");
    assert.equal(rejected.headers.get("x-hc-error"), "device_revoked", "code also in a header for clients that drop 401 bodies");
    assert.equal((await upload(db, newDevice.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 1))).status, 201);
    const status = await loadConnectionStatus(db, "alice", NOW);
    assert.equal(status.sync.activeDevice?.name, "New phone");
    assert.equal(status.sync.recentDevices.find((x) => x.name === "Old phone")?.revokedReason, "replaced");
  });

  it("owner revocation stops uploads, and is scoped to the owner", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const req = (owner: string) =>
      new Request(`${ORIGIN}/api/sync/devices/${d.deviceId}`, {
        method: "DELETE",
        headers: { "oai-authenticated-user-id": owner, "content-type": "application/json", "sec-fetch-site": "same-origin" },
        body: "{}",
      });
    assert.equal((await handleOwnerRevokeDevice(req("bob"), d.deviceId, deps(db))).status, 404);
    assert.equal((await upload(db, d.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 1))).status, 201);
    assert.equal((await handleOwnerRevokeDevice(req("alice"), d.deviceId, deps(db))).status, 200);
    assert.equal((await upload(db, d.deviceToken, snapshot("2026-10-27T13:10:00-05:00", 2))).status, 401);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-25", "2026-10-25"))[0].metrics.steps?.value, 1);
    assert.equal((await loadConnectionStatus(db, "alice", NOW)).sync.mode, "not_paired");
  });

  it("a revocation that commits after authentication still blocks the write", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const device = (await authenticateDevice(db, d.deviceToken))!;
    const data = valid(snapshot("2026-10-27T13:00:00-05:00", 5));
    const racingDb: SqlDatabase = {
      prepare: (sql) => db.prepare(sql),
      async batch(statements) {
        await revokeDeviceByOwner(db, "alice", d.deviceId, NOW);
        return db.batch(statements);
      },
    };
    await assert.rejects(
      importSnapshot(racingDb, "alice", data, {
        sha256: await sha256Hex(JSON.stringify(data)),
        payloadBytes: 100,
        nowMs: NOW,
        deviceUpload: { device, trigger: "scheduled", autoSync: true },
      }),
      { name: "DeviceRevokedError" },
    );
    assert.equal((await listImports(db, "alice")).length, 0);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-25", "2026-10-25"))[0].covered, false);
  });

  it("delete all disconnects devices and pairings atomically, including a racing upload", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    await createPairing(db, "alice", NOW); // a pending unused code
    const device = (await authenticateDevice(db, d.deviceToken))!;
    const data = valid(snapshot("2026-10-27T13:00:00-05:00", 5));
    const racingDb: SqlDatabase = {
      prepare: (sql) => db.prepare(sql),
      async batch(statements) {
        await deleteAllOwnerData(db, "alice");
        return db.batch(statements);
      },
    };
    await assert.rejects(
      importSnapshot(racingDb, "alice", data, {
        sha256: await sha256Hex(JSON.stringify(data)),
        payloadBytes: 100,
        nowMs: NOW,
        deviceUpload: { device, trigger: "scheduled", autoSync: true },
      }),
      { name: "DeviceRevokedError" },
    );
    assert.equal((db.raw.prepare("SELECT COUNT(*) AS n FROM sync_devices WHERE owner_id = 'alice'").get() as any).n, 0);
    assert.equal((db.raw.prepare("SELECT COUNT(*) AS n FROM sync_pairings WHERE owner_id = 'alice'").get() as any).n, 0);
    assert.equal((await listImports(db, "alice")).length, 0);
    // Later scheduled jobs from the phone cannot repopulate the data.
    assert.equal((await upload(db, d.deviceToken, snapshot("2026-10-27T13:30:00-05:00", 6))).status, 401);
    assert.equal((await listImports(db, "alice")).length, 0);
  });

  it("a duplicate upload from a revoked device reports revocation", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const body = snapshot("2026-10-27T13:00:00-05:00", 5);
    assert.equal((await upload(db, d.deviceToken, body)).status, 201);
    const device = (await authenticateDevice(db, d.deviceToken))!;
    await revokeDeviceByOwner(db, "alice", d.deviceId, NOW);
    const data = valid(body);
    await assert.rejects(
      importSnapshot(db, "alice", data, {
        sha256: await sha256Hex(JSON.stringify(data)), payloadBytes: 1, nowMs: NOW, deviceUpload: { device, trigger: null, autoSync: null },
      }),
      { name: "DeviceRevokedError" },
    );
  });

  it("the phone can disconnect itself, idempotently", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    const del = () => handleSyncSelfRevoke(phone("/api/sync", null, { token: d.deviceToken, method: "DELETE" }), deps(db));
    assert.equal((await del()).status, 200);
    assert.equal((await del()).status, 200);
    assert.equal(await authenticateDevice(db, d.deviceToken), null);
    assert.equal((await loadConnectionStatus(db, "alice", NOW)).sync.recentDevices[0].revokedReason, "disconnected_on_device");
  });

  it("mixed manual and device snapshots keep per-import provenance in MCP results", async () => {
    const db = createTestDb();
    const manual = valid(buildExport({ rangeStart: "2026-10-20", rangeEnd: "2026-10-22", generatedAt: "2026-10-22T20:00:00-05:00", categories: ["steps"], values: { "2026-10-20": { steps: 1 } } }));
    await importSnapshot(db, "alice", manual, { sha256: await sha256Hex(JSON.stringify(manual)), payloadBytes: 1, nowMs: NOW, enableSharing: true });
    const d = await pairVia(db, "alice");
    assert.equal((await upload(db, d.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 2), NOW, { "x-hc-auto-sync": "off", "x-hc-sync-trigger": "manual" })).status, 201);
    const ctx = { db, ownerId: "alice", nowMs: NOW, siteOrigin: ORIGIN };
    const daily = (await handleMcpMessage(
      { jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "get_daily_health_metrics", arguments: { startDate: "2026-10-20", endDate: "2026-10-27" } } },
      ctx,
    )) as any;
    const methods = daily.result.structuredContent.exportsUsed.map((e: any) => e.uploadMethod).sort();
    assert.deepEqual(methods, ["browser_upload", "device_sync"]);
    assert.equal(daily.result.structuredContent.days[0].uploadMethod, "browser_upload");
    assert.equal(daily.result.structuredContent.days[7].uploadMethod, "device_sync");
    const status = (await handleMcpMessage(
      { jsonrpc: "2.0", id: 2, method: "tools/call", params: { name: "get_health_connection_status", arguments: {} } },
      ctx,
    )) as any;
    const sync = status.result.structuredContent.sync;
    assert.equal(sync.mode, "paired_manual");
    assert.equal(sync.device.name, "Pixel 9");
    assert.equal(sync.device.automaticSyncReportedOn, false);
    assert.match(sync.scheduling, /best effort/);
    assert.ok(!JSON.stringify(status).includes(d.deviceToken), "no credential in tool output");
    assert.ok(!JSON.stringify(status).includes(d.deviceId), "no device id in tool output");
    assert.doesNotMatch(JSON.stringify(status), /no background sync|uploaded by hand|not a live sync/i);
  });
});

describe("device connection status (GET /api/sync/status)", () => {
  const status = (db: SqlDatabase, token: string | undefined, headers: Record<string, string> = {}, nowMs = NOW) => {
    const h = new Headers(headers);
    if (token) h.set("authorization", `Bearer ${token}`);
    return handleSyncStatus(new Request(`${ORIGIN}/api/sync/status`, { method: "GET", headers: h }), deps(db, nowMs));
  };
  const KEYS = ["chatgptAccessEnabled", "checkedAt", "deviceConnected", "lastSuccessfulSyncAt"];

  it("returns only connection metadata for the device's own owner", async () => {
    const db = createTestDb();
    const alice = await pairVia(db, "alice", "Pixel 10 Pro");
    const bob = await pairVia(db, "bob");
    await setSharing(db, "bob", true, NOW);

    // Before the first sync: connected, no sync yet, access off.
    let res = await status(db, alice.deviceToken);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("cache-control"), "no-store");
    let body = (await res.json()) as any;
    assert.deepEqual(Object.keys(body).sort(), KEYS);
    assert.deepEqual(body, { deviceConnected: true, lastSuccessfulSyncAt: null, chatgptAccessEnabled: false, checkedAt: new Date(NOW).toISOString() });

    // After a sync: this device's last sync time; still no health values, IDs, names, or credentials.
    const syncedAt = NOW - 60_000;
    assert.equal((await upload(db, alice.deviceToken, snapshot("2026-10-27T13:00:00-05:00", 9876), syncedAt)).status, 201);
    res = await status(db, alice.deviceToken, { "oai-authenticated-user-id": "bob", "oai-authenticated-user-email": "bob@example.test" });
    body = (await res.json()) as any;
    assert.deepEqual(Object.keys(body).sort(), KEYS);
    assert.equal(body.lastSuccessfulSyncAt, new Date(syncedAt).toISOString());
    // A forged identity header cannot select Bob's settings: Alice's access is still off.
    assert.equal(body.chatgptAccessEnabled, false);
    const text = JSON.stringify(body);
    for (const leak of ["9876", "steps", alice.deviceId, alice.deviceToken, "alice", "bob", "Pixel", SERVICE]) {
      assert.ok(!text.includes(leak), `status must not include ${leak}`);
    }

    // Bob's own device sees Bob's switch, and Alice's sync time does not leak to him.
    body = (await (await status(db, bob.deviceToken)).json()) as any;
    assert.equal(body.chatgptAccessEnabled, true);
    assert.equal(body.lastSuccessfulSyncAt, null);

    // Turning access on is reflected on the next check; the check itself changes nothing.
    await setSharing(db, "alice", true, NOW);
    const before = await loadConnectionStatus(db, "alice", NOW);
    body = (await (await status(db, alice.deviceToken, {}, NOW + 5000)).json()) as any;
    assert.equal(body.chatgptAccessEnabled, true);
    assert.equal(body.checkedAt, new Date(NOW + 5000).toISOString());
    assert.deepEqual(await loadConnectionStatus(db, "alice", NOW), before);
  });

  it("refuses missing, unknown, malformed, and revoked tokens with a coded 401", async () => {
    const db = createTestDb();
    const d = await pairVia(db, "alice");
    for (const token of [undefined, "hcd_" + "A".repeat(43), d.deviceToken.slice(0, -1), "not a token"]) {
      const res = await status(db, token, { "oai-authenticated-user-id": "alice" });
      assert.equal(res.status, 401);
      assert.equal(res.headers.get("x-hc-error"), "device_revoked");
      assert.equal(((await res.json()) as any).code, "device_revoked");
    }
    // Revoked by the owner, by re-pairing, and by delete-all.
    const req = new Request(`${ORIGIN}/api/sync/devices/${d.deviceId}`, {
      method: "DELETE",
      headers: { "oai-authenticated-user-id": "alice", "content-type": "application/json", "sec-fetch-site": "same-origin" },
      body: "{}",
    });
    assert.equal((await handleOwnerRevokeDevice(req, d.deviceId, deps(db))).status, 200);
    assert.equal((await status(db, d.deviceToken)).headers.get("x-hc-error"), "device_revoked");
    const second = await pairVia(db, "alice");
    const third = await pairVia(db, "alice");
    assert.equal((await status(db, second.deviceToken)).status, 401);
    assert.equal((await status(db, third.deviceToken)).status, 200);
    await deleteAllOwnerData(db, "alice");
    assert.equal((await status(db, third.deviceToken)).status, 401);
  });

  it("a pairing token or the service key alone cannot read status", async () => {
    const db = createTestDb();
    const created = await handleCreatePairing(browser("/api/sync/pairings", "alice"), deps(db));
    const pairingToken = decodeConnectionCode(((await created.json()) as any).code)!.pairingToken;
    assert.equal((await status(db, pairingToken)).status, 401);
    assert.equal((await status(db, undefined, { "oai-sites-authorization": `Bearer ${SERVICE}` })).status, 401);
  });
});
