// Read models for the dashboard and MCP tools. Values are reported exactly as imported;
// missing values stay missing, with an explicit reason, and nothing is interpolated.

import { METRIC_KEYS, METRICS, type MetricKey } from "./metrics.ts";
import {
  getCoverage,
  getSharing,
  getStoredDays,
  listImports,
  type Coverage,
  type ImportRecord,
  type SharingState,
  type SqlDatabase,
  type StoredDay,
  type UploadMethod,
} from "./store.ts";
import { SCHEDULING_NOTE, listDevices, type DeviceRecord } from "./sync.ts";
import { datesInRange } from "./time.ts";

export const FRESH_HOURS = 24;

export type Freshness = "fresh" | "stale";

export function freshnessOf(generatedAtMs: number, nowMs: number): { freshness: Freshness; ageHours: number } {
  const ageHours = Math.max(0, Math.round(((nowMs - generatedAtMs) / 3_600_000) * 10) / 10);
  return { freshness: ageHours <= FRESH_HOURS ? "fresh" : "stale", ageHours };
}

/**
 * Why a metric has no value for a date:
 * - no_data: the day was exported with this category, and Health Connect returned no records.
 * - not_exported: the day was exported, but this category was not granted/selected for that export.
 * - not_imported: no uploaded export covers this date.
 */
export type MetricStatus = "value" | "no_data" | "not_exported" | "not_imported";

export type DailyMetricCell = { status: MetricStatus; value: number | null };

export type DailyRow = {
  date: string;
  covered: boolean;
  /** True when the export was generated before this local day ended, so totals may be incomplete. */
  partialDay: boolean;
  dayStart: string | null;
  dayEnd: string | null;
  sleepWindow: { start: string; end: string } | null;
  exportGeneratedAt: string | null;
  importId: string | null;
  uploadMethod: UploadMethod | null;
  timezone: string | null;
  metrics: Partial<Record<MetricKey, DailyMetricCell>>;
};

export function buildDailyRows(stored: StoredDay[], start: string, end: string, metrics: readonly MetricKey[]): DailyRow[] {
  const byDate = new Map(stored.map((d) => [d.date, d]));
  return datesInRange(start, end).map((date) => {
    const day = byDate.get(date);
    const cells: Partial<Record<MetricKey, DailyMetricCell>> = {};
    for (const key of metrics) {
      if (!day) cells[key] = { status: "not_imported", value: null };
      else if (!day.categories.includes(key)) cells[key] = { status: "not_exported", value: null };
      else if (day.values[key] === undefined) cells[key] = { status: "no_data", value: null };
      else cells[key] = { status: "value", value: day.values[key] as number };
    }
    return {
      date,
      covered: Boolean(day),
      partialDay: day ? day.generatedAtMs < day.dayEndMs : false,
      dayStart: day?.dayStart ?? null,
      dayEnd: day?.dayEnd ?? null,
      sleepWindow:
        day?.sleepWindowStart && day.sleepWindowEnd ? { start: day.sleepWindowStart, end: day.sleepWindowEnd } : null,
      exportGeneratedAt: day?.generatedAt ?? null,
      importId: day?.importId ?? null,
      uploadMethod: day?.uploadMethod ?? null,
      timezone: day?.timezone ?? null,
      metrics: cells,
    };
  });
}

export async function loadDailyRows(
  db: SqlDatabase,
  ownerId: string,
  start: string,
  end: string,
  metrics: readonly MetricKey[] = METRIC_KEYS,
): Promise<DailyRow[]> {
  return buildDailyRows(await getStoredDays(db, ownerId, start, end), start, end, metrics);
}

export type MetricSummary = {
  metric: MetricKey;
  label: string;
  unit: string;
  aggregation: string;
  daysWithData: number;
  daysNoData: number;
  daysNotExported: number;
  daysNotImported: number;
  partialDaysExcluded: number;
  mean: number | null;
  min: { value: number; date: string } | null;
  max: { value: number; date: string } | null;
  latest: { value: number; date: string } | null;
  trend:
    | { status: "insufficient_data"; note: string }
    | {
        status: "computed";
        direction: "up" | "down" | "flat";
        earlierMean: number;
        laterMean: number;
        change: number;
        changePercent: number | null;
        earlierDays: number;
        laterDays: number;
        note: string;
      };
};

const TREND_MIN_POINTS = 4;
const FLAT_THRESHOLD_PERCENT = 5;

function round(value: number, decimals: number): number {
  const f = 10 ** decimals;
  return Math.round(value * f) / f;
}

function mean(values: number[]): number {
  return values.reduce((a, b) => a + b, 0) / values.length;
}

/** Descriptive statistics only; partial (in-progress) days are excluded so totals are not understated. */
export function summarize(rows: DailyRow[], metric: MetricKey): MetricSummary {
  const def = METRICS[metric];
  const decimals = Math.max(def.decimals, 1);
  const points: { date: string; value: number }[] = [];
  let noData = 0;
  let notExported = 0;
  let notImported = 0;
  let partial = 0;
  for (const row of rows) {
    const cell = row.metrics[metric];
    if (!cell) continue;
    if (cell.status === "value" && cell.value !== null) {
      if (row.partialDay) partial++;
      else points.push({ date: row.date, value: cell.value });
    } else if (cell.status === "no_data") noData++;
    else if (cell.status === "not_exported") notExported++;
    else notImported++;
  }

  const values = points.map((p) => p.value);
  const minPoint = points.reduce<typeof points[number] | null>((m, p) => (!m || p.value < m.value ? p : m), null);
  const maxPoint = points.reduce<typeof points[number] | null>((m, p) => (!m || p.value > m.value ? p : m), null);
  const latest = points.at(-1) ?? null;

  let trend: MetricSummary["trend"];
  if (points.length < TREND_MIN_POINTS) {
    trend = {
      status: "insufficient_data",
      note: `At least ${TREND_MIN_POINTS} complete days with data are needed to compare earlier and later periods.`,
    };
  } else {
    const half = Math.floor(points.length / 2);
    const earlier = values.slice(0, half);
    const later = values.slice(points.length - half);
    const earlierMean = mean(earlier);
    const laterMean = mean(later);
    const change = laterMean - earlierMean;
    const changePercent = earlierMean === 0 ? null : (change / earlierMean) * 100;
    const direction =
      changePercent === null
        ? change === 0
          ? "flat"
          : change > 0
            ? "up"
            : "down"
        : Math.abs(changePercent) < FLAT_THRESHOLD_PERCENT
          ? "flat"
          : change > 0
            ? "up"
            : "down";
    trend = {
      status: "computed",
      direction,
      earlierMean: round(earlierMean, decimals),
      laterMean: round(laterMean, decimals),
      change: round(change, decimals),
      changePercent: changePercent === null ? null : round(changePercent, 1),
      earlierDays: earlier.length,
      laterDays: later.length,
      note: `Compares the mean of the earliest ${earlier.length} and latest ${later.length} complete days with data; changes under ${FLAT_THRESHOLD_PERCENT}% are reported as flat. Descriptive only.`,
    };
  }

  return {
    metric,
    label: def.label,
    unit: def.unit,
    aggregation: def.aggregation,
    daysWithData: points.length,
    daysNoData: noData,
    daysNotExported: notExported,
    daysNotImported: notImported,
    partialDaysExcluded: partial,
    mean: values.length ? round(mean(values), decimals) : null,
    min: minPoint ? { value: round(minPoint.value, decimals), date: minPoint.date } : null,
    max: maxPoint ? { value: round(maxPoint.value, decimals), date: maxPoint.date } : null,
    latest: latest ? { value: round(latest.value, decimals), date: latest.date } : null,
    trend,
  };
}

/**
 * - automatic: a paired phone reported its automatic (background) sync switch on.
 * - paired_manual: paired, but the phone reported automatic sync off or unavailable; it uploads on "Sync now".
 * - paired_awaiting_first_sync: paired, no upload received yet.
 * - not_paired: no active device; data can only arrive by manual browser upload.
 */
export type SyncMode = "automatic" | "paired_manual" | "paired_awaiting_first_sync" | "not_paired";

export type SyncSummary = {
  mode: SyncMode;
  activeDevice: DeviceRecord | null;
  recentDevices: DeviceRecord[];
  lastSuccessfulSyncAt: string | null;
  scheduling: string;
};

export async function loadSyncSummary(db: SqlDatabase, ownerId: string): Promise<SyncSummary> {
  const devices = await listDevices(db, ownerId);
  const active = devices.find((d) => d.active) ?? null;
  const mode: SyncMode = !active
    ? "not_paired"
    : !active.lastSyncAt && !active.lastAttemptAt
      ? "paired_awaiting_first_sync"
      : active.autoSyncReported
        ? "automatic"
        : "paired_manual";
  return { mode, activeDevice: active, recentDevices: devices, lastSuccessfulSyncAt: active?.lastSyncAt ?? null, scheduling: SCHEDULING_NOTE };
}

export type ConnectionStatus = {
  sharing: SharingState;
  sync: SyncSummary;
  hasData: boolean;
  coverage: Coverage;
  latestImport:
    | (ImportRecord & { freshness: Freshness; ageHours: number })
    | null;
  categoriesInLatestImport: MetricKey[];
  importCount: number;
};

export async function loadConnectionStatus(db: SqlDatabase, ownerId: string, nowMs: number): Promise<ConnectionStatus> {
  const [sharing, coverage, imports, sync] = await Promise.all([
    getSharing(db, ownerId),
    getCoverage(db, ownerId),
    listImports(db, ownerId),
    loadSyncSummary(db, ownerId),
  ]);
  // "Latest" means the most recently generated export still backing stored days.
  const active = imports.filter((i) => i.activeDays > 0).sort((a, b) => b.generatedAtMs - a.generatedAtMs);
  const latest = active[0] ?? null;
  return {
    sharing,
    sync,
    hasData: coverage.daysStored > 0,
    coverage,
    latestImport: latest ? { ...latest, ...freshnessOf(latest.generatedAtMs, nowMs) } : null,
    categoriesInLatestImport: latest?.categories ?? [],
    importCount: imports.length,
  };
}
