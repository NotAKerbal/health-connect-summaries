// Request helpers for browser API routes and the MCP endpoint.

const USER_ID_HEADER = "oai-authenticated-user-id";

const NO_STORE = { "cache-control": "no-store", "x-content-type-options": "nosniff" };

export function json(body: unknown, status = 200, extraHeaders: Record<string, string> = {}): Response {
  return Response.json(body, { status, headers: { ...NO_STORE, ...extraHeaders } });
}

/**
 * The owner is the Site-scoped user ID that Sites injects after authenticating the caller.
 * It is never read from a body, query string, or tool argument.
 */
export function ownerIdFrom(request: Request): string | null {
  const id = request.headers.get(USER_ID_HEADER)?.trim();
  return id && id.length <= 256 ? id : null;
}

/**
 * CSRF defence for browser mutations: require a JSON body (cross-site forms cannot send one
 * without a CORS preflight, which this Site never approves) and reject cross-site fetch metadata.
 */
export function rejectCrossSite(request: Request): Response | null {
  const contentType = request.headers.get("content-type") ?? "";
  if (!contentType.toLowerCase().startsWith("application/json")) {
    return json({ error: "Expected application/json" }, 415);
  }
  const site = request.headers.get("sec-fetch-site");
  if (site && site !== "same-origin" && site !== "none") {
    return json({ error: "Cross-site requests are not allowed" }, 403);
  }
  return null;
}

export class BodyTooLargeError extends Error {}

/** Reads a request body as UTF-8 text, aborting once it exceeds `maxBytes`. */
export async function readTextCapped(request: Request, maxBytes: number): Promise<string> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > maxBytes) throw new BodyTooLargeError();
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maxBytes) {
      await reader.cancel();
      throw new BodyTooLargeError();
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const c of chunks) {
    bytes.set(c, offset);
    offset += c.byteLength;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}
