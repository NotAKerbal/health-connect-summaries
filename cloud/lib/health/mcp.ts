// Stateless MCP server (JSON-RPC over POST /mcp). Read-only tools over the caller's own
// imported daily summaries. The owner is always the authenticated Site user; tool
// arguments cannot select another owner (unknown arguments are rejected).

import { METRIC_KEYS, METRICS, isMetricKey, type MetricKey } from "./metrics.ts";
import { freshnessOf, loadConnectionStatus, loadDailyRows, summarize, type DailyRow, type SyncSummary } from "./query.ts";
import { getCoverage, getSharing, type SqlDatabase } from "./store.ts";
import { addDays, daySpan, isValidDate } from "./time.ts";

export const MCP_PROTOCOL_VERSION = "2026-07-28";
const LEGACY_PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05"];
const SERVER_INFO = { name: "health-connect-summaries", title: "Health Connect Summaries", version: "0.2.0" };
const MAX_DAILY_SPAN = 92;
const MAX_SUMMARY_SPAN = 366;

const INSTRUCTIONS = [
  "Read-only access to daily health summaries from Android Health Connect stored on the user's private Site, uploaded automatically by their paired Android phone or, optionally, by a manual file upload.",
  "This is not ChatGPT Health and not real time: automatic sync runs on Android's best-effort background schedule (at most about every 15 minutes, often later), so always report when the newest snapshot was generated and whether it is fresh or stale.",
  "Report units and gaps exactly as returned. A missing value means no data, a category that was not exported, or a date that was not imported; never treat it as zero.",
  "Provide descriptive observations only. Do not diagnose conditions or give medical advice; suggest a clinician for health concerns.",
].join(" ");

export type McpContext = { db: SqlDatabase; ownerId: string; nowMs: number; siteOrigin: string };

type JsonRpcId = string | number | null;
type JsonRpcRequest = { jsonrpc: "2.0"; id?: JsonRpcId; method: string; params?: unknown };
export type JsonRpcResponse =
  | { jsonrpc: "2.0"; id: JsonRpcId; result: unknown }
  | { jsonrpc: "2.0"; id: JsonRpcId; error: { code: number; message: string; data?: unknown } };

const READ_ONLY_ANNOTATIONS = {
  readOnlyHint: true,
  destructiveHint: false,
  idempotentHint: true,
  openWorldHint: false,
};

const metricsArgSchema = {
  type: "array",
  items: { type: "string", enum: [...METRIC_KEYS] },
  uniqueItems: true,
  minItems: 1,
  description: `Metrics to include (default: all). ${METRIC_KEYS.map((k) => `${k} = ${METRICS[k].label} [${METRICS[k].unit}]`).join("; ")}.`,
};
const dateArg = (what: string) => ({ type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$", description: what });

export const TOOLS = [
  {
    name: "get_health_connection_status",
    title: "Health Connect import status",
    description:
      "Check whether ChatGPT access is turned on, whether a phone is paired for automatic sync and when it last synced, which categories the latest snapshot contains, the stored date coverage, and how fresh the data is. Call this first. Returns no health values.",
    inputSchema: { type: "object", properties: {}, additionalProperties: false },
    annotations: { title: "Health Connect import status", ...READ_ONLY_ANNOTATIONS },
  },
  {
    name: "get_daily_health_metrics",
    title: "Daily health metrics",
    description:
      "Return per-day values (steps, sleep minutes, average heart rate, average weight, distance, active calories) for a local-date range from the user's synced or uploaded Health Connect snapshots, with units, gap reasons, partial-day flags, and per-snapshot provenance. Defaults to the 7 days ending at the latest stored date. Maximum range 92 days.",
    inputSchema: {
      type: "object",
      properties: {
        startDate: dateArg("First local date (YYYY-MM-DD), inclusive."),
        endDate: dateArg("Last local date (YYYY-MM-DD), inclusive."),
        metrics: metricsArgSchema,
      },
      additionalProperties: false,
    },
    annotations: { title: "Daily health metrics", ...READ_ONLY_ANNOTATIONS },
  },
  {
    name: "get_health_metric_summary",
    title: "Health metric summary and trend",
    description:
      "Summarize each metric over a local-date range: days with data and gap counts, mean, min, max, latest value, and a descriptive earlier-vs-later trend. Partial (in-progress) days are excluded. Defaults to the 30 days ending at the latest stored date. Maximum range 366 days. Not a diagnosis.",
    inputSchema: {
      type: "object",
      properties: {
        startDate: dateArg("First local date (YYYY-MM-DD), inclusive."),
        endDate: dateArg("Last local date (YYYY-MM-DD), inclusive."),
        metrics: metricsArgSchema,
      },
      additionalProperties: false,
    },
    annotations: { title: "Health metric summary and trend", ...READ_ONLY_ANNOTATIONS },
  },
];

class ToolInputError extends Error {}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function rpcError(id: JsonRpcId, code: number, message: string): JsonRpcResponse {
  return { jsonrpc: "2.0", id, error: { code, message } };
}

/** Returns null for notifications (no response body expected). */
export async function handleMcpMessage(message: unknown, ctx: McpContext): Promise<JsonRpcResponse | null> {
  if (Array.isArray(message)) return rpcError(null, -32600, "Batch requests are not supported");
  if (!isObject(message) || message.jsonrpc !== "2.0" || typeof message.method !== "string") {
    return rpcError(null, -32600, "Invalid JSON-RPC request");
  }
  const req = message as JsonRpcRequest;
  const isNotification = !("id" in message);
  const id: JsonRpcId = isNotification ? null : (req.id ?? null);
  if (isNotification) return null; // e.g. notifications/initialized, notifications/cancelled

  const params = isObject(req.params) ? req.params : {};
  switch (req.method) {
    case "server/discover":
      return {
        jsonrpc: "2.0",
        id,
        result: {
          supportedVersions: [MCP_PROTOCOL_VERSION],
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO,
          instructions: INSTRUCTIONS,
        },
      };
    case "initialize": {
      const requested = typeof params.protocolVersion === "string" ? params.protocolVersion : "";
      const all = [MCP_PROTOCOL_VERSION, ...LEGACY_PROTOCOL_VERSIONS];
      return {
        jsonrpc: "2.0",
        id,
        result: {
          protocolVersion: all.includes(requested) ? requested : LEGACY_PROTOCOL_VERSIONS[1],
          capabilities: { tools: { listChanged: false } },
          serverInfo: SERVER_INFO,
          instructions: INSTRUCTIONS,
        },
      };
    }
    case "ping":
      return { jsonrpc: "2.0", id, result: {} };
    case "tools/list":
      return { jsonrpc: "2.0", id, result: { tools: TOOLS } };
    case "tools/call": {
      const name = params.name;
      const args = params.arguments === undefined ? {} : params.arguments;
      if (typeof name !== "string" || !TOOLS.some((t) => t.name === name)) {
        return rpcError(id, -32602, `Unknown tool: ${String(name)}`);
      }
      if (!isObject(args)) return rpcError(id, -32602, "Tool arguments must be an object");
      try {
        return { jsonrpc: "2.0", id, result: await callTool(name, args, ctx) };
      } catch (error) {
        if (error instanceof ToolInputError) return { jsonrpc: "2.0", id, result: toolError(error.message) };
        return { jsonrpc: "2.0", id, result: toolError("The health summary store is temporarily unavailable. Try again shortly.") };
      }
    }
    default:
      return rpcError(id, -32601, `Method not found: ${req.method}`);
  }
}

function toolResult(text: string, structured: Record<string, unknown>) {
  return {
    content: [
      { type: "text", text },
      { type: "text", text: JSON.stringify(structured) },
    ],
    structuredContent: structured,
    isError: false,
  };
}

function toolError(message: string) {
  return { content: [{ type: "text", text: message }], isError: true };
}

function provenance(siteOrigin: string) {
  return {
    source: "Android Health Connect, via the Health Connect Export companion app",
    collection:
      "The phone computes daily aggregates and sends 30-day snapshots to the private Site. With automatic sync on, a paired phone uploads in the background on Android's best-effort schedule (at most about every 15 minutes, often later, delayed by battery saver, Doze, or no network); the user can also tap Sync now. A manual file upload in the browser is an optional fallback. Each entry in exportsUsed states which path (device_sync or browser_upload) it came from.",
    aggregation: "Daily values come from the Health Connect aggregate API. Activity and sleep use Health Connect's source-priority deduplication; other metrics combine records from all contributing sources.",
    notChatGPTHealth: "This plugin is separate from ChatGPT Health and does not read or write ChatGPT Health data.",
    disclaimer: "Descriptive data only, not a diagnosis or medical advice.",
    updateUrl: `${siteOrigin}/`,
  };
}

function syncInfo(sync: SyncSummary) {
  const d = sync.activeDevice;
  return {
    mode: sync.mode,
    modeMeaning: {
      automatic: "A paired phone has automatic background sync turned on.",
      paired_manual: "A phone is paired, but its automatic sync is off or unsupported on that device; it uploads only when the user taps Sync now.",
      paired_awaiting_first_sync: "A phone is paired but has not uploaded yet.",
      not_paired: "No phone is paired; data only changes through manual browser uploads.",
    }[sync.mode],
    device: d
      ? {
          name: d.name,
          pairedAt: d.pairedAt,
          lastSuccessfulSyncAt: d.lastSyncAt,
          lastAttemptAt: d.lastAttemptAt,
          lastAttemptStatus: d.lastAttemptStatus,
          lastTrigger: d.lastTrigger,
          automaticSyncReportedOn: d.autoSyncReported,
        }
      : null,
    scheduling: sync.scheduling,
  };
}

function units(metrics: readonly MetricKey[]) {
  return Object.fromEntries(metrics.map((k) => [k, { unit: METRICS[k].unit, label: METRICS[k].label, aggregation: METRICS[k].aggregation }]));
}

function parseArgs(args: Record<string, unknown>) {
  for (const key of Object.keys(args)) {
    if (!["startDate", "endDate", "metrics"].includes(key)) throw new ToolInputError(`Unknown argument: ${key}`);
  }
  if (args.startDate !== undefined && !isValidDate(args.startDate)) throw new ToolInputError("startDate must be a valid YYYY-MM-DD date");
  if (args.endDate !== undefined && !isValidDate(args.endDate)) throw new ToolInputError("endDate must be a valid YYYY-MM-DD date");
  let metrics: MetricKey[] = [...METRIC_KEYS];
  if (args.metrics !== undefined) {
    if (!Array.isArray(args.metrics) || args.metrics.length === 0) throw new ToolInputError("metrics must be a non-empty array");
    const bad = args.metrics.find((m) => !isMetricKey(m));
    if (bad !== undefined) throw new ToolInputError(`Unknown metric: ${String(bad)}. Allowed: ${METRIC_KEYS.join(", ")}`);
    metrics = METRIC_KEYS.filter((k) => (args.metrics as unknown[]).includes(k));
  }
  return { startDate: args.startDate as string | undefined, endDate: args.endDate as string | undefined, metrics };
}

async function resolveRange(
  ctx: McpContext,
  startDate: string | undefined,
  endDate: string | undefined,
  defaultDays: number,
  maxSpan: number,
): Promise<{ start: string; end: string } | null> {
  let end = endDate;
  if (!end) {
    if (startDate) end = addDays(startDate, defaultDays - 1);
    else {
      const coverage = await getCoverage(ctx.db, ctx.ownerId);
      if (!coverage.lastDate) return null;
      end = coverage.lastDate;
    }
  }
  const start = startDate ?? addDays(end, -(defaultDays - 1));
  const span = daySpan(start, end);
  if (span < 1) throw new ToolInputError("endDate must not be before startDate");
  if (span > maxSpan) throw new ToolInputError(`Date range is limited to ${maxSpan} days`);
  return { start, end };
}

const SHARING_OFF =
  "ChatGPT access to Health Connect summaries is turned off. The user can turn it on in the ChatGPT plugin access card on their private Site. Pairing a phone does not turn it on.";

async function callTool(name: string, args: Record<string, unknown>, ctx: McpContext) {
  if (name === "get_health_connection_status") {
    if (Object.keys(args).length > 0) throw new ToolInputError("This tool takes no arguments");
    const status = await loadConnectionStatus(ctx.db, ctx.ownerId, ctx.nowMs);
    const latest = status.latestImport;
    const structured = {
      chatgptAccessEnabled: status.sharing.enabled,
      hasData: status.hasData,
      sync: syncInfo(status.sync),
      coverage: status.coverage,
      latestExport: latest
        ? {
            generatedAt: latest.generatedAt,
            uploadedAt: latest.importedAt,
            uploadMethod: latest.uploadMethod,
            ageHours: latest.ageHours,
            freshness: latest.freshness,
            freshnessRule: "fresh = generated within the last 24 hours; otherwise stale",
            timezone: latest.timezone,
            rangeStart: latest.rangeStart,
            rangeEnd: latest.rangeEnd,
            categories: latest.categories,
          }
        : null,
      availableMetrics: units(METRIC_KEYS),
      provenance: provenance(ctx.siteOrigin),
    };
    const device = status.sync.activeDevice;
    const syncText =
      status.sync.mode === "not_paired"
        ? "No phone is paired for automatic sync."
        : `Paired phone "${device?.name}": sync mode ${status.sync.mode}, last successful sync ${device?.lastSyncAt ?? "never"}.`;
    const text = !status.hasData
      ? `No Health Connect data is stored yet. ${syncText} Pair the Android app at ${ctx.siteOrigin}/. ChatGPT access is ${status.sharing.enabled ? "on" : "off"}.`
      : `ChatGPT access is ${status.sharing.enabled ? "on" : "off"}. ${syncText} Stored dates ${status.coverage.firstDate} to ${status.coverage.lastDate} (${status.coverage.daysStored} days). Latest snapshot (${latest?.uploadMethod}) generated ${latest?.generatedAt} (${latest?.freshness}, ${latest?.ageHours} h old) in ${latest?.timezone}, categories: ${latest?.categories.join(", ")}. Automatic sync is best effort, not real time.`;
    return toolResult(text, structured);
  }

  const parsed = parseArgs(args);
  const sharing = await getSharing(ctx.db, ctx.ownerId);
  if (!sharing.enabled) return toolError(SHARING_OFF);

  const daily = name === "get_daily_health_metrics";
  const range = await resolveRange(ctx, parsed.startDate, parsed.endDate, daily ? 7 : 30, daily ? MAX_DAILY_SPAN : MAX_SUMMARY_SPAN);
  if (!range) {
    return toolResult(`No Health Connect data is stored yet. Pair the Android app for automatic sync at ${ctx.siteOrigin}/.`, {
      days: [],
      provenance: provenance(ctx.siteOrigin),
    });
  }
  const rows = await loadDailyRows(ctx.db, ctx.ownerId, range.start, range.end, parsed.metrics);
  const exportsUsed = exportsIn(rows, ctx.nowMs);

  if (daily) {
    const structured = {
      range,
      units: units(parsed.metrics),
      gapStatuses: {
        value: "A value was exported for this day.",
        no_data: "The category was exported for this day but Health Connect had no records. Not zero.",
        not_exported: "The snapshot covering this day did not include this category (not granted or not selected).",
        not_imported: "No synced or uploaded snapshot covers this date.",
      },
      days: rows.map((r) => ({
        date: r.date,
        covered: r.covered,
        partialDay: r.partialDay,
        dayStart: r.dayStart,
        dayEnd: r.dayEnd,
        sleepWindow: r.sleepWindow,
        exportGeneratedAt: r.exportGeneratedAt,
        uploadMethod: r.uploadMethod,
        timezone: r.timezone,
        metrics: r.metrics,
      })),
      exportsUsed,
      provenance: provenance(ctx.siteOrigin),
    };
    const covered = rows.filter((r) => r.covered).length;
    const text = `Daily Health Connect values for ${range.start} to ${range.end}: ${covered} of ${rows.length} dates covered by stored snapshots. ${describeExports(exportsUsed)} Missing values are gaps, not zeros.`;
    return toolResult(text, structured);
  }

  const summaries = parsed.metrics.map((m) => summarize(rows, m));
  const structured = {
    range,
    summaries,
    exportsUsed,
    provenance: provenance(ctx.siteOrigin),
  };
  const text = `Summary of Health Connect values for ${range.start} to ${range.end}. ${describeExports(exportsUsed)} Descriptive statistics only; partial days are excluded and missing values are not treated as zero.`;
  return toolResult(text, structured);
}

function exportsIn(rows: DailyRow[], nowMs: number) {
  const seen = new Map<string, { generatedAt: string; uploadMethod: string | null; timezone: string | null; days: number }>();
  for (const r of rows) {
    if (!r.importId || !r.exportGeneratedAt) continue;
    const entry = seen.get(r.importId) ?? { generatedAt: r.exportGeneratedAt, uploadMethod: r.uploadMethod, timezone: r.timezone, days: 0 };
    entry.days++;
    seen.set(r.importId, entry);
  }
  return [...seen.values()]
    .sort((a, b) => b.generatedAt.localeCompare(a.generatedAt))
    .map((e) => ({ ...e, ...freshnessOf(Date.parse(e.generatedAt), nowMs) }));
}

function describeExports(exportsUsed: ReturnType<typeof exportsIn>): string {
  if (exportsUsed.length === 0) return "No stored snapshot covers this range.";
  const newest = exportsUsed[0];
  return `Newest snapshot used was generated ${newest.generatedAt} (${newest.uploadMethod}, ${newest.freshness}, ${newest.ageHours} h old, ${newest.timezone}).`;
}
