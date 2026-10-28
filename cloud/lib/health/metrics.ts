// Metric catalogue shared by validation, storage, the dashboard, and MCP tools.
// Keys match the Android export schema (schemaVersion 1).

export const METRIC_KEYS = [
  "steps",
  "sleepMinutes",
  "heartRateAvgBpm",
  "weightKg",
  "distanceMeters",
  "activeCaloriesKcal",
] as const;

export type MetricKey = (typeof METRIC_KEYS)[number];

export type MetricDefinition = {
  key: MetricKey;
  label: string;
  shortLabel: string;
  unit: string;
  column: string;
  integer: boolean;
  min: number;
  max: number;
  /** Lower bound is exclusive (a zero value is not physiologically meaningful). */
  minExclusive: boolean;
  /** How the Android app computes the daily value with the Health Connect aggregate API. */
  aggregation: string;
  /** Number of decimals used for display and summaries. */
  decimals: number;
};

export const METRICS: Record<MetricKey, MetricDefinition> = {
  steps: {
    key: "steps",
    label: "Steps (daily total)",
    shortLabel: "Steps",
    unit: "steps",
    column: "steps",
    integer: true,
    min: 0,
    max: 200_000,
    minExclusive: false,
    aggregation:
      "StepsRecord.COUNT_TOTAL over the local calendar day (midnight to midnight); Health Connect de-duplicates overlapping sources.",
    decimals: 0,
  },
  sleepMinutes: {
    key: "sleepMinutes",
    label: "Sleep (minutes, night ending this day)",
    shortLabel: "Sleep",
    unit: "minutes",
    column: "sleep_minutes",
    integer: false,
    min: 0,
    max: 1_500,
    minExclusive: false,
    aggregation:
      "SleepSessionRecord.SLEEP_DURATION_TOTAL over the sleep window 18:00 the previous local day to 18:00 this day, so an overnight sleep is attributed to the wake-up date.",
    decimals: 0,
  },
  heartRateAvgBpm: {
    key: "heartRateAvgBpm",
    label: "Heart rate (daily average of samples)",
    shortLabel: "Avg heart rate",
    unit: "bpm",
    column: "heart_rate_avg_bpm",
    integer: false,
    min: 20,
    max: 250,
    minExclusive: false,
    aggregation:
      "HeartRateRecord.BPM_AVG over the local calendar day: the mean of all heart-rate samples, not a resting heart rate.",
    decimals: 0,
  },
  weightKg: {
    key: "weightKg",
    label: "Weight (average of that day's entries)",
    shortLabel: "Weight (day avg)",
    unit: "kg",
    column: "weight_kg",
    integer: false,
    min: 1,
    max: 500,
    minExclusive: false,
    aggregation:
      "WeightRecord.WEIGHT_AVG over the local calendar day: the average of the weight entries recorded that day, not the latest entry.",
    decimals: 1,
  },
  distanceMeters: {
    key: "distanceMeters",
    label: "Distance (daily total)",
    shortLabel: "Distance",
    unit: "meters",
    column: "distance_meters",
    integer: false,
    min: 0,
    max: 500_000,
    minExclusive: false,
    aggregation:
      "DistanceRecord.DISTANCE_TOTAL over the local calendar day; Health Connect de-duplicates overlapping sources.",
    decimals: 0,
  },
  activeCaloriesKcal: {
    key: "activeCaloriesKcal",
    label: "Active calories (daily total)",
    shortLabel: "Active energy",
    unit: "kcal",
    column: "active_calories_kcal",
    integer: false,
    min: 0,
    max: 20_000,
    minExclusive: false,
    aggregation:
      "ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL over the local calendar day; excludes basal (resting) energy.",
    decimals: 0,
  },
};

export function isMetricKey(value: unknown): value is MetricKey {
  return typeof value === "string" && (METRIC_KEYS as readonly string[]).includes(value);
}
