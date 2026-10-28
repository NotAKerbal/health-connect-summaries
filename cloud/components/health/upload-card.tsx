"use client";

import { useRouter } from "next/navigation";
import { useId, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { mutate } from "./mutate";

const MAX_BYTES = 64 * 1024;

type Preview = {
  text: string;
  fileName: string;
  rangeStart: string;
  rangeEnd: string;
  generatedAt: string;
  timezone: string;
  categories: string[];
  dayCount: number;
};

type Status =
  | { kind: "idle" }
  | { kind: "error"; message: string; issues?: string[] }
  | { kind: "success"; message: string };

export function UploadCard({ sharingEnabled, categoryLabels }: { sharingEnabled: boolean; categoryLabels: Record<string, string> }) {
  const router = useRouter();
  const inputId = useId();
  const consentId = useId();
  const inputRef = useRef<HTMLInputElement>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [allowChatGPT, setAllowChatGPT] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<Status>({ kind: "idle" });

  async function onFile(file: File | undefined) {
    setPreview(null);
    setAllowChatGPT(false);
    setStatus({ kind: "idle" });
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setStatus({ kind: "error", message: "This file is larger than 64 KiB, so it is not a Health Connect Export file." });
      return;
    }
    const text = await file.text();
    try {
      const data = JSON.parse(text) as Record<string, unknown>;
      // Local summary of the selected file so you can confirm before uploading; the server validates fully.
      setPreview({
        text,
        fileName: file.name,
        rangeStart: String(data.rangeStart ?? "?"),
        rangeEnd: String(data.rangeEnd ?? "?"),
        generatedAt: String(data.generatedAt ?? "?"),
        timezone: String(data.timezone ?? "?"),
        categories: Array.isArray(data.categories) ? data.categories.map(String) : [],
        dayCount: Array.isArray(data.days) ? data.days.length : 0,
      });
    } catch {
      setStatus({ kind: "error", message: "This file is not valid JSON. Choose the .json file created by the Health Connect Export app." });
    }
  }

  async function onImport() {
    if (!preview) return;
    setBusy(true);
    const query = !sharingEnabled && allowChatGPT ? "?allowChatGPT=1" : "";
    const result = await mutate(`/api/imports${query}`, "POST", preview.text);
    setBusy(false);
    if (!result.ok) {
      setStatus({ kind: "error", message: result.error, issues: result.issues });
      return;
    }
    const duplicate = result.body.status === "duplicate";
    setStatus({
      kind: "success",
      message: duplicate
        ? `This export was already imported. No stored health values changed.${allowChatGPT ? " ChatGPT access is now on." : ""}`
        : `Imported ${result.body.dayCount} days (${result.body.rangeStart} to ${result.body.rangeEnd}). Stored values for those dates were replaced with this export.`,
    });
    setPreview(null);
    setAllowChatGPT(false);
    if (inputRef.current) inputRef.current.value = "";
    router.refresh();
  }

  return (
    <div className="space-y-4">
      <div>
        <label htmlFor={inputId} className="mb-1.5 block text-sm font-medium">
          Export file (.json)
        </label>
        <input
          ref={inputRef}
          id={inputId}
          type="file"
          accept="application/json,.json"
          onChange={(e) => onFile(e.target.files?.[0])}
          className="block w-full cursor-pointer rounded-md border border-input bg-background text-sm file:mr-3 file:cursor-pointer file:border-0 file:bg-secondary file:px-3 file:py-2 file:text-sm file:font-medium"
        />
        <p className="mt-1.5 text-xs text-muted-foreground">The file is read in this browser and sent only to this private site.</p>
      </div>

      {preview && (
        <div className="rounded-lg border bg-secondary/40 p-4 text-sm">
          <p className="font-medium">Confirm this import</p>
          <dl className="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1">
            <dt className="text-muted-foreground">File</dt>
            <dd className="break-all">{preview.fileName}</dd>
            <dt className="text-muted-foreground">Dates</dt>
            <dd>
              {preview.rangeStart} to {preview.rangeEnd} ({preview.dayCount} days)
            </dd>
            <dt className="text-muted-foreground">Exported</dt>
            <dd>
              {preview.generatedAt} ({preview.timezone})
            </dd>
            <dt className="text-muted-foreground">Categories</dt>
            <dd>{preview.categories.map((c) => categoryLabels[c] ?? c).join(", ") || "none"}</dd>
          </dl>
          <p className="mt-3 text-xs text-muted-foreground">
            Importing replaces any stored values for these dates, including categories this file does not contain.
          </p>
          {!sharingEnabled && (
            <div className="mt-3 flex items-start gap-2">
              <Checkbox id={consentId} checked={allowChatGPT} onCheckedChange={(v) => setAllowChatGPT(v === true)} className="mt-0.5" />
              <label htmlFor={consentId} className="text-sm leading-snug">
                Also allow my private ChatGPT plugin to read my imported daily summaries. You can turn this off at any time.
              </label>
            </div>
          )}
          <div className="mt-4 flex flex-wrap gap-2">
            <Button onClick={onImport} disabled={busy}>
              {busy ? "Importing…" : "Import"}
            </Button>
            <Button
              variant="ghost"
              disabled={busy}
              onClick={() => {
                setPreview(null);
                if (inputRef.current) inputRef.current.value = "";
              }}
            >
              Cancel
            </Button>
          </div>
        </div>
      )}

      <div aria-live="polite">
        {status.kind === "success" && (
          <p className="rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-900">{status.message}</p>
        )}
        {status.kind === "error" && (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-900">
            <p>{status.message}</p>
            {status.issues && status.issues.length > 0 && (
              <ul className="mt-1 list-disc pl-5 text-xs">
                {status.issues.map((issue) => (
                  <li key={issue}>{issue}</li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
