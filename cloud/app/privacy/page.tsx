import Link from "next/link";
import { METRIC_KEYS, METRICS } from "@/lib/health/metrics";

export const metadata = { title: "Privacy and limitations · Health Connect Summaries" };

export default function Privacy() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <main className="mx-auto max-w-3xl space-y-8 px-4 py-10 text-sm leading-relaxed sm:px-6">
        <div>
          <Link href="/" className="text-muted-foreground hover:text-foreground">
            ← Back to dashboard
          </Link>
          <h1 className="mt-4 text-2xl font-semibold tracking-tight">Privacy and limitations</h1>
          <p className="mt-2 text-muted-foreground">
            Applies to the Health Connect Export Android app, this private site, and the private ChatGPT plugin that reads it.
            Last updated 27 October 2026.
          </p>
        </div>

        <section className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-4 text-amber-950">
          <h2 className="font-semibold">This is not ChatGPT Health</h2>
          <p>
            This is a separate, personal tool. It does not connect to, read from, or write to ChatGPT Health, and the
            protections and controls specific to ChatGPT Health do not apply to it. There is no public API for importing
            data into ChatGPT Health, so this tool keeps your summaries on its own private site instead.
          </p>
        </section>

        <section className="space-y-2">
          <h2 className="text-base font-semibold">What is collected</h2>
          <p>Only daily aggregates for the categories you select and grant in Health Connect:</p>
          <ul className="list-disc space-y-1 pl-5">
            {METRIC_KEYS.map((k) => (
              <li key={k}>
                <strong>{METRICS[k].label}</strong> ({METRICS[k].unit}). {METRICS[k].aggregation}
              </li>
            ))}
          </ul>
          <p>
            For each day a snapshot also records the local day&apos;s start and end time, your time zone, when the snapshot was
            generated, and which categories were included. Individual samples, sessions, sleep stages, and source apps are never
            sent or stored. The site also keeps the paired phone&apos;s display name (for example its model), when it was
            paired, and when it last synced. The app only requests read permissions; it never writes to Health Connect.
          </p>
        </section>

        <section className="space-y-2">
          <h2 className="text-base font-semibold">How data moves</h2>
          <ol className="list-decimal space-y-1 pl-5">
            <li>
              You pair the Android app with this site once by copying a one-time connection code from this dashboard into the
              app. The code expires after 5 minutes and works once. It contains this private site&apos;s service access key, which
              lets the phone reach the site without a browser sign-in. The app exchanges the code for its own device credential.
              The app stores both in Android Keystore-encrypted storage and excludes them from backups and device transfer.
            </li>
            <li>
              With <em>Automatic sync</em> turned on, and Android&apos;s background health-read permission granted, Android
              periodically runs a background job, usually no more than every 15 minutes and often later. The job reads daily
              aggregates for the last 30 days from Health Connect and sends them directly to this site over HTTPS. No file is
              created. You can also tap <em>Sync now</em>. The app has no analytics and does not log health values.
            </li>
            <li>
              The site checks each snapshot strictly and stores the daily values in a database tied to your Sites account ID.
              The device credential can only upload snapshots for your account, disconnect itself, and read its own
              connection status: whether it is still connected, its last successful sync time, and whether ChatGPT access is
              on. It cannot read health data back, turn on ChatGPT access, or use the dashboard. Other signed-in users cannot
              read your records.
            </li>
            <li>
              Optionally, the app can still create a JSON export file that you share or save and then upload here in a browser.
              Wherever you send or save that file (for example a cloud drive) then holds a copy.
            </li>
            <li>
              If you turn on ChatGPT plugin access, the plugin&apos;s read-only tools can return your stored summaries to
              ChatGPT when you ask about them. Pairing a phone does not turn this on. Data returned to a conversation becomes
              part of that ChatGPT conversation and is handled under your ChatGPT data settings and OpenAI&apos;s privacy policy.
            </li>
          </ol>
        </section>

        <section className="space-y-2">
          <h2 className="text-base font-semibold">Your controls</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>Choose which categories to sync, and revoke any of them, or background access, in Health Connect at any time.
              Automatic sync then pauses rather than sending an incomplete snapshot.</li>
            <li>Turn automatic sync off in the app, or tap <em>Disconnect</em> there. That stops scheduled jobs, revokes the
              phone&apos;s credential on the site, and erases it from the phone.</li>
            <li>Disconnect the phone from this dashboard. Its credential stops working immediately.</li>
            <li>Turn ChatGPT plugin access off on the dashboard; the tools then return no health values.</li>
            <li>Delete a single import, or delete all stored data. Deleting everything also turns ChatGPT access off and
              disconnects the phone, so later syncs cannot add the data back.</li>
            <li>Delete exports stored on your phone from the Android app.</li>
          </ul>
        </section>

        <section className="space-y-2">
          <h2 className="text-base font-semibold">Use and retention</h2>
          <p>
            Data is used only to show you your summaries and to answer your own questions through the plugin. It is not sold,
            used for advertising, or used to build profiles. Stored summaries remain until you delete them. Each new snapshot
            replaces the stored values for its date range. Up to 20 superseded import records, and the last few disconnected
            phones, are kept for your history.
          </p>
        </section>

        <section className="space-y-2">
          <h2 className="text-base font-semibold">Limitations</h2>
          <ul className="list-disc space-y-1 pl-5">
            <li>
              Automatic sync is best effort, not real time. Android may delay it for battery saver, Doze, app standby, or no
              network connection. It also needs the background health-read permission, which only some phones and Health Connect
              versions support. Data is only as current as the latest successful sync; check the freshness label.
            </li>
            <li>
              The connection code and the phone&apos;s stored credentials include this site&apos;s service access key. Anyone who
              obtains that key can reach the site&apos;s pairing, upload, and connection-status endpoints, but cannot upload or read
              status without a valid pairing or device credential, and cannot read any health data without signing in as you.
            </li>
            <li>
              Values are whatever Health Connect aggregates from the apps and devices you have connected. Accuracy depends on
              those sources. Missing days are shown as gaps, not zeros.
            </li>
            <li>
              Health Connect normally lets a newly installed app read up to 30 days before you first granted permission; the
              app does not request access to older history.
            </li>
            <li>
              Access to this site relies on Sites sign-in. The site adds no encryption beyond what its hosting platform
              provides, and the site owner&apos;s hosting account can administer the database.
            </li>
            <li>This tool is not a medical device and does not diagnose, treat, or give medical advice.</li>
          </ul>
        </section>
      </main>
    </div>
  );
}
