export type MutationResult = { ok: true; body: Record<string, unknown> } | { ok: false; error: string; issues?: string[] };

/** Same-origin JSON request used by every dashboard mutation. */
export async function mutate(url: string, method: string, body: string): Promise<MutationResult> {
  try {
    const res = await fetch(url, {
      method,
      headers: { "content-type": "application/json" },
      credentials: "same-origin",
      body,
    });
    const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
    if (res.ok) return { ok: true, body: data };
    return {
      ok: false,
      error: typeof data.error === "string" ? data.error : `Request failed (${res.status})`,
      issues: Array.isArray(data.issues) ? data.issues.map(String) : undefined,
    };
  } catch {
    return { ok: false, error: "Network error. Check your connection and try again." };
  }
}
