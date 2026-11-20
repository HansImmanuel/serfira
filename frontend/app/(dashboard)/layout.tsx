"use client";

import { AppHeader } from "@/components/shared/AppHeader";
import { AppSidebar } from "@/components/shared/AppSidebar";
import { AuthGuard } from "@/components/shared/AuthGuard";

/**
 * Dashboard shell (FE §1.2): sidebar + header around every operational screen. The guard is
 * client-side, so a reload lands on `/login` when the in-memory session is gone (ADR-023 D2).
 */
export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <AuthGuard>
      <div className="flex min-h-screen">
        <AppSidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <AppHeader />
          <main className="flex-1 overflow-x-hidden p-6">{children}</main>
        </div>
      </div>
    </AuthGuard>
  );
}
