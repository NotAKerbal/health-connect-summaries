import Link from "next/link";
import type { ReactNode } from "react";
import { chatGPTSignOutPath, requireChatGPTUser } from "./chatgpt-auth";
import { ConfirmDelete } from "@/components/health/confirm-delete";
import { PairingCard } from "@/components/health/pairing-card";
import { SharingToggle } from "@/components/health/sharing-toggle";
import { UploadCard } from "@/components/health/upload-card";
import { Badge } from "@/components/ui/badge";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { getHealthDb } from "@/lib/health/db";
import { formatAge, formatDate, formatInstant, formatMetric } from "@/lib/health/format";
import { METRIC_KEYS, METRICS } from "@/lib/health/metrics";
import { loadConnectionStatus, loadDailyRows, type ConnectionStatus, type DailyRow, type SyncSummary } from "@/lib/health/query";
import { syncConfigured } from "@/lib/health/runtime";
import { listImports } from "@/lib/health/store";
import { addDays } from "@/lib/health/time";

export const dynamic = "force-dynamic";

const TABLE_DAYS = 30;
const APP_VERSION = "0.5.0";

function requestTime(): number {
  return Date.now();
}

const ATTEMPT_PROBLEMS: Record<string, string> = {
  rejected_stale: "The last upload was older than stored data and was ignored.",
  rejected_invalid: "The last upload was rejected as invalid.",
  rejected_too_large: "The last upload was too large.",
};

function syncHeadline(sync: SyncSummary): string {
  switch (sync.mode) {
    case "automatic":
      return "Automatic sync on";
    case "paired_manual":
      return "Automatic sync off";
    case "paired_awaiting_first_sync":
      return "Waiting for first sync";
    default:
      return "Not paired";
  }
}

function ageHours(iso: string, now: number): number {
  return Math.max(0, (now - Date.parse(iso)) / 3_600_000);
}

type Tone = "ok" | "warn" | "info" | "neutral";

/** One status word and at most one short note for the top of the page. */
function overview(status: ConnectionStatus): { label: string; tone: Tone; note: string | null } {
  const device = status.sync.activeDevice;
  if (!device && !status.hasData) return { label: "Not set up", tone: "neutral", note: null };
  if (!device) return { label: "Needs attention", tone: "warn", note: "No phone is connected." };
  if (status.sync.mode === "paired_awaiting_first_sync") return { label: "Waiting", tone: "info", note: "Waiting for the phone’s first sync." };
  const attempt = device.lastAttemptStatus;
  if (attempt && attempt !== "ok") return { label: "Needs attention", tone: "warn", note: ATTEMPT_PROBLEMS[attempt] ?? "The last upload failed." };
  if (status.latestImport?.freshness === "stale") return { label: "Needs attention", tone: "warn", note: "Data is over a day old. Open the app to sync." };
  if (!status.sharing.enabled) return { label: "Needs attention", tone: "warn", note: "ChatGPT access is off." };
  return { label: "Ready", tone: "ok", note: null };
}

const TONE_CLASS: Record<Tone, string> = {
  ok: "border-emerald-300 bg-emerald-50",
  warn: "border-amber-300 bg-amber-50",
  info: "border-sky-300 bg-sky-50",
  neutral: "border-border bg-card",
};

const TONE_SYMBOL: Record<Tone, string> = { ok: "✓", warn: "!", info: "…", neutral: "•" };

/** A native, keyboard- and screen-reader-accessible disclosure, closed by default. */
function Section({ title, hint, children }: { title: string; hint?: string; children: ReactNode }) {
  return (
    <details className="group rounded-xl border bg-card">
      <summary className="flex min-h-12 cursor-pointer list-none items-center gap-2 px-4 py-3 text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2 [&::-webkit-details-marker]:hidden">
        <span aria-hidden className="inline-block w-3 transition-transform group-open:rotate-90">
          ▸
        </span>
        {title}
        {hint && <span className="ml-auto font-normal text-muted-foreground">{hint}</span>}
      </summary>
      <div className="space-y-4 border-t px-4 py-4 text-sm">{children}</div>
    </details>
  );
}

export default async function Dashboard() {
  const user = await requireChatGPTUser("/");
  const db = getHealthDb();
  // Request time for freshness labels; this page is dynamic and rendered per request.
  const now = requestTime();
  const [status, imports] = await Promise.all([loadConnectionStatus(db, user.userId, now), listImports(db, user.userId)]);
  const lastDate = status.coverage.lastDate;
  const firstDate = status.coverage.firstDate;
  const tableStart =
    lastDate && firstDate ? [addDays(lastDate, -(TABLE_DAYS - 1)), firstDate].sort().at(-1)! : null;
  const rows: DailyRow[] =
    lastDate && tableStart ? (await loadDailyRows(db, user.userId, tableStart, lastDate)).reverse() : [];
  const latest = status.latestImport;
  const categoryLabels = Object.fromEntries(METRIC_KEYS.map((k) => [k, METRICS[k].shortLabel]));
  const sync = status.sync;
  const device = sync.activeDevice;
  const tz = latest?.timezone ?? null;
  const pastDevices = sync.recentDevices.filter((d) => !d.active);
  const top = overview(status);
  const lastSync = device?.lastSyncAt ?? latest?.importedAt ?? null;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b bg-white/80">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6">
          <div className="flex items-center gap-3">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/favicon.svg" alt="" className="size-7" />
            <h1 className="text-base font-semibold tracking-tight">Health Connect Summaries</h1>
          </div>
          <nav className="flex items-center gap-4 text-sm">
            <Link href="/privacy" className="text-muted-foreground hover:text-foreground">
              Privacy
            </Link>
            <a href={chatGPTSignOutPath("/")} target="_top" className="text-muted-foreground hover:text-foreground">
              Sign out
            </a>
          </nav>
        </div>
      </header>

      <main className="mx-auto max-w-3xl space-y-4 px-4 py-6 sm:px-6">
        <section aria-labelledby="status-title" className={`space-y-1 rounded-xl border-2 p-5 ${TONE_CLASS[top.tone]}`}>
          <h2 id="status-title" className="text-2xl font-semibold">
            <span aria-hidden>{TONE_SYMBOL[top.tone]} </span>
            {top.label}
          </h2>
          <p className="text-sm text-muted-foreground">
            Last sync: {lastSync ? `${formatInstant(lastSync, tz)} (${formatAge(ageHours(lastSync, now))})` : "not yet"}
          </p>
          {top.note && <p className="text-sm">{top.note}</p>}
          {/* The Android app's "Turn on ChatGPT access" button opens /#chatgpt-access; keep this always visible. */}
          <div id="chatgpt-access" className="mt-3 scroll-mt-6 rounded-lg border bg-white px-3 py-3 target:ring-2 target:ring-emerald-700">
            <SharingToggle enabled={status.sharing.enabled} />
          </div>
        </section>

        {!device && (
          <section aria-labelledby="setup-title" className="space-y-3 rounded-xl border bg-card p-5 text-sm">
            <h2 id="setup-title" className="text-base font-semibold">
              Set up automatic sync
            </h2>
            <ol className="list-decimal space-y-3 pl-5">
              <li>
                Install the{" "}
                <a href="/downloads/health-connect-export.apk" download className="font-medium underline">
                  Android app
                </a>{" "}
                on your phone.
              </li>
              <li>
                Create a code and paste it into the app.
                <div className="mt-2">
                  <PairingCard paired={false} configured={syncConfigured()} />
                </div>
              </li>
              <li>In the app, turn on automatic sync.</li>
            </ol>
          </section>
        )}

        {status.hasData && (
          <Section
            title="Daily data"
            hint={`${status.coverage.daysStored} days${latest ? ` · ${latest.freshness === "fresh" ? "Fresh" : "Stale"}` : ""}`}
          >
            <p className="text-muted-foreground">
              {status.coverage.firstDate} to {status.coverage.lastDate}.{" "}
              {status.categoriesInLatestImport.map((k) => METRICS[k].shortLabel).join(", ")}.
            </p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Date</TableHead>
                  {METRIC_KEYS.map((k) => (
                    <TableHead key={k} className="text-right" title={METRICS[k].aggregation}>
                      {METRICS[k].shortLabel}
                    </TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((row) => (
                  <TableRow key={row.date}>
                    <TableCell className="whitespace-nowrap">
                      {formatDate(row.date)}
                      {row.partialDay && (
                        <Badge variant="outline" className="ml-2 text-[10px]" title="Exported before the day ended">
                          partial
                        </Badge>
                      )}
                    </TableCell>
                    {METRIC_KEYS.map((k) => {
                      const cell = row.metrics[k];
                      return (
                        <TableCell key={k} className="whitespace-nowrap text-right tabular-nums">
                          {cell?.status === "value" && cell.value !== null ? (
                            formatMetric(k, cell.value)
                          ) : cell?.status === "not_exported" ? (
                            <span className="text-xs text-muted-foreground">not exported</span>
                          ) : cell?.status === "not_imported" ? (
                            <span className="text-xs text-muted-foreground">not uploaded</span>
                          ) : (
                            <span className="text-muted-foreground" aria-label="no data">
                              —
                            </span>
                          )}
                        </TableCell>
                      );
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <p className="text-xs text-muted-foreground">A dash means no records that day.</p>
          </Section>
        )}

        {(device || pastDevices.length > 0) && (
          <Section title="Phone" hint={device ? syncHeadline(sync) : undefined}>
            {device && (
              <div className="space-y-2">
                <p className="font-medium">{device.name}</p>
                <p className="text-muted-foreground">
                  Paired {formatInstant(device.pairedAt, tz)}. Last sync{" "}
                  {device.lastSyncAt ? formatInstant(device.lastSyncAt, tz) : "none yet"}
                  {device.lastTrigger ? ` (${device.lastTrigger === "scheduled" ? "automatic" : "Sync now"})` : ""}.
                </p>
                <ConfirmDelete
                  url={`/api/sync/devices/${device.id}`}
                  body="{}"
                  size="sm"
                  triggerLabel="Disconnect phone"
                  title="Disconnect this phone?"
                  description="The phone's credential stops working immediately, including for any sync that is already scheduled. Data already stored here is kept. To sync again, create a new connection code and pair the phone again."
                  confirmLabel="Disconnect"
                  busyLabel="Disconnecting…"
                />
                <div className="border-t pt-3">
                  <PairingCard paired configured={syncConfigured()} />
                </div>
              </div>
            )}
            {pastDevices.length > 0 && (
              <div>
                <p className="mb-1 text-xs font-medium text-muted-foreground">Previously paired</p>
                <ul className="space-y-1 text-xs text-muted-foreground">
                  {pastDevices.map((d) => (
                    <li key={d.id}>
                      {d.name}: paired {formatInstant(d.pairedAt, tz)}, disconnected{" "}
                      {d.revokedAt ? formatInstant(d.revokedAt, tz) : ""}
                      {d.revokedReason === "replaced" ? " (replaced)" : ""}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </Section>
        )}

        {imports.length > 0 && (
          <Section title="Import history" hint={String(imports.length)}>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Received</TableHead>
                  <TableHead>Source</TableHead>
                  <TableHead>Dates</TableHead>
                  <TableHead className="text-right">Days in use</TableHead>
                  <TableHead className="sr-only">Actions</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {imports.map((imp) => (
                  <TableRow key={imp.id}>
                    <TableCell className="whitespace-nowrap">{formatInstant(imp.importedAt, imp.timezone)}</TableCell>
                    <TableCell className="whitespace-nowrap">{imp.uploadMethod === "device_sync" ? "Phone sync" : "Manual upload"}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {imp.rangeStart} to {imp.rangeEnd}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {imp.activeDays} / {imp.dayCount}
                    </TableCell>
                    <TableCell className="text-right">
                      <ConfirmDelete
                        url={`/api/imports/${imp.id}`}
                        body="{}"
                        size="sm"
                        triggerLabel="Delete"
                        title="Delete this import?"
                        description={`This permanently removes the ${imp.activeDays} stored days that come from the snapshot generated ${formatInstant(imp.generatedAt, imp.timezone)}. If a phone is still paired, its next sync uploads the current 30 days again; disconnect the phone or use Delete everything to stop that.`}
                        confirmLabel="Delete import"
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Section>
        )}

        <Section title="More">
          <p className="text-muted-foreground">
            This private site is separate from ChatGPT Health. Background sync runs about every 15 minutes or later, when
            Android allows.
          </p>
          <details className="rounded-lg border">
            <summary className="flex min-h-12 cursor-pointer items-center px-3 py-2 font-medium">Manual upload</summary>
            <div className="border-t px-3 py-3">
              <UploadCard sharingEnabled={status.sharing.enabled} categoryLabels={categoryLabels} />
            </div>
          </details>
          <div className="space-y-2 rounded-lg border border-red-200 p-3">
            <p className="font-medium">Delete everything</p>
            <p className="text-muted-foreground">
              Deletes all stored data, turns ChatGPT access off, and disconnects your phone.
            </p>
            <ConfirmDelete
              url="/api/data"
              body={JSON.stringify({ confirm: "DELETE_ALL" })}
              triggerLabel="Delete all my data"
              title="Delete all Health Connect data on this site?"
              description="Every stored daily summary and import record will be permanently deleted, ChatGPT access will be turned off, and your paired phone will be disconnected. This cannot be undone."
              confirmLabel="Delete everything"
            />
          </div>
        </Section>

        <footer className="flex flex-wrap gap-x-4 gap-y-1 pb-6 text-xs text-muted-foreground">
          <a href="/downloads/health-connect-export.apk" download className="underline">
            Android app {APP_VERSION}
          </a>
          <a href="/downloads/health-connect-source.zip" download className="underline">
            Source
          </a>
          <Link href="/privacy" className="underline">
            Privacy
          </Link>
          <span>Not medical advice.</span>
        </footer>
      </main>
    </div>
  );
}
