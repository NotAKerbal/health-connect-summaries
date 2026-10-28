import { getHealthDb } from "@/lib/health/db";
import { BodyTooLargeError, json, ownerIdFrom, readTextCapped, rejectCrossSite } from "@/lib/health/http";
import { StaleImportError, importSnapshot, listImports } from "@/lib/health/store";
import { MAX_EXPORT_BYTES, parseAndValidateExport, sha256Hex } from "@/lib/health/validate";

export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  return json({ imports: await listImports(getHealthDb(), ownerId) });
}

/**
 * Body: the export file's JSON, exactly as produced by the Android app.
 * Optional query `?allowChatGPT=1` records the owner's explicit consent in the same request.
 */
export async function POST(request: Request) {
  const ownerId = ownerIdFrom(request);
  if (!ownerId) return json({ error: "Sign in required" }, 401);
  const crossSite = rejectCrossSite(request);
  if (crossSite) return crossSite;

  let text: string;
  try {
    text = await readTextCapped(request, MAX_EXPORT_BYTES);
  } catch (error) {
    if (error instanceof BodyTooLargeError) return json({ error: `Export files are limited to ${MAX_EXPORT_BYTES / 1024} KiB` }, 413);
    return json({ error: "The file is not valid UTF-8 text" }, 400);
  }
  const nowMs = Date.now();
  const result = parseAndValidateExport(text, nowMs);
  if (!result.ok) return json({ error: "This file is not a valid Health Connect export", issues: result.issues }, 422);

  const db = getHealthDb();
  try {
    const canonical = JSON.stringify(result.value);
    const outcome = await importSnapshot(db, ownerId, result.value, {
      sha256: await sha256Hex(canonical),
      payloadBytes: new TextEncoder().encode(text).byteLength,
      nowMs,
      enableSharing: new URL(request.url).searchParams.get("allowChatGPT") === "1",
    });
    return json(outcome, outcome.status === "imported" ? 201 : 200);
  } catch (error) {
    if (error instanceof StaleImportError) return json({ error: error.message }, 409);
    return json({ error: "Could not save the import. Nothing was changed; try again." }, 500);
  }
}
