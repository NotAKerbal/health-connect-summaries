import { getHealthDb } from "@/lib/health/db";
import { json, ownerIdFrom, rejectCrossSite } from "@/lib/health/http";
import { deleteImport } from "@/lib/health/store";

export const dynamic = "force-dynamic";

export async function DELETE(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;
  const { id } = await params;
  if (!/^[0-9a-f-]{36}$/.test(id)) return json({ error: "Not found" }, 404);
  const deleted = await deleteImport(getHealthDb(), ownerId, id);
  return deleted ? json({ deleted: true }) : json({ error: "Not found" }, 404);
}
