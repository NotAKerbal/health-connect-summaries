"use client";

import { useRouter } from "next/navigation";
import { useId, useState } from "react";
import { Switch } from "@/components/ui/switch";
import { mutate } from "./mutate";

export function SharingToggle({ enabled }: { enabled: boolean }) {
  const router = useRouter();
  const id = useId();
  const value = enabled;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onChange(next: boolean) {
    setBusy(true);
    setError(null);
    const result = await mutate("/api/sharing", "POST", JSON.stringify({ enabled: next }));
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    router.refresh();
  }

  return (
    <div>
      <div className="flex items-center gap-3">
        <Switch id={id} checked={value} disabled={busy} onCheckedChange={onChange} />
        <label htmlFor={id} className="text-sm font-medium">
          {value ? "ChatGPT can read my summaries" : "ChatGPT access is off"}
        </label>
      </div>
      {error && <p className="mt-2 text-sm text-red-700">{error}</p>}
    </div>
  );
}
