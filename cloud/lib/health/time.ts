// Date/time helpers that do not depend on the host time zone.

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;
// RFC 3339 / ISO 8601 instant with an explicit offset. Seconds and fractions are optional
// because java.time may omit zero seconds.
const INSTANT_RE =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:\d{2})$/;
const TIMEZONE_RE = /^(?:UTC|[A-Za-z][A-Za-z0-9_+-]*(?:\/[A-Za-z0-9_+-]+){1,2})$/;

const DAY_MS = 86_400_000;

export function isValidDate(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const match = DATE_RE.exec(value);
  if (!match) return false;
  const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
  if (y < 2000 || y > 2100) return false;
  const date = new Date(Date.UTC(y, m - 1, d));
  return (
    date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
  );
}

export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d) + days * DAY_MS).toISOString().slice(0, 10);
}

/** Inclusive number of calendar days from `start` to `end`. */
export function daySpan(start: string, end: string): number {
  const [ys, ms, ds] = start.split("-").map(Number);
  const [ye, me, de] = end.split("-").map(Number);
  return Math.round((Date.UTC(ye, me - 1, de) - Date.UTC(ys, ms - 1, ds)) / DAY_MS) + 1;
}

export function datesInRange(start: string, end: string): string[] {
  const out: string[] = [];
  for (let date = start; date <= end; date = addDays(date, 1)) out.push(date);
  return out;
}

export type ParsedInstant = { ms: number; offsetMinutes: number | null };

/** Parses an ISO instant with explicit offset. Returns null for anything malformed. */
export function parseInstant(value: unknown): ParsedInstant | null {
  if (typeof value !== "string" || value.length > 40) return null;
  const match = INSTANT_RE.exec(value);
  if (!match) return null;
  const [y, mo, d, h, mi] = match.slice(1, 6).map(Number);
  const s = match[6] ? Number(match[6]) : 0;
  const ms = match[7] ? Number(match[7].slice(0, 3).padEnd(3, "0")) : 0;
  if (!isValidDate(`${match[1]}-${match[2]}-${match[3]}`)) return null;
  if (h > 23 || mi > 59 || s > 59) return null;
  let offsetMinutes: number | null = null;
  if (match[8] === "Z") {
    offsetMinutes = 0;
  } else {
    const sign = match[8][0] === "-" ? -1 : 1;
    const oh = Number(match[8].slice(1, 3));
    const om = Number(match[8].slice(4, 6));
    if (oh > 18 || om > 59) return null;
    offsetMinutes = sign * (oh * 60 + om);
  }
  const local = Date.UTC(y, mo - 1, d, h, mi, s, ms);
  return { ms: local - offsetMinutes * 60_000, offsetMinutes };
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function wallClockFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = formatterCache.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    if (formatterCache.size > 64) formatterCache.clear();
    formatterCache.set(timeZone, formatter);
  }
  return formatter;
}

export function isValidTimeZone(value: unknown): value is string {
  if (typeof value !== "string" || value.length > 64 || !TIMEZONE_RE.test(value)) return false;
  try {
    wallClockFormatter(value);
    return true;
  } catch {
    return false;
  }
}

export type WallClock = {
  date: string;
  hour: number;
  minute: number;
  second: number;
  /** Offset from UTC at this instant, in minutes. */
  offsetMinutes: number;
};

/** Local wall-clock time of an instant in an IANA zone. */
export function wallClock(ms: number, timeZone: string): WallClock {
  const parts = wallClockFormatter(timeZone).formatToParts(new Date(ms));
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const year = get("year");
  const month = get("month");
  const day = get("day");
  const hour = get("hour");
  const minute = get("minute");
  const second = get("second");
  const asUtc = Date.UTC(year, month - 1, day, hour, minute, second);
  const wholeSecondMs = Math.floor(ms / 1000) * 1000;
  return {
    date: `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`,
    hour,
    minute,
    second,
    offsetMinutes: Math.round((asUtc - wholeSecondMs) / 60_000),
  };
}

/** True when `ms` is the first instant of local `date` in `timeZone` (handles DST gaps at midnight). */
export function isStartOfLocalDay(ms: number, date: string, timeZone: string): boolean {
  return wallClock(ms, timeZone).date === date && wallClock(ms - 1, timeZone).date !== date;
}

export function hoursBetween(fromMs: number, toMs: number): number {
  return (toMs - fromMs) / 3_600_000;
}
