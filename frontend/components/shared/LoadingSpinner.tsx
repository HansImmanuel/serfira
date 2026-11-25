import { Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

/** Loading indicator for a page or a card (FE §3.4). */
export function LoadingSpinner({ label = "Memuat…", className }: { label?: string; className?: string }) {
  return (
    <div className={cn("flex items-center justify-center gap-2 py-10 text-muted-foreground", className)} role="status">
      <Loader2 className="size-4 animate-spin" aria-hidden="true" />
      <span className="text-sm">{label}</span>
    </div>
  );
}
