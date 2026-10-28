"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { mutate } from "./mutate";

type Props = {
  url: string;
  body: string;
  triggerLabel: string;
  title: string;
  description: string;
  confirmLabel: string;
  busyLabel?: string;
  size?: "sm" | "default";
};

export function ConfirmDelete({ url, body, triggerLabel, title, description, confirmLabel, busyLabel = "Deleting…", size = "default" }: Props) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onConfirm(event: React.MouseEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const result = await mutate(url, "DELETE", body);
    setBusy(false);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setOpen(false);
    router.refresh();
  }

  return (
    <AlertDialog open={open} onOpenChange={(v) => !busy && setOpen(v)}>
      <AlertDialogTrigger asChild>
        <Button variant="outline" size={size} className="text-red-700 hover:text-red-800">
          {triggerLabel}
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {error && <p className="text-sm text-red-700">{error}</p>}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
          <AlertDialogAction variant="destructive" onClick={onConfirm} disabled={busy}>
            {busy ? busyLabel : confirmLabel}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
