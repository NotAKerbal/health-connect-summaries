import { syncDeps } from "@/lib/health/runtime";
import { handleSyncStatus } from "@/lib/health/sync-api";

export const dynamic = "force-dynamic";

/** Paired phone reads its connection status (no health data) with its device token. */
export async function GET(request: Request) {
  return handleSyncStatus(request, syncDeps());
}
