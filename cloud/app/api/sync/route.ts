import { syncDeps } from "@/lib/health/runtime";
import { handleSyncSelfRevoke, handleSyncUpload } from "@/lib/health/sync-api";

export const dynamic = "force-dynamic";

/** Paired phone uploads a snapshot with its device token. */
export async function POST(request: Request) {
  return handleSyncUpload(request, syncDeps());
}

/** Paired phone disconnects itself. */
export async function DELETE(request: Request) {
  return handleSyncSelfRevoke(request, syncDeps());
}
