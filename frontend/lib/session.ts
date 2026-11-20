/**
 * In-memory session store (FE §1.4, ADR-023 D2/D3).
 *
 * <p>Both tokens live in module memory for the tab's lifetime: never `localStorage`,
 * `sessionStorage`, a cookie or a URL. A reload therefore forces a new login, which is the
 * documented behaviour of this phase rather than a defect.
 *
 * <p>The username is kept from the login form because the JWT subject is an `app_user` UUID, and
 * the roles are decoded from the access token for UX only (there is no `/me` endpoint).
 */

import type { TokenPair } from "@/types/auth";

import { decodeJwtClaims } from "./jwt";

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

/** After login: both tokens plus the username the operator typed. */
export function setSession(tokens: TokenPair, enteredUsername: string): void {
  accessToken = tokens.access_token;
  refreshToken = tokens.refresh_token;
  username = enteredUsername;
  refreshSnapshot();
  notify();
}

/** After a rotation: replace both tokens, keep the username. */
export function updateTokens(tokens: TokenPair): void {
  accessToken = tokens.access_token;
  refreshToken = tokens.refresh_token;
  refreshSnapshot();
  notify();
}

export function clearSession(): void {
  accessToken = null;
  refreshToken = null;
  username = null;
  snapshot = ANONYMOUS;
  notify();
}
