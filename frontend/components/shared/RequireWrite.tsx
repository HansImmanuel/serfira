"use client";

import Link from "next/link";
import { ShieldAlert } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useCanWrite } from "@/hooks/useSession";

/**
 * Hides a write screen from a role that cannot use it (FE §3.5). This is UX only: the backend's
 * matcher table still answers 403 for the action itself (ADR-015).
 */
export function RequireWrite({ children }: { children: React.ReactNode }) {
  const canWrite = useCanWrite();

  if (!canWrite) {
    return (
      <div className="flex flex-col items-start gap-3 rounded-lg border border-amber-200 bg-amber-50 p-6 text-amber-900">
        <ShieldAlert className="size-5" aria-hidden="true" />
        <div>
          <p className="font-medium">Peran Anda tidak dapat membuat kontrak.</p>
          <p className="text-sm">Aksi tulis hanya tersedia untuk ADMIN_OPERASIONAL.</p>
        </div>
        <Button asChild variant="outline" size="sm">
          <Link href="/contracts">Kembali ke daftar kontrak</Link>
        </Button>
      </div>
    );
  }
  return <>{children}</>;
}
