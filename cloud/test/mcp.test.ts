/* eslint-disable @typescript-eslint/no-explicit-any -- JSON-RPC responses are inspected loosely in tests. */
import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { handleMcpMessage, TOOLS, type McpContext } from "../lib/health/mcp.ts";
import { importSnapshot, setSharing } from "../lib/health/store.ts";
import { sha256Hex, validateExport, type ValidExport } from "../lib/health/validate.ts";
import { buildExport, createTestDb } from "./helpers.ts";

const NOW = Date.parse("2026-10-27T20:00:00Z");

async function seed() {
  const db = createTestDb();
  const r = validateExport(
    buildExport({
      rangeStart: "2026-10-21",
      rangeEnd: "2026-10-27",
      generatedAt: "2026-10-27T14:00:00-05:00",
      categories: ["steps", "weightKg"],
      values: { "2026-10-21": { steps: 0, weightKg: 80.4 }, "2026-10-22": { steps: 7000 } },
    }),
    NOW,
  );
  assert.ok(r.ok);
  const v = (r as { ok: true; value: ValidExport }).value;
  await importSnapshot(db, "alice", v, { sha256: await sha256Hex(JSON.stringify(v)), payloadBytes: 500, nowMs: NOW });
  return db;
}

const ctx = (db: McpContext["db"], ownerId = "alice"): McpContext => ({ db, ownerId, nowMs: NOW, siteOrigin: "https://example.test" });
const call = (c: McpContext, name: string, args: unknown = {}) =>
  handleMcpMessage({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name, arguments: args } }, c) as Promise<{ result: any }>;

describe("MCP protocol", () => {
  it("supports server/discover, initialize, ping, notifications, and unknown methods", async () => {
    const db = createTestDb();
    const discover = (await handleMcpMessage({ jsonrpc: "2.0", id: 1, method: "server/discover" }, ctx(db))) as any;
    assert.deepEqual(discover.result.supportedVersions, ["2026-07-28"]);
    assert.deepEqual(discover.result.capabilities, { tools: {} });
    const init = (await handleMcpMessage({ jsonrpc: "2.0", id: 2, method: "initialize", params: { protocolVersion: "2025-06-18" } }, ctx(db))) as any;
    assert.equal(init.result.protocolVersion, "2025-06-18");
    assert.deepEqual(((await handleMcpMessage({ jsonrpc: "2.0", id: 3, method: "ping" }, ctx(db))) as any).result, {});
    assert.equal(await handleMcpMessage({ jsonrpc: "2.0", method: "notifications/initialized" }, ctx(db)), null);
    assert.equal(((await handleMcpMessage({ jsonrpc: "2.0", id: 4, method: "resources/list" }, ctx(db))) as any).error.code, -32601);
    assert.equal(((await handleMcpMessage([{}], ctx(db))) as any).error.code, -32600);
  });

  it("lists read-only, closed-world tools that cannot take an owner argument", async () => {
    const list = (await handleMcpMessage({ jsonrpc: "2.0", id: 1, method: "tools/list" }, ctx(createTestDb()))) as any;
    assert.equal(list.result.tools.length, 3);
    for (const tool of TOOLS) {
      assert.equal(tool.annotations.readOnlyHint, true);
      assert.equal(tool.annotations.openWorldHint, false);
      assert.equal(tool.inputSchema.additionalProperties, false);
    }
  });
});

describe("MCP tools", () => {
  it("reports status without health values, including sharing state", async () => {
    const db = await seed();
    const res = await call(ctx(db), "get_health_connection_status");
    assert.equal(res.result.isError, false);
    assert.equal(res.result.structuredContent.chatgptAccessEnabled, false);
    assert.equal(res.result.structuredContent.latestExport.freshness, "fresh");
    assert.deepEqual(res.result.structuredContent.latestExport.categories, ["steps", "weightKg"]);
  });

  it("refuses data tools until the owner turns ChatGPT access on", async () => {
    const db = await seed();
    const res = await call(ctx(db), "get_daily_health_metrics");
    assert.equal(res.result.isError, true);
    assert.match(res.result.content[0].text, /turned off/);
  });

  it("returns daily values with gaps, units, provenance, and zero preserved", async () => {
    const db = await seed();
    await setSharing(db, "alice", true, NOW);
    const res = await call(ctx(db), "get_daily_health_metrics", { metrics: ["steps", "weightKg", "sleepMinutes"] });
    const s = res.result.structuredContent;
    assert.equal(res.result.isError, false);
    assert.deepEqual(s.range, { start: "2026-10-21", end: "2026-10-27" });
    assert.deepEqual(s.days[0].metrics.steps, { status: "value", value: 0 });
    assert.deepEqual(s.days[2].metrics.steps, { status: "no_data", value: null });
    assert.deepEqual(s.days[0].metrics.sleepMinutes, { status: "not_exported", value: null });
    assert.equal(s.days[6].partialDay, true);
    assert.equal(s.units.weightKg.unit, "kg");
    assert.match(s.units.weightKg.label, /average/);
    assert.equal(s.exportsUsed[0].freshness, "fresh");
    assert.match(s.provenance.notChatGPTHealth, /separate from ChatGPT Health/);
  });

  it("summarizes and validates arguments", async () => {
    const db = await seed();
    await setSharing(db, "alice", true, NOW);
    const res = await call(ctx(db), "get_health_metric_summary", { startDate: "2026-10-01", endDate: "2026-10-27", metrics: ["steps"] });
    const steps = res.result.structuredContent.summaries[0];
    assert.equal(steps.daysWithData, 2);
    assert.equal(steps.daysNotImported, 20);
    assert.equal(steps.trend.status, "insufficient_data");
    assert.equal((await call(ctx(db), "get_daily_health_metrics", { ownerId: "bob" })).result.isError, true);
    assert.equal((await call(ctx(db), "get_daily_health_metrics", { startDate: "2026-02-30" })).result.isError, true);
    assert.equal((await call(ctx(db), "get_daily_health_metrics", { startDate: "2026-01-01", endDate: "2026-10-27" })).result.isError, true);
  });

  it("never returns another owner's data", async () => {
    const db = await seed();
    await setSharing(db, "alice", true, NOW);
    await setSharing(db, "bob", true, NOW);
    const res = await call(ctx(db, "bob"), "get_daily_health_metrics", { startDate: "2026-10-21", endDate: "2026-10-27" });
    assert.ok(res.result.structuredContent.days.every((d: any) => !d.covered));
    const status = await call(ctx(db, "bob"), "get_health_connection_status");
    assert.equal(status.result.structuredContent.hasData, false);
  });
});
