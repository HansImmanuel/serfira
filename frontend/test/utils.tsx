import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import type { ReactElement } from "react";

/** A QueryClient whose queries never retry (keeps failure tests fast). */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
}

/**
 * Renders a component inside a QueryClientProvider. Pass a client to seed or inspect the cache;
 * the client used is returned alongside the render result.
 */
export function renderWithQueryClient(ui: ReactElement, queryClient: QueryClient = createTestQueryClient()) {
  const result = render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
  return { ...result, queryClient };
}

function base64url(json: string): string {
  return btoa(json).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Mints an unsigned access token whose claims drive the UI role gating (ADR-023 D3). */
export function accessTokenWithRoles(roles: string[]): string {
  return `header.${base64url(JSON.stringify({ sub: "user-1", roles, exp: 1795000000 }))}.signature`;
}
