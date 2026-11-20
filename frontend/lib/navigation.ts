/** Navigation side effects kept in one place so the session logic stays testable (jsdom cannot navigate). */

export const LOGIN_PATH = "/login";

/**
 * Sends the operator to `/login` after the session is gone.
 *
 * <p>This is deliberately a full page load, not a client-side route change: the access token lives
 * only in module memory (ADR-023 D2), so a hard navigation drops every Query cache entry and every
 * residual piece of the session instead of leaving stale data mounted behind the router.
 */
export function redirectToLogin(): void {
  if (typeof window === "undefined" || window.location.pathname === LOGIN_PATH) {
    return;
  }
  // The App Router cannot be used here: this runs from the API client, outside any component tree.
  // eslint-disable-next-line @next/next/no-location-assign-relative-destination
  window.location.assign(LOGIN_PATH);
}
