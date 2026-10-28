import { getHealthDb } from "@/lib/health/db";
import { BodyTooLargeError, json, ownerIdFrom, readTextCapped } from "@/lib/health/http";
import { handleMcpMessage } from "@/lib/health/mcp";

export const dynamic = "force-dynamic";

const MAX_MCP_REQUEST_BYTES = 32 * 1024;

export async function POST(request: Request) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) {
    return json({ jsonrpc: "2.0", id: null, error: { code: -32001, message: "Authentication required" } }, 401);
  }
  let message: unknown;
  try {
    message = JSON.parse(await readTextCapped(request, MAX_MCP_REQUEST_BYTES));
  } catch (error) {
    if (error instanceof BodyTooLargeError) {
      return json({ jsonrpc: "2.0", id: null, error: { code: -32600, message: "Request too large" } }, 413);
    }
    return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }, 400);
  }
  const response = await handleMcpMessage(message, {
    db: getHealthDb(),
    ownerId,
    nowMs: Date.now(),
    siteOrigin: new URL(request.url).origin,
  });
  if (response === null) return new Response(null, { status: 202, headers: { "cache-control": "no-store" } });
  return json(response);
}

export async function GET() {
  return new Response("Method Not Allowed", { status: 405, headers: { allow: "POST" } });
}
