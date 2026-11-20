"use client";

import { useRouter } from "next/navigation";
import { useEffect } from "react";

import { LoginForm } from "@/components/auth/LoginForm";
import { useSession } from "@/hooks/useSession";

/** `/login` (FE §2.1). An already-authenticated visitor is sent to the contract book. */
export default function LoginPage() {
  const session = useSession();
  const router = useRouter();

  useEffect(() => {
    if (session.authenticated) {
      router.replace("/contracts");
    }
  }, [session.authenticated, router]);

  return (
    <main className="flex min-h-screen items-center justify-center bg-muted/40 p-6">
      <LoginForm />
    </main>
  );
}
