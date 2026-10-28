"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { mutate } from "./mutate";

type Code = { code: string; expiresAtMs: number };

/**
 * Creates a one-time connection code on request. The code exists only in this component's
 * memory: it is never server-rendered, put in a URL, or stored, and it is dropped on expiry.
 */
export function PairingCard({ paired, configured }: { paired: boolean; configured: boolean }) {
  const router = useRouter();
  const [code, setCode] = useState<Code | null>(null);
  const [reveal, setReveal] = useState(false);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (!code) return;
    const timer = setInterval(() => {
      const t = Date.now();
      setNow(t);
      if (t >= code.expiresAtMs) {
        setCode(null);
        setReveal(false);
        setCopied(false);
        setError("The code expired. Create a new one when you're ready.");
      }
    }, 1000);
    return () => clearInterval(timer);
  }, [code]);

  async function create() {
    setBusy(true);
    setError(null);
    setCopied(false);
    setReveal(false);
    const result = await mutate("/api/sync/pairings", "POST", "{}");
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    const value = typeof result.body.code === "string" ? result.body.code : null;
    const expiresAtMs = Date.parse(String(result.body.expiresAt));
    if (!value || !Number.isFinite(expiresAtMs)) {
      setError("The site returned an unexpected response. Try again.");
      return;
    }
    setNow(Date.now());
    setCode({ code: value, expiresAtMs });
  }

  async function copy() {
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code.code);
      setCopied(true);
    } catch {
      setReveal(true);
      setError("Copying is blocked in this browser. Select the code below and copy it manually.");
    }
  }

  function done() {
    setCode(null);
    setReveal(false);
    setCopied(false);
    router.refresh();
  }

  if (!configured) {
    return (
      <p className="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-950">
        Pairing isn&apos;t configured on this Site yet, so connection codes can&apos;t be created. Manual upload still works.
      </p>
    );
  }

  const secondsLeft = code ? Math.max(0, Math.ceil((code.expiresAtMs - now) / 1000)) : 0;

  return (
    <div className="space-y-3">
      {!code ? (
        <>
          <Button onClick={create} disabled={busy}>
            {busy ? "Creating…" : paired ? "Pair a different phone" : "Create connection code"}
          </Button>
          {paired && <p className="text-xs text-muted-foreground">Pairing a different phone disconnects this one.</p>}
        </>
      ) : (
        <div className="space-y-3 rounded-lg border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950">
          <p className="font-medium">Code ready. Expires in {Math.floor(secondsLeft / 60)}:{String(secondsLeft % 60).padStart(2, "0")}.</p>
          <p>
            This code is a secret: it contains this Site&apos;s access key. Paste it only into the app on your own phone. Don&apos;t
            share or save it.
          </p>
          <div className="flex flex-wrap gap-2">
            <Button onClick={copy}>{copied ? "Copied" : "Copy to clipboard"}</Button>
            <Button variant="outline" onClick={() => setReveal((v) => !v)}>
              {reveal ? "Hide code" : "Show code"}
            </Button>
            <Button variant="ghost" onClick={done}>
              Done
            </Button>
          </div>
          {reveal && (
            <textarea
              readOnly
              aria-label="Connection code"
              value={code.code}
              rows={4}
              spellCheck={false}
              autoComplete="off"
              className="block w-full break-all rounded-md border border-amber-300 bg-white p-2 font-mono text-xs"
              onFocus={(e) => e.currentTarget.select()}
            />
          )}
          <p className="text-xs">
            In the app, tap <strong>Paste connection code</strong>, then <strong>Connect</strong>.
          </p>
        </div>
      )}
      {error && <p className="text-sm text-red-700">{error}</p>}
    </div>
  );
}
