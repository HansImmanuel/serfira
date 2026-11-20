"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { LoadingSpinner } from "@/components/shared/LoadingSpinner";
import { LOGIN_PATH } from "@/lib/navigation";
import { useSession } from "@/hooks/useSession";

/**
 * Client-side gate for the dashboard shell. Tokens live in memory (ADR-023 D2), so a reload lands
 * here unauthenticated and is sent to `/login`; this is UX only, every request is still authorized
 * by the backend.
 */
export function AuthGuard({ children }: { children: React.ReactNode }) {
  const session = useSession();
  const router = useRouter();

  useEffect(() => {
    if (!session.authenticated) {
      router.replace(LOGIN_PATH);
    }
  }, [session.authenticated, router]);

  if (!session.authenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <LoadingSpinner label="Memeriksa sesi…" />
      </div>
    );
  }
  return <>{children}</>;
}
