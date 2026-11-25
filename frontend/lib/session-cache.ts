/**
 * Session-scoped cache ownership (review finding: a logout left the previous operator's Query cache
 * mounted).
 *
 * <p>`lib/session.ts` must stay free of React and TanStack Query imports, so it cannot clear the
 * `QueryClient` itself. `app/providers.tsx` registers the cancel-and-clear routine here once, and
 * every login/logout runs it synchronously before the next screen can read a cached response.
 */

type SessionCacheCleaner = () => void;

let cleaner: SessionCacheCleaner | null = null;

/** Registers the one cleaner for the tab's QueryClient; returns an unregister function. */
export function registerSessionCacheCleaner(next: SessionCacheCleaner): () => void {
  cleaner = next;
  return () => {
    if (cleaner === next) {
      cleaner = null;
    }
  };
}

/** Drops every cached response. Safe before the provider has mounted (no-op). */
export function clearSessionCache(): void {
  cleaner?.();
}
