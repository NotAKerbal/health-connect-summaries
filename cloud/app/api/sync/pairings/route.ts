import { syncDeps } from "@/lib/health/runtime";
import { handleCreatePairing } from "@/lib/health/sync-api";

export const dynamic = "force-dynamic";

/** Signed-in owner creates a one-time connection code for the Android app. */
export async function POST(request: Request) {
  return handleCreatePairing(request, syncDeps());
}
