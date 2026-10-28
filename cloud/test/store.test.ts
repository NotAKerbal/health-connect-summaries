import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { loadConnectionStatus, loadDailyRows, summarize } from "../lib/health/query.ts";
import {
  StaleImportError,
  deleteAllOwnerData,
  deleteImport,
  getSharing,
  importSnapshot,
  listImports,
  setSharing,
} from "../lib/health/store.ts";
import { sha256Hex, validateExport, type ValidExport } from "../lib/health/validate.ts";
import { buildExport, createTestDb } from "./helpers.ts";

const NOW = Date.parse("2026-10-27T20:00:00Z");

function valid(e: Record<string, unknown>): ValidExport {
  const r = validateExport(e, NOW);
  assert.ok(r.ok, JSON.stringify(!r.ok && r.issues));
  return (r as { ok: true; value: ValidExport }).value;
}

async function load(db: ReturnType<typeof createTestDb>, owner: string, e: Record<string, unknown>, nowMs = NOW) {
  const v = valid(e);
  return importSnapshot(db, owner, v, { sha256: await sha256Hex(JSON.stringify(v)), payloadBytes: 1000, nowMs });
}

describe("importSnapshot", () => {
  it("stores owner-scoped rows and keeps owners isolated", async () => {
    const db = createTestDb();
    await load(db, "alice", buildExport({ rangeStart: "2026-10-25", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00", values: { "2026-10-25": { steps: 5000 } } }));
    const alice = await loadDailyRows(db, "alice", "2026-10-25", "2026-10-27");
    const bob = await loadDailyRows(db, "bob", "2026-10-25", "2026-10-27");
    assert.equal(alice[0].metrics.steps?.value, 5000);
    assert.equal(alice[1].metrics.steps?.status, "no_data");
    assert.ok(bob.every((r) => !r.covered && r.metrics.steps?.status === "not_imported"));
    assert.equal((await listImports(db, "bob")).length, 0);
  });

  it("is idempotent for duplicate uploads", async () => {
    const db = createTestDb();
    const e = buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00" });
    const first = await load(db, "alice", e);
    const second = await load(db, "alice", e);
    assert.equal(first.status, "imported");
    assert.equal(second.status, "duplicate");
    assert.equal(second.importId, first.importId);
    assert.equal((await listImports(db, "alice")).length, 1);
  });

  it("replaces the whole range so stale or revoked data is not retained", async () => {
    const db = createTestDb();
    await load(db, "alice", buildExport({
      rangeStart: "2026-10-20", rangeEnd: "2026-10-26", generatedAt: "2026-10-26T12:00:00-05:00",
      values: { "2026-10-21": { steps: 9000, heartRateAvgBpm: 70 }, "2026-10-25": { steps: 100 } },
    }));
    // Newer export: heart rate no longer granted, and the 10-25 steps were deleted on the phone.
    await load(db, "alice", buildExport({
      rangeStart: "2026-10-21", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00",
      categories: ["steps"], values: { "2026-10-21": { steps: 9100 } },
    }));
    const rows = await loadDailyRows(db, "alice", "2026-10-20", "2026-10-27");
    assert.equal(rows[0].metrics.heartRateAvgBpm?.status, "no_data", "10-20 is outside the new range and kept from the old export");
    assert.equal(rows[1].metrics.steps?.value, 9100);
    assert.equal(rows[1].metrics.heartRateAvgBpm?.status, "not_exported");
    assert.equal(rows[5].metrics.steps?.status, "no_data");
  });

  it("rejects an older export that would overwrite newer data", async () => {
    const db = createTestDb();
    await load(db, "alice", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00" }));
    await assert.rejects(
      load(db, "alice", buildExport({ rangeStart: "2026-10-20", rangeEnd: "2026-10-26", generatedAt: "2026-10-26T12:00:00-05:00" })),
      StaleImportError,
    );
  });

  it("rolls back the whole batch on failure", async () => {
    const db = createTestDb();
    await load(db, "alice", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00", values: { "2026-10-26": { steps: 1 } } }));
    db.raw.exec("CREATE TRIGGER fail_insert BEFORE INSERT ON health_days WHEN NEW.steps = 777 BEGIN SELECT RAISE(ABORT, 'boom'); END");
    await assert.rejects(load(db, "alice", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T13:00:00-05:00", values: { "2026-10-27": { steps: 777 } } })));
    const rows = await loadDailyRows(db, "alice", "2026-10-26", "2026-10-27");
    assert.equal(rows[0].metrics.steps?.value, 1, "previous snapshot intact");
    assert.equal((await listImports(db, "alice")).length, 1);
  });

  it("rejects an older upload when a newer upload commits after the freshness precheck", async () => {
    const db = createTestDb();
    const older = valid(buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27",
      generatedAt: "2026-10-27T11:00:00-05:00", categories: ["steps"], values: { "2026-10-26": { steps: 100 } } }));
    const racingDb = {
      ...db,
      async batch(statements: Parameters<typeof db.batch>[0]) {
        await load(db, "alice", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27",
          generatedAt: "2026-10-27T12:00:00-05:00", categories: ["steps"], values: { "2026-10-26": { steps: 9000 } } }));
        return db.batch(statements);
      },
    };
    await assert.rejects(importSnapshot(racingDb, "alice", older, {
      sha256: await sha256Hex(JSON.stringify(older)), payloadBytes: 1000, nowMs: NOW, enableSharing: true,
    }), StaleImportError);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-26", "2026-10-27"))[0].metrics.steps?.value, 9000);
    assert.equal((await listImports(db, "alice")).length, 1);
    assert.equal((await getSharing(db, "alice")).enabled, false, "a rejected upload cannot enable access");
  });

  it("commits import and consent together, rolling back the data if consent cannot be saved", async () => {
    const db = createTestDb();
    const data = valid(buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27",
      generatedAt: "2026-10-27T12:00:00-05:00", categories: ["steps"], values: { "2026-10-26": { steps: 100 } } }));
    const meta = { sha256: await sha256Hex(JSON.stringify(data)), payloadBytes: 1000, nowMs: NOW, enableSharing: true };
    db.raw.exec("CREATE TRIGGER fail_sharing BEFORE INSERT ON owner_settings BEGIN SELECT RAISE(ABORT, 'boom'); END");
    await assert.rejects(importSnapshot(db, "alice", data, meta));
    assert.equal((await listImports(db, "alice")).length, 0);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-26", "2026-10-27"))[0].covered, false);
    assert.equal((await getSharing(db, "alice")).enabled, false);
    db.raw.exec("DROP TRIGGER fail_sharing");
    await importSnapshot(db, "alice", data, meta);
    assert.equal((await getSharing(db, "alice")).enabled, true);
    assert.equal((await listImports(db, "alice")).length, 1);
  });

  it("deletes a single import or all data, scoped to the owner", async () => {
    const db = createTestDb();
    const a = await load(db, "alice", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00" }));
    await load(db, "bob", buildExport({ rangeStart: "2026-10-26", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T12:00:00-05:00" }));
    assert.equal(await deleteImport(db, "bob", a.importId), false, "bob cannot delete alice's import");
    assert.equal(await deleteImport(db, "alice", a.importId), true);
    assert.equal((await loadDailyRows(db, "alice", "2026-10-26", "2026-10-27")).filter((r) => r.covered).length, 0);
    await setSharing(db, "bob", true, NOW);
    await deleteAllOwnerData(db, "bob");
    assert.equal((await listImports(db, "bob")).length, 0);
    assert.equal((await getSharing(db, "bob")).enabled, false);
  });

  it("bounds import history", async () => {
    const db = createTestDb();
    for (let i = 0; i < 25; i++) {
      const generatedAt = new Date(Date.parse("2026-10-27T10:00:00Z") + i * 60_000).toISOString();
      await load(db, "alice", buildExport({ rangeStart: "2026-10-27", rangeEnd: "2026-10-27", generatedAt }), NOW + i);
    }
    assert.ok((await listImports(db, "alice", 100)).length <= 20);
  });
});

describe("summaries and status", () => {
  it("excludes partial days, reports gaps, and computes a descriptive trend", async () => {
    const db = createTestDb();
    const values: Record<string, Record<string, number>> = {};
    const steps = [4000, 4200, 4100, 6000, 6100, 6300, 1200];
    ["2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25", "2026-10-26", "2026-10-27"].forEach((d, i) => (values[d] = { steps: steps[i] }));
    delete values["2026-10-23"];
    await load(db, "alice", buildExport({ rangeStart: "2026-10-21", rangeEnd: "2026-10-27", generatedAt: "2026-10-27T14:00:00-05:00", categories: ["steps"], values }));
    const rows = await loadDailyRows(db, "alice", "2026-10-20", "2026-10-27");
    assert.equal(rows.at(-1)?.partialDay, true);
    const s = summarize(rows, "steps");
    assert.equal(s.daysWithData, 5);
    assert.equal(s.partialDaysExcluded, 1);
    assert.equal(s.daysNoData, 1);
    assert.equal(s.daysNotImported, 1);
    assert.equal(s.latest?.date, "2026-10-26");
    assert.equal(s.trend.status, "computed");
    if (s.trend.status === "computed") assert.equal(s.trend.direction, "up");
    const hr = summarize(rows, "heartRateAvgBpm");
    assert.equal(hr.mean, null);
    assert.equal(hr.daysNotExported, 7);

    const status = await loadConnectionStatus(db, "alice", NOW);
    assert.equal(status.latestImport?.freshness, "fresh");
    assert.equal((await loadConnectionStatus(db, "alice", NOW + 3 * 86_400_000)).latestImport?.freshness, "stale");
  });
});
