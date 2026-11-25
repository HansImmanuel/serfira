"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { Toaster } from "@/components/ui/sonner";
import { registerSessionCacheCleaner } from "@/lib/session-cache";

/**
 * Client providers (FE §1.2). The QueryClient is created once per browser tab; retries are limited
 * because the API client already handles the one meaningful retry (a refresh-and-replay after 401).
 *
 * <p>The client registers itself as the session's cache owner: `setSession`/`clearSession` cancel
 * outstanding queries and drop every cached response synchronously, so a logout (or a login as a
 * different operator) can never show the previous session's contracts or money.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            retry: 1,
            staleTime: 15_000,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  useEffect(
    () =>
      registerSessionCacheCleaner(() => {
        void queryClient.cancelQueries();
        queryClient.clear();
      }),
    [queryClient],
  );

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      <Toaster richColors position="top-right" />
    </QueryClientProvider>
  );
}
