// Test-only helpers: a D1-compatible adapter over node:sqlite running the real migrations,
// and a builder for well-formed exports (mirrors the Android app's output format).
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import type { SqlDatabase, SqlStatement } from "../lib/health/store.ts";
import { addDays, wallClock } from "../lib/health/time.ts";

const MIGRATIONS = join(import.meta.dirname, "..", "drizzle");

export function createTestDb(): SqlDatabase & { raw: DatabaseSync } {
  const raw = new DatabaseSync(":memory:");
  for (const file of readdirSync(MIGRATIONS).filter((f) => f.endsWith(".sql")).sort()) {
    for (const stmt of readFileSync(join(MIGRATIONS, file), "utf8").split("--> statement-breakpoint")) {
      if (stmt.trim()) raw.exec(stmt);
    }
  }
  const prepare = (sql: string, values: unknown[] = []): SqlStatement & { exec(): unknown } => ({
    bind: (...v: unknown[]) => prepare(sql, v),
    async first<T>() {
      return ((raw.prepare(sql).get(...(values as never[])) as T) ?? null);
    },
    async all<T>() {
      return { results: raw.prepare(sql).all(...(values as never[])) as T[] };
    },
    async run() {
      return raw.prepare(sql).run(...(values as never[]));
    },
    exec() {
      return raw.prepare(sql).run(...(values as never[]));
    },
  });
  return {
    raw,
    prepare: (sql) => prepare(sql),
    async batch(statements) {
      raw.exec("BEGIN");
      try {
        const out = statements.map((s) => (s as ReturnType<typeof prepare>).exec());
        raw.exec("COMMIT");
        return out;
      } catch (error) {
        raw.exec("ROLLBACK");
        throw error;
      }
    },
  };
}

/** Formats an instant as java.time would: local wall time plus offset (e.g. 2026-03-08T00:00:00-06:00). */
export function localIso(ms: number, tz: string): string {
  const c = wallClock(ms, tz);
  const sign = c.offsetMinutes < 0 ? "-" : "+";
  const abs = Math.abs(c.offsetMinutes);
  const off = c.offsetMinutes === 0 ? "Z" : `${sign}${String(Math.floor(abs / 60)).padStart(2, "0")}:${String(abs % 60).padStart(2, "0")}`;
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${c.date}T${pad(c.hour)}:${pad(c.minute)}:${pad(c.second)}${off}`;
}

/** First instant of a local date, found by search (robust to DST). */
export function startOfLocalDay(date: string, tz: string): number {
  const [y, m, d] = date.split("-").map(Number);
  let ms = Date.UTC(y, m - 1, d) - 14 * 3_600_000;
  while (wallClock(ms, tz).date < date) ms += 15 * 60_000;
  return ms;
}

/** First instant at or after `hour`:00 local on `date` (java.time shifts forward across DST gaps). */
export function localTime(date: string, hour: number, tz: string): number {
  let ms = startOfLocalDay(date, tz);
  while (wallClock(ms, tz).hour < hour) ms += 15 * 60_000;
  return ms;
}

type DayValues = Partial<Record<string, number>>;

export function buildExport(opts: {
  tz?: string;
  rangeStart: string;
  rangeEnd: string;
  generatedAt: string;
  categories?: string[];
  values?: Record<string, DayValues>;
}): Record<string, unknown> {
  const tz = opts.tz ?? "America/Chicago";
  const categories = opts.categories ?? ["steps", "sleepMinutes", "heartRateAvgBpm", "weightKg", "distanceMeters", "activeCaloriesKcal"];
  const days = [];
  for (let date = opts.rangeStart; date <= opts.rangeEnd; date = addDays(date, 1)) {
    const day: Record<string, unknown> = {
      date,
      start: localIso(startOfLocalDay(date, tz), tz),
      end: localIso(startOfLocalDay(addDays(date, 1), tz), tz),
    };
    if (categories.includes("sleepMinutes")) {
      day.sleepWindowStart = localIso(localTime(addDays(date, -1), 18, tz), tz);
      day.sleepWindowEnd = localIso(localTime(date, 18, tz), tz);
    }
    Object.assign(day, opts.values?.[date] ?? {});
    days.push(day);
  }
  return {
    schemaVersion: 1,
    source: "android-health-connect",
    generatedAt: opts.generatedAt,
    timezone: tz,
    rangeStart: opts.rangeStart,
    rangeEnd: opts.rangeEnd,
    categories,
    days,
  };
}
