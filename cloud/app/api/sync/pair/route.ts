import { syncDeps } from "@/lib/health/runtime";
import { handleRedeemPairing } from "@/lib/health/sync-api";

export const dynamic = "force-dynamic";

/** Phone redeems a one-time pairing token for a device token (upload, self-disconnect, and connection status only). */
export async function POST(request: Request) {
  return handleRedeemPairing(request, syncDeps());
}
