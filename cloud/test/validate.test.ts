import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { MAX_EXPORT_BYTES, parseAndValidateExport, validateExport } from "../lib/health/validate.ts";
import { buildExport } from "./helpers.ts";

const NOW = Date.parse("2026-10-27T20:00:00Z");
const base = () =>
  buildExport({
    rangeStart: "2026-10-21",
    rangeEnd: "2026-10-27",
    generatedAt: "2026-10-27T14:30:00-05:00",
    values: {
      "2026-10-21": { steps: 8123, sleepMinutes: 412, heartRateAvgBpm: 71.4, weightKg: 80.25, distanceMeters: 6020.5, activeCaloriesKcal: 410.2 },
      "2026-10-22": { steps: 0 },
    },
  });

function expectIssue(input: unknown, pattern: RegExp) {
  const result = validateExport(input, NOW);
  assert.equal(result.ok, false, "expected validation to fail");
  if (!result.ok) assert.ok(result.issues.some((i) => pattern.test(i)), `no issue matching ${pattern}: ${result.issues.join(" | ")}`);
}

describe("validateExport", () => {
  it("accepts a well-formed export and preserves zero vs absent", () => {
    const result = validateExport(base(), NOW);
    assert.ok(result.ok, JSON.stringify(!result.ok && result.issues));
    if (!result.ok) return;
    assert.equal(result.value.days.length, 7);
    assert.equal(result.value.days[1].metrics.steps, 0);
    assert.equal("steps" in result.value.days[2].metrics, false);
    assert.equal(result.value.generatedAt, "2026-10-27T19:30:00.000Z");
  });

  it("accepts DST transition days (23 h and 25 h) in America/Chicago", () => {
    for (const [start, end] of [["2026-03-05", "2026-03-11"], ["2026-10-29", "2026-11-04"]]) {
      const r = validateExport(buildExport({ rangeStart: start, rangeEnd: end, generatedAt: `${end}T23:00:00-06:00` }), Date.parse("2026-12-01T00:00:00Z"));
      assert.ok(r.ok, JSON.stringify(!r.ok && r.issues));
    }
  });

  it("accepts zones with a non-hour offset and a zone whose midnight is skipped by DST", () => {
    const r1 = validateExport(buildExport({ tz: "Asia/Kolkata", rangeStart: "2026-10-20", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T20:00:00+05:30" }), NOW);
    assert.ok(r1.ok, JSON.stringify(!r1.ok && r1.issues));
    // America/Santiago springs forward at 00:00 -> 01:00 (first Sunday of September).
    const r2 = validateExport(buildExport({ tz: "America/Santiago", rangeStart: "2026-09-04", rangeEnd: "2026-09-08", generatedAt: "2026-09-09T12:00:00-03:00" }), NOW);
    assert.ok(r2.ok, JSON.stringify(!r2.ok && r2.issues));
  });

  it("rejects day boundaries that are not local midnight", () => {
    const e = base() as { days: Record<string, unknown>[] };
    e.days[0].start = "2026-10-21T00:00:00Z";
    expectIssue(e, /days\[0\]\.start/);
  });

  it("rejects wrong schema, source, unknown fields, and nulls", () => {
    expectIssue({ ...base(), schemaVersion: 2 }, /schemaVersion/);
    expectIssue({ ...base(), source: "fitbit" }, /source/);
    expectIssue({ ...base(), ownerId: "someone-else" }, /ownerId: unknown field/);
    const e = base() as { days: Record<string, unknown>[] };
    e.days[3].steps = null;
    expectIssue(e, /days\[3\]\.steps/);
    e.days[3] = { ...e.days[3], rawSamples: [] };
    expectIssue(e, /rawSamples: unknown field/);
  });

  it("rejects non-finite, negative, fractional-steps, and unrealistic values", () => {
    for (const [key, value] of [["steps", -1], ["steps", 12.5], ["steps", 900000], ["heartRateAvgBpm", 0], ["weightKg", 2000], ["sleepMinutes", 2000], ["distanceMeters", -3], ["activeCaloriesKcal", "100"]] as const) {
      const e = base() as { days: Record<string, unknown>[] };
      e.days[4][key] = value;
      expectIssue(e, new RegExp(`days\\[4\\]\\.${key}`));
    }
    expectIssue(JSON.parse(JSON.stringify(base()).replace('"steps":8123', '"steps":1e999')), /steps/);
  });

  it("rejects metrics for categories that were not exported", () => {
    const e = buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T10:00:00-05:00", categories: ["steps"], values: { "2026-10-26": { weightKg: 80 } } });
    expectIssue(e, /weightKg: present but not listed/);
  });

  it("rejects duplicate, missing, or out-of-order dates", () => {
    const e = base() as { days: Record<string, unknown>[] };
    e.days[2] = { ...e.days[1] };
    expectIssue(e, /days\[2\]\.date/);
    const short = base() as { days: unknown[] };
    short.days.pop();
    expectIssue(short, /exactly one entry per date/);
  });

  it("rejects ranges over 30 days, future data, and bad timezones", () => {
    expectIssue(buildExport({ rangeStart: "2026-09-01", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T10:00:00-05:00" }), /at most 30 days/);
    expectIssue(buildExport({ rangeStart: "2026-10-27", rangeEnd: "2026-10-28", generatedAt: "2026-10-27T10:00:00-05:00" }), /rangeEnd/);
    expectIssue({ ...base(), generatedAt: "2026-10-28T23:00:00Z" }, /generatedAt: is in the future/);
    expectIssue({ ...base(), timezone: "Mars/Olympus" }, /timezone/);
    expectIssue({ ...base(), generatedAt: "yesterday" }, /generatedAt/);
    expectIssue({ ...base(), categories: ["steps", "steps"] }, /duplicate/);
    expectIssue({ ...base(), categories: ["bloodGlucose"] }, /unknown metric/);
  });

  it("enforces the byte limit and JSON syntax", () => {
    const big = parseAndValidateExport(" ".repeat(MAX_EXPORT_BYTES + 1), NOW);
    assert.equal(big.ok, false);
    const bad = parseAndValidateExport("{not json", NOW);
    assert.equal(bad.ok, false);
  });
});
