"use client";

import { LogOut } from "lucide-react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useSession } from "@/hooks/useSession";
import { logout } from "@/lib/auth";
import { redirectToLogin } from "@/lib/navigation";

/**
 * Session header. Logout clears the local session synchronously (tokens, snapshot, Query cache)
 * and then hard-navigates to `/login`, so no cached screen from the previous operator survives the
 * route change; the refresh-token revocation is fired first and allowed to finish in the
 * background (`keepalive`).
 */
export function AppHeader() {
  const session = useSession();

  function handleLogout() {
    void logout();
    redirectToLogin();
  }

  return (
    <header className="flex items-center justify-between gap-4 border-b px-6 py-3">
      <div className="text-sm text-muted-foreground">Sistem Layanan Pembiayaan</div>
      <div className="flex items-center gap-3">
        <div className="text-right">
          <div className="text-sm font-medium">{session.username ?? "–"}</div>
          <div className="flex justify-end gap-1">
            {session.roles.map((role) => (
              <Badge key={role} variant="outline" className="font-mono text-[10px]">
                {role}
              </Badge>
            ))}
          </div>
        </div>
        <Button variant="ghost" size="sm" onClick={handleLogout}>
          <LogOut className="size-4" aria-hidden="true" />
          Keluar
        </Button>
      </div>
    </header>
  );
}
