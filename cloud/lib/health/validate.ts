// Strict validation of Android Health Connect exports (schemaVersion 1).
// The payload is untrusted user input: every field is checked and unknown fields are rejected.

import { METRIC_KEYS, METRICS, isMetricKey, type MetricKey } from "./metrics.ts";
import {
  addDays,
  daySpan,
  isStartOfLocalDay,
  isValidDate,
  isValidTimeZone,
  parseInstant,
  wallClock,
} from "./time.ts";

export const EXPORT_SCHEMA_VERSION = 1;
export const EXPORT_SOURCE = "android-health-connect";
export const MAX_EXPORT_BYTES = 64 * 1024;
export const MAX_EXPORT_DAYS = 30;
const MAX_ISSUES = 20;
const FUTURE_SKEW_MS = 5 * 60_000;
const EARLIEST_GENERATED_AT_MS = Date.UTC(2015, 0, 1);

const TOP_LEVEL_KEYS = new Set([
  "schemaVersion",
  "source",
  "generatedAt",
  "timezone",
  "rangeStart",
  "rangeEnd",
  "categories",
  "days",
]);
const DAY_BASE_KEYS = new Set(["date", "start", "end", "sleepWindowStart", "sleepWindowEnd"]);

export type ValidDay = {
  date: string;
  startMs: number;
  endMs: number;
  start: string;
  end: string;
  sleepWindowStart: string | null;
  sleepWindowEnd: string | null;
  metrics: Partial<Record<MetricKey, number>>;
};

export type ValidExport = {
  schemaVersion: 1;
  source: typeof EXPORT_SOURCE;
  generatedAt: string;
  generatedAtMs: number;
  timezone: string;
  rangeStart: string;
  rangeEnd: string;
  categories: MetricKey[];
  days: ValidDay[];
};

export type ValidationResult =
  | { ok: true; value: ValidExport }
  | { ok: false; issues: string[] };

class Issues {
  list: string[] = [];
  add(path: string, message: string) {
    if (this.list.length < MAX_ISSUES) this.list.push(`${path}: ${message}`);
  }
  get full() {
    return this.list.length >= MAX_ISSUES;
  }
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Parses raw bytes and validates them. `nowMs` is injectable for tests. */
export function parseAndValidateExport(text: string, nowMs: number = Date.now()): ValidationResult {
  if (new TextEncoder().encode(text).byteLength > MAX_EXPORT_BYTES) {
    return { ok: false, issues: [`file: larger than ${MAX_EXPORT_BYTES / 1024} KiB`] };
  }
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    return { ok: false, issues: ["file: not valid JSON"] };
  }
  return validateExport(json, nowMs);
}

export function validateExport(input: unknown, nowMs: number = Date.now()): ValidationResult {
  const issues = new Issues();
  if (!isPlainObject(input)) return { ok: false, issues: ["$: must be a JSON object"] };

  for (const key of Object.keys(input)) {
    if (!TOP_LEVEL_KEYS.has(key)) issues.add(key, "unknown field");
  }
  if (input.schemaVersion !== EXPORT_SCHEMA_VERSION) {
    issues.add("schemaVersion", `must be ${EXPORT_SCHEMA_VERSION}`);
  }
  if (input.source !== EXPORT_SOURCE) issues.add("source", `must be "${EXPORT_SOURCE}"`);

  const generated = parseInstant(input.generatedAt);
  if (!generated) {
    issues.add("generatedAt", "must be an ISO 8601 timestamp with an offset");
  } else if (generated.ms > nowMs + FUTURE_SKEW_MS) {
    issues.add("generatedAt", "is in the future");
  } else if (generated.ms < EARLIEST_GENERATED_AT_MS) {
    issues.add("generatedAt", "is implausibly old");
  }

  const timezoneOk = isValidTimeZone(input.timezone);
  if (!timezoneOk) issues.add("timezone", "must be a valid IANA time zone such as America/Chicago");

  const rangeOk = isValidDate(input.rangeStart) && isValidDate(input.rangeEnd);
  if (!isValidDate(input.rangeStart)) issues.add("rangeStart", "must be a YYYY-MM-DD date");
  if (!isValidDate(input.rangeEnd)) issues.add("rangeEnd", "must be a YYYY-MM-DD date");
  let span = 0;
  if (rangeOk) {
    span = daySpan(input.rangeStart as string, input.rangeEnd as string);
    if (span < 1) issues.add("rangeEnd", "must not be before rangeStart");
    else if (span > MAX_EXPORT_DAYS) issues.add("rangeEnd", `range may cover at most ${MAX_EXPORT_DAYS} days`);
  }

  const categories: MetricKey[] = [];
  if (!Array.isArray(input.categories) || input.categories.length === 0) {
    issues.add("categories", "must be a non-empty array of metric names");
  } else if (input.categories.length > METRIC_KEYS.length) {
    issues.add("categories", "has too many entries");
  } else {
    for (const [i, c] of input.categories.entries()) {
      if (!isMetricKey(c)) issues.add(`categories[${i}]`, `unknown metric (allowed: ${METRIC_KEYS.join(", ")})`);
      else if (categories.includes(c)) issues.add(`categories[${i}]`, "duplicate");
      else categories.push(c);
    }
  }

  if (timezoneOk && rangeOk && generated) {
    const tz = input.timezone as string;
    const generatedLocalDate = wallClock(generated.ms, tz).date;
    if ((input.rangeEnd as string) > generatedLocalDate) {
      issues.add("rangeEnd", "is after the local date the export was generated");
    }
  }

  const days: ValidDay[] = [];
  if (!Array.isArray(input.days)) {
    issues.add("days", "must be an array");
  } else if (input.days.length > MAX_EXPORT_DAYS) {
    issues.add("days", `may contain at most ${MAX_EXPORT_DAYS} entries`);
  } else if (rangeOk && span >= 1 && input.days.length !== span) {
    issues.add("days", `must contain exactly one entry per date from rangeStart to rangeEnd (${span})`);
  } else if (timezoneOk && rangeOk && span >= 1) {
    const tz = input.timezone as string;
    const wantsSleep = categories.includes("sleepMinutes");
    for (const [i, raw] of input.days.entries()) {
      if (issues.full) break;
      const day = validateDay(raw, `days[${i}]`, addDays(input.rangeStart as string, i), tz, categories, wantsSleep, issues);
      if (day) days.push(day);
      if (day && generated && day.startMs > generated.ms) {
        issues.add(`days[${i}].date`, "starts after the export was generated");
      }
    }
  }

  if (issues.list.length > 0 || !generated) return { ok: false, issues: issues.list };

  return {
    ok: true,
    value: {
      schemaVersion: 1,
      source: EXPORT_SOURCE,
      generatedAt: new Date(generated.ms).toISOString(),
      generatedAtMs: generated.ms,
      timezone: input.timezone as string,
      rangeStart: input.rangeStart as string,
      rangeEnd: input.rangeEnd as string,
      categories: METRIC_KEYS.filter((k) => categories.includes(k)),
      days,
    },
  };
}

function validateDay(
  raw: unknown,
  path: string,
  expectedDate: string,
  tz: string,
  categories: MetricKey[],
  wantsSleep: boolean,
  issues: Issues,
): ValidDay | null {
  if (!isPlainObject(raw)) {
    issues.add(path, "must be an object");
    return null;
  }
  const before = issues.list.length;
  for (const key of Object.keys(raw)) {
    if (!DAY_BASE_KEYS.has(key) && !isMetricKey(key)) issues.add(`${path}.${key}`, "unknown field");
  }
  if (raw.date !== expectedDate) {
    issues.add(`${path}.date`, `must be ${expectedDate} (days must be consecutive, ascending, without duplicates)`);
  }

  const start = parseInstant(raw.start);
  const end = parseInstant(raw.end);
  if (!start) issues.add(`${path}.start`, "must be an ISO 8601 timestamp with an offset");
  else if (!isStartOfLocalDay(start.ms, expectedDate, tz) || start.offsetMinutes !== wallClock(start.ms, tz).offsetMinutes) {
    issues.add(`${path}.start`, `must be the start of ${expectedDate} in ${tz}`);
  }
  const nextDate = addDays(expectedDate, 1);
  if (!end) issues.add(`${path}.end`, "must be an ISO 8601 timestamp with an offset");
  else if (!isStartOfLocalDay(end.ms, nextDate, tz) || end.offsetMinutes !== wallClock(end.ms, tz).offsetMinutes) {
    issues.add(`${path}.end`, `must be the start of ${nextDate} in ${tz}`);
  }

  let sleepWindowStart: string | null = null;
  let sleepWindowEnd: string | null = null;
  if (wantsSleep) {
    const ws = parseInstant(raw.sleepWindowStart);
    const we = parseInstant(raw.sleepWindowEnd);
    if (!ws || !isSleepBoundary(ws.ms, addDays(expectedDate, -1), tz)) {
      issues.add(`${path}.sleepWindowStart`, `must be 18:00 on ${addDays(expectedDate, -1)} in ${tz}`);
    }
    if (!we || !isSleepBoundary(we.ms, expectedDate, tz)) {
      issues.add(`${path}.sleepWindowEnd`, `must be 18:00 on ${expectedDate} in ${tz}`);
    }
    if (ws && we) {
      sleepWindowStart = new Date(ws.ms).toISOString();
      sleepWindowEnd = new Date(we.ms).toISOString();
    }
  } else if (raw.sleepWindowStart !== undefined || raw.sleepWindowEnd !== undefined) {
    issues.add(`${path}.sleepWindowStart`, "only allowed when sleepMinutes is an exported category");
  }

  const metrics: Partial<Record<MetricKey, number>> = {};
  for (const key of METRIC_KEYS) {
    if (!(key in raw)) continue; // Absent means "no data recorded", never zero.
    const value = raw[key];
    const def = METRICS[key];
    if (!categories.includes(key)) {
      issues.add(`${path}.${key}`, "present but not listed in categories");
    } else if (typeof value !== "number" || !Number.isFinite(value)) {
      issues.add(`${path}.${key}`, "must be a finite number (omit the field when there is no data)");
    } else if (def.integer && !Number.isInteger(value)) {
      issues.add(`${path}.${key}`, "must be a whole number");
    } else if (value < def.min || value > def.max || (def.minExclusive && value === def.min)) {
      issues.add(`${path}.${key}`, `must be between ${def.min} and ${def.max} ${def.unit}`);
    } else {
      metrics[key] = value;
    }
  }

  if (issues.list.length > before || !start || !end) return null;
  return {
    date: expectedDate,
    startMs: start.ms,
    endMs: end.ms,
    start: raw.start as string,
    end: raw.end as string,
    sleepWindowStart,
    sleepWindowEnd,
    metrics,
  };
}

function isSleepBoundary(ms: number, date: string, tz: string): boolean {
  const clock = wallClock(ms, tz);
  // 18:00 local; java.time shifts forward by the gap length if 18:00 falls in a DST gap.
  return clock.date === date && (clock.hour === 18 || clock.hour === 19) && clock.minute === 0 && clock.second === 0;
}

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
