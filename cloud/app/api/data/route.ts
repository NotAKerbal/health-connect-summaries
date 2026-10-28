import { getHealthDb } from "@/lib/health/db";
import { json, ownerIdFrom, rejectCrossSite } from "@/lib/health/http";
import { deleteAllOwnerData } from "@/lib/health/store";

export const dynamic = "force-dynamic";

/** Deletes every stored summary, import record, and the ChatGPT access setting for the caller. */
export async function DELETE(request: Request) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;
  const body = (await request.json().catch(() => null)) as { confirm?: unknown } | null;
  if (body?.confirm !== "DELETE_ALL") return json({ error: 'Confirmation required: {"confirm":"DELETE_ALL"}' }, 400);
  await deleteAllOwnerData(getHealthDb(), ownerId);
  return json({ deleted: true });
}
