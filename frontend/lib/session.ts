/**
 * In-memory session store (FE §1.4, ADR-023 D2/D3).
 *
 * <p>Both tokens live in module memory for the tab's lifetime: never `localStorage`,
 * `sessionStorage`, a cookie or a URL. A reload therefore forces a new login, which is the
 * documented behaviour of this phase rather than a defect.
 *
 * <p>The username is kept from the login form because the JWT subject is an `app_user` UUID, and
 * the roles are decoded from the access token for UX only (there is no `/me` endpoint).
 *
 * <p>A monotonically increasing generation identifies the current session. Login, logout and
 * expiry bump it; a token rotation does not, because the operator is the same person. Async work
 * that started under an older generation (an in-flight refresh, a queued request) must not write
 * its result into the newer session (review finding: a stale refresh overwrote a fresh login).
 */

import type { TokenPair } from "@/types/auth";

import { decodeJwtClaims } from "./jwt";
import { clearSessionCache } from "./session-cache";

export interface SessionState {
  authenticated: boolean;
  username: string | null;
  roles: string[];
}

const ANONYMOUS: SessionState = Object.freeze({ authenticated: false, username: null, roles: [] });

let accessToken: string | null = null;
let refreshToken: string | null = null;
let username: string | null = null;
let snapshot: SessionState = ANONYMOUS;
let generation = 0;
const listeners = new Set<() => void>();

function refreshSnapshot(): void {
  const claims = accessToken === null ? null : decodeJwtClaims(accessToken);
  if (accessToken === null && refreshToken === null && username === null) {
    snapshot = ANONYMOUS;
    return;
  }
  snapshot = Object.freeze({
    authenticated: claims !== null,
    username,
    roles: claims === null ? [] : claims.roles,
  });
}

function notify(): void {
  for (const listener of [...listeners]) {
    listener();
  }
}

/** Stable snapshot for `useSyncExternalStore`. */
export function getSession(): SessionState {
  return snapshot;
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  return refreshToken;
}

/**
 * Identifier of the current session. Captured before an async call and compared afterwards: a
 * mismatch means a different operator logged in (or the session ended) in the meantime.
 */
export function getSessionGeneration(): number {
  return generation;
}

/** After login: both tokens plus the username the operator typed. Starts a new generation. */
export function setSession(tokens: TokenPair, enteredUsername: string): void {
  accessToken = tokens.access_token;
  refreshToken = tokens.refresh_token;
  username = enteredUsername;
  generation += 1;
  clearSessionCache();
  refreshSnapshot();
  notify();
}

/**
 * After a rotation: replace both tokens, keep the username and the generation.
 *
 * <p>Returns `false` without touching anything when `expectedGeneration` is no longer current — the
 * refresh response belongs to a session that has already been replaced or ended, so applying it
 * would hand the new operator's cache to the old token pair.
 */
export function updateTokens(tokens: TokenPair, expectedGeneration: number): boolean {
  if (generation !== expectedGeneration) {
    return false;
  }
  accessToken = tokens.access_token;
  refreshToken = tokens.refresh_token;
  refreshSnapshot();
  notify();
  return true;
}

export function clearSession(): void {
  accessToken = null;
  refreshToken = null;
  username = null;
  snapshot = ANONYMOUS;
  generation += 1;
  clearSessionCache();
  notify();
}
