// Verifies the unattended phone sync path over HTTP without writing any health data.
// Credentials are read from stdin (hidden when it is a terminal), kept only in memory, and
// never printed, logged, written to disk, or put in a URL.
//
//   node scripts/sync-check.ts gate  <site-origin>
//     stdin: the Site's platform service-access token.
//     Changes nothing. Confirms the private Site's service-access gate admits the token and the
//     sync routes answer: a random pairing token must get 401 pairing_invalid, and a random
//     device token must get 401 device_revoked from both the upload and status routes, as JSON
//     from this app.
//
//   node scripts/sync-check.ts probe <site-origin> --replace-paired-phone
//     stdin: a connection code created on the dashboard.
//     Pairs a temporary "sync-check" device (this DISCONNECTS the currently paired phone),
//     reads its connection status (metadata only), sends an invalid snapshot that must be
//     rejected with 422 (nothing stored), disconnects the temporary device, and confirms its
//     credential then gets 401 from upload and status. Re-pair the phone afterwards.
//
// Only https origins are accepted, except http://localhost or 127.0.0.1 for local previews.
import { decodeConnectionCode } from "../lib/health/sync.ts";

const TIMEOUT_MS = 15_000;

function fail(message: string): never {
  console.error(`FAIL ${message}`);
  process.exit(1);
}

function parseOrigin(raw: string | undefined): string {
  if (!raw) fail("missing <site-origin>");
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    fail("site origin is not a URL");
  }
  const loopback = url.protocol === "http:" && (url.hostname === "localhost" || url.hostname === "127.0.0.1");
  if (url.protocol !== "https:" && !loopback) fail("site origin must use https");
  return url.origin;
}

/** Reads one secret line. On a terminal, input is not echoed. */
async function readSecret(prompt: string): Promise<string> {
  const stdin = process.stdin;
  if (!stdin.isTTY) {
    const chunks: Buffer[] = [];
    let total = 0;
    for await (const chunk of stdin) {
      total += (chunk as Buffer).length;
      if (total > 16_384) fail("stdin input too large");
      chunks.push(chunk as Buffer);
    }
    return Buffer.concat(chunks).toString("utf8").trim();
  }
  process.stderr.write(`${prompt} (input hidden): `);
  stdin.setRawMode(true);
  stdin.resume();
  stdin.setEncoding("utf8");
  return new Promise((resolve) => {
    let value = "";
    const onData = (data: string) => {
      for (const ch of data) {
        if (ch === "\r" || ch === "\n") {
          stdin.setRawMode(false);
          stdin.pause();
          stdin.off("data", onData);
          process.stderr.write("\n");
          resolve(value.trim());
          return;
        }
        if (ch === "\u0003") {
          stdin.setRawMode(false);
          process.stderr.write("\n");
          process.exit(130);
        }
        if (ch === "\u007f" || ch === "\b") value = value.slice(0, -1);
        else value += ch;
      }
    };
    stdin.on("data", onData);
  });
}

function randomToken(prefix: string): string {
  return prefix + Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString("base64url");
}

type Reply = { status: number; code: string | null; body: Record<string, unknown> | null };

async function call(
  origin: string,
  path: string,
  init: { method: string; serviceToken: string; deviceToken?: string; body?: unknown },
): Promise<Reply> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    accept: "application/json",
    "oai-sites-authorization": `Bearer ${init.serviceToken}`,
  };
  if (init.deviceToken) headers.authorization = `Bearer ${init.deviceToken}`;
  let res: Response;
  try {
    res = await fetch(origin + path, {
      method: init.method,
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      redirect: "manual",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (error) {
    fail(`${init.method} ${path}: network error (${(error as Error).name})`);
  }
  if (res.status >= 300 && res.status < 400) {
    fail(`${init.method} ${path}: redirected (HTTP ${res.status}); the service-access gate did not admit the token`);
  }
  const text = (await res.text()).slice(0, 16_384);
  let body: Record<string, unknown> | null = null;
  try {
    body = JSON.parse(text) as Record<string, unknown>;
  } catch {
    body = null;
  }
  return { status: res.status, code: typeof body?.code === "string" ? body.code : null, body };
}

function expect(label: string, reply: Reply, status: number, code?: string) {
  const ok = reply.status === status && (code === undefined || reply.code === code);
  console.log(`${ok ? "PASS" : "FAIL"} ${label}: HTTP ${reply.status}${reply.code ? ` (${reply.code})` : ""}`);
  if (!ok) process.exitCode = 1;
  return ok;
}

async function gate(origin: string) {
  const serviceToken = await readSecret("Site service-access token");
  if (!serviceToken) fail("no token on stdin");
  expect(
    "service gate admits token; unknown pairing token rejected by app",
    await call(origin, "/api/sync/pair", { method: "POST", serviceToken, body: { pairingToken: randomToken("hcp_"), deviceName: "sync-check" } }),
    401,
    "pairing_invalid",
  );
  expect(
    "unknown device token cannot upload",
    await call(origin, "/api/sync", { method: "POST", serviceToken, deviceToken: randomToken("hcd_"), body: {} }),
    401,
    "device_revoked",
  );
  expect(
    "unknown device token cannot read connection status",
    await call(origin, "/api/sync/status", { method: "GET", serviceToken, deviceToken: randomToken("hcd_") }),
    401,
    "device_revoked",
  );
}

const STATUS_KEYS = "chatgptAccessEnabled,checkedAt,deviceConnected,lastSuccessfulSyncAt";

async function probe(origin: string) {
  const code = decodeConnectionCode(await readSecret("Connection code"));
  if (!code) fail("stdin is not a valid connection code");
  if (code.origin !== origin) fail("connection code is for a different site origin");
  const { serviceToken } = code;
  const paired = await call(origin, "/api/sync/pair", {
    method: "POST",
    serviceToken,
    body: { pairingToken: code.pairingToken, deviceName: "sync-check (temporary)" },
  });
  if (!expect("pairing code redeemed", paired, 201)) return;
  const deviceToken = String(paired.body?.deviceToken ?? "");
  try {
    expect(
      "pairing code cannot be replayed",
      await call(origin, "/api/sync/pair", { method: "POST", serviceToken, body: { pairingToken: code.pairingToken } }),
      401,
      "pairing_invalid",
    );
    const status = await call(origin, "/api/sync/status", { method: "GET", serviceToken, deviceToken });
    if (expect("device credential reads its connection status", status, 200)) {
      const keys = Object.keys(status.body ?? {}).sort().join(",");
      const ok = keys === STATUS_KEYS && status.body?.deviceConnected === true;
      console.log(`${ok ? "PASS" : "FAIL"} status contains only connection metadata`);
      if (!ok) process.exitCode = 1;
    }
    expect(
      "device credential reaches upload route; invalid snapshot rejected, nothing stored",
      await call(origin, "/api/sync", { method: "POST", serviceToken, deviceToken, body: { schemaVersion: 0 } }),
      422,
    );
  } finally {
    expect("temporary device disconnects itself", await call(origin, "/api/sync", { method: "DELETE", serviceToken, deviceToken }), 200);
  }
  expect(
    "disconnected credential is refused",
    await call(origin, "/api/sync", { method: "POST", serviceToken, deviceToken, body: { schemaVersion: 0 } }),
    401,
    "device_revoked",
  );
  expect(
    "disconnected credential cannot read status",
    await call(origin, "/api/sync/status", { method: "GET", serviceToken, deviceToken }),
    401,
    "device_revoked",
  );
  console.log("The previously paired phone (if any) was disconnected by this probe. Pair it again from the dashboard.");
}

const [command, rawOrigin, flag] = process.argv.slice(2);
const origin = parseOrigin(rawOrigin);
if (command === "gate") await gate(origin);
else if (command === "probe") {
  if (flag !== "--replace-paired-phone") fail("probe disconnects the paired phone; pass --replace-paired-phone to confirm");
  await probe(origin);
} else fail("usage: node scripts/sync-check.ts gate|probe <site-origin> [--replace-paired-phone]");
