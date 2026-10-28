import { getHealthDb } from "@/lib/health/db";
import { json, ownerIdFrom, rejectCrossSite } from "@/lib/health/http";
import { setSharing } from "@/lib/health/store";

export const dynamic = "force-dynamic";

/** Turns the ChatGPT plugin's read access to the caller's summaries on or off. */
export async function POST(request: Request) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;
  const body = (await request.json().catch(() => null)) as { enabled?: unknown } | null;
  if (typeof body?.enabled !== "boolean") return json({ error: "Expected {\"enabled\": true|false}" }, 400);
  return json(await setSharing(getHealthDb(), ownerId, body.enabled, Date.now()));
}
