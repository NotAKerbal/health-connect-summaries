/* eslint-disable @typescript-eslint/no-explicit-any -- JSON-RPC responses are inspected loosely in tests. */
// End-to-end smoke test against a running local preview (npm run dev), using the portable
// profile's simulated sign-in. Uploads synthetic test snapshots generated in this script.
// Pairing needs a placeholder HC_SITE_SERVICE_TOKEN in the preview's ignored .dev.vars.
// Usage: node test/e2e/smoke.ts http://localhost:5199
import assert from "node:assert/strict";
import { decodeConnectionCode } from "../../lib/health/sync.ts";
import { addDays, wallClock } from "../../lib/health/time.ts";
import { buildExport } from "../helpers.ts";

const base = process.argv[2] ?? "http://localhost:5173";
const tz = "America/Chicago";
const now = Date.now();
const today = wallClock(now, tz).date;
const fixture = buildExport({
  tz,
  rangeStart: addDays(today, -6),
  rangeEnd: today,
  generatedAt: new Date(now - 60_000).toISOString(),
  categories: ["steps", "sleepMinutes", "weightKg"],
  values: {
    [addDays(today, -6)]: { steps: 0, sleepMinutes: 431, weightKg: 81.2 },
    [addDays(today, -5)]: { steps: 10234, sleepMinutes: 388 },
    [addDays(today, -3)]: { steps: 6120 },
  },
});

let cookie = "";
async function req(path: string, init: RequestInit = {}, withCookie = true) {
  const headers = new Headers(init.headers);
  if (withCookie && cookie) headers.set("cookie", cookie);
  const res = await fetch(base + path, { ...init, headers, redirect: "manual" });
  const setCookie = res.headers.getSetCookie?.() ?? [];
  if (setCookie.length) cookie = setCookie.map((c) => c.split(";")[0]).join("; ");
  return res;
}
const jsonPost = (path: string, body: unknown, method = "POST", extra: Record<string, string> = {}) =>
  req(path, { method, headers: { "content-type": "application/json", "sec-fetch-site": "same-origin", ...extra }, body: typeof body === "string" ? body : JSON.stringify(body) });
const mcp = async (method: string, params?: unknown, withCookie = true) =>
  req("/mcp", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method, params }) }, withCookie);

const results: [string, boolean][] = [];
async function step(name: string, fn: () => Promise<void>) {
  try {
    await fn();
    results.push([name, true]);
    console.log(`PASS ${name}`);
  } catch (error) {
    results.push([name, false]);
    console.log(`FAIL ${name}: ${(error as Error).message}`);
  }
}

await step("anonymous MCP request is rejected", async () => {
  assert.equal((await mcp("tools/list", undefined, false)).status, 401);
});
await step("forged identity header is ignored without sign-in", async () => {
  const res = await req("/api/imports", { headers: { "oai-authenticated-user-id": "someone-else" } }, false);
  assert.equal(res.status, 401);
});
await step("sign in (local simulated)", async () => {
  const res = await req("/signin-with-chatgpt?return_to=/");
  assert.ok(res.status >= 300 && res.status < 400, `status ${res.status}`);
  assert.ok(cookie.length > 0, "no session cookie");
});
await step("start clean", async () => {
  assert.equal((await jsonPost("/api/data", { confirm: "DELETE_ALL" }, "DELETE")).status, 200);
});
await step("dashboard renders empty state", async () => {
  const html = await (await req("/")).text();
  assert.match(html, /Not set up/);
  assert.match(html, /id="chatgpt-access"/, "the app's access link target is always rendered");
  assert.match(html, /separate from ChatGPT Health/);
  assert.match(html, /Set up automatic sync/);
  assert.doesNotMatch(html, /HCS1\./, "no connection code is server-rendered");
});
await step("cross-site upload is rejected", async () => {
  assert.equal((await jsonPost("/api/imports", fixture, "POST", { "sec-fetch-site": "cross-site" })).status, 403);
});
await step("invalid export is rejected with issues", async () => {
  const res = await jsonPost("/api/imports", { ...fixture, schemaVersion: 9 });
  assert.equal(res.status, 422);
  const body = (await res.json()) as { issues: string[] };
  assert.ok(body.issues.some((i) => i.startsWith("schemaVersion")));
});
await step("valid export imports, duplicate is idempotent", async () => {
  const first = await jsonPost("/api/imports", fixture);
  assert.equal(first.status, 201, await first.clone().text());
  const second = await jsonPost("/api/imports", JSON.stringify(fixture, null, 2));
  assert.equal(second.status, 200);
  assert.equal(((await second.json()) as { status: string }).status, "duplicate");
});
await step("dashboard shows imported data and gaps", async () => {
  const html = await (await req("/")).text();
  assert.match(html, /10,234 steps/);
  assert.match(html, /not exported/);
  assert.match(html, /Fresh/);
});
await step("MCP discover/list", async () => {
  const d = (await (await mcp("server/discover")).json()) as any;
  assert.deepEqual(d.result.supportedVersions, ["2026-07-28"]);
  const l = (await (await mcp("tools/list")).json()) as any;
  assert.equal(l.result.tools.length, 3);
});
await step("MCP data tools blocked until access is on", async () => {
  const r = (await (await mcp("tools/call", { name: "get_daily_health_metrics", arguments: {} })).json()) as any;
  assert.equal(r.result.isError, true);
});
await step("enable access; MCP returns daily values with gaps", async () => {
  assert.equal((await jsonPost("/api/sharing", { enabled: true })).status, 200);
  const r = (await (await mcp("tools/call", { name: "get_daily_health_metrics", arguments: {} })).json()) as any;
  const days = r.result.structuredContent.days;
  assert.equal(days.length, 7);
  assert.deepEqual(days[0].metrics.steps, { status: "value", value: 0 });
  assert.equal(days[2].metrics.steps.status, "no_data");
  assert.equal(days[0].metrics.heartRateAvgBpm.status, "not_exported");
  assert.equal(days[6].partialDay, true);
  const s = (await (await mcp("tools/call", { name: "get_health_metric_summary", arguments: { metrics: ["steps"] } })).json()) as any;
  assert.equal(s.result.structuredContent.summaries[0].daysWithData, 3);
});
const deviceSnapshot = buildExport({
  tz,
  rangeStart: addDays(today, -2),
  rangeEnd: today,
  generatedAt: new Date(now - 30_000).toISOString(),
  categories: ["steps"],
  values: { [addDays(today, -2)]: { steps: 4321 } },
});
let device = { deviceId: "", deviceToken: "" };
const phone = (path: string, body: unknown, method = "POST", token = device.deviceToken) =>
  req(path, {
    method,
    headers: { "content-type": "application/json", authorization: `Bearer ${token}`, "x-hc-sync-trigger": "scheduled", "x-hc-auto-sync": "on" },
    body: body === undefined ? undefined : JSON.stringify(body),
  }, false);
await step("pairing code requires sign-in and same-site JSON", async () => {
  assert.equal((await req("/api/sync/pairings", { method: "POST", headers: { "content-type": "application/json" }, body: "{}" }, false)).status, 401);
  assert.equal((await jsonPost("/api/sync/pairings", {}, "POST", { "sec-fetch-site": "cross-site" })).status, 403);
});
await step("pair a device once; replay rejected", async () => {
  const res = await jsonPost("/api/sync/pairings", {});
  assert.equal(res.status, 201, await res.clone().text());
  assert.match(res.headers.get("cache-control") ?? "", /no-store/);
  const code = decodeConnectionCode(((await res.json()) as { code: string }).code);
  assert.ok(code, "decodable code");
  assert.equal(code.origin, new URL(base).origin);
  const redeem = () => req("/api/sync/pair", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ pairingToken: code.pairingToken, deviceName: "Smoke phone" }) }, false);
  const first = await redeem();
  assert.equal(first.status, 201);
  device = (await first.json()) as typeof device;
  assert.equal((await redeem()).status, 401);
});
await step("device uploads a snapshot without a browser identity", async () => {
  const res = await phone("/api/sync", deviceSnapshot);
  assert.equal(res.status, 201, await res.clone().text());
  assert.equal(((await phone("/api/sync", deviceSnapshot)).status), 200, "duplicate is idempotent");
  const older = { ...fixture, generatedAt: new Date(now - 120_000).toISOString() };
  assert.equal((await phone("/api/sync", older)).status, 409, "older snapshot is stale");
});
await step("device reads only its connection status", async () => {
  const res = await phone("/api/sync/status", undefined, "GET");
  assert.equal(res.status, 200, await res.clone().text());
  assert.match(res.headers.get("cache-control") ?? "", /no-store/);
  const body = (await res.json()) as Record<string, unknown>;
  assert.deepEqual(Object.keys(body).sort(), ["chatgptAccessEnabled", "checkedAt", "deviceConnected", "lastSuccessfulSyncAt"]);
  assert.equal(body.deviceConnected, true);
  assert.equal(typeof body.lastSuccessfulSyncAt, "string");
  assert.equal(typeof body.chatgptAccessEnabled, "boolean");
  assert.ok(!JSON.stringify(body).includes("4321"), "no health values");
  assert.equal((await phone("/api/sync/status", undefined, "GET", "hcd_" + "Z".repeat(43))).status, 401);
});
await step("device token cannot read or use browser routes", async () => {
  assert.equal((await phone("/api/imports", undefined, "GET")).status, 401);
  assert.equal((await phone("/api/sync/pairings", {})).status, 401);
  const m = await req("/mcp", { method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${device.deviceToken}` }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/list" }) }, false);
  assert.equal(m.status, 401);
});
await step("dashboard and MCP report automatic sync and provenance", async () => {
  const html = await (await req("/")).text();
  assert.match(html, /Automatic sync on/);
  assert.match(html, /Smoke phone/);
  assert.match(html, /Phone sync/);
  const st = (await (await mcp("tools/call", { name: "get_health_connection_status", arguments: {} })).json()) as any;
  assert.equal(st.result.structuredContent.sync.mode, "automatic");
  assert.equal(st.result.structuredContent.latestExport.uploadMethod, "device_sync");
  assert.ok(!JSON.stringify(st).includes(device.deviceToken));
});
await step("owner revokes device; uploads stop", async () => {
  assert.equal((await jsonPost(`/api/sync/devices/${device.deviceId}`, {}, "DELETE")).status, 200);
  const later = { ...deviceSnapshot, generatedAt: new Date(Date.now() - 1000).toISOString() };
  const res = await phone("/api/sync", later);
  assert.equal(res.status, 401);
  assert.equal(((await res.json()) as { code: string }).code, "device_revoked");
  const st = await phone("/api/sync/status", undefined, "GET");
  assert.equal(st.status, 401);
  assert.equal(st.headers.get("x-hc-error"), "device_revoked");
});
await step("delete all data", async () => {
  assert.equal((await jsonPost("/api/data", { confirm: "DELETE_ALL" }, "DELETE")).status, 200);
  const st = (await (await mcp("tools/call", { name: "get_health_connection_status", arguments: {} })).json()) as any;
  assert.equal(st.result.structuredContent.hasData, false);
  assert.equal(st.result.structuredContent.chatgptAccessEnabled, false);
  assert.equal(st.result.structuredContent.sync.mode, "not_paired");
});

const failed = results.filter(([, ok]) => !ok).length;
console.log(`${results.length - failed}/${results.length} smoke steps passed`);
process.exit(failed ? 1 : 0);
