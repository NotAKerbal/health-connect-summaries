import { syncDeps } from "@/lib/health/runtime";
import { handleOwnerRevokeDevice } from "@/lib/health/sync-api";

export const dynamic = "force-dynamic";

/** Signed-in owner disconnects a paired device. */
export async function DELETE(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return handleOwnerRevokeDevice(request, id, syncDeps());
}
