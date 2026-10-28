import { METRICS, type MetricKey } from "./metrics.ts";

export function formatMetric(key: MetricKey, value: number): string {
  const def = METRICS[key];
  switch (key) {
    case "sleepMinutes": {
      const h = Math.floor(value / 60);
      const m = Math.round(value - h * 60);
      return `${h} h ${String(m).padStart(2, "0")} min`;
    }
    case "distanceMeters":
      return value >= 1000 ? `${(value / 1000).toFixed(2)} km` : `${Math.round(value)} m`;
    default:
      return `${value.toLocaleString("en-US", { maximumFractionDigits: def.decimals })} ${def.unit}`;
  }
}

/** Formats an ISO instant in the export's own time zone, so server and browser agree. */
export function formatInstant(iso: string, timeZone: string | null): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  try {
    return new Intl.DateTimeFormat("en-US", {
      timeZone: timeZone ?? "UTC",
      year: "numeric",
      month: "short",
      day: "numeric",
      hour: "numeric",
      minute: "2-digit",
      timeZoneName: "short",
    }).format(date);
  } catch {
    return date.toISOString();
  }
}

export function formatDate(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  return new Intl.DateTimeFormat("en-US", { timeZone: "UTC", weekday: "short", month: "short", day: "numeric" }).format(
    new Date(Date.UTC(y, m - 1, d)),
  );
}

export function formatAge(hours: number): string {
  if (hours < 1) return "less than an hour ago";
  if (hours < 48) return `${Math.round(hours)} h ago`;
  return `${Math.round(hours / 24)} days ago`;
}
