/**
 * Login and logout (FE §1.4, §2.1), including the in-memory session handoff.
 *
 * <p>Login stores both tokens and the username that was typed. Logout forgets the local session
 * *first* — tokens, reactive snapshot and the Query cache — so no screen can keep rendering the
 * previous operator's data while the best-effort revocation is in flight; the endpoint is
 * idempotent, so a failed or aborted revocation still leaves the client logged out.
 */

import type { TokenPair } from "@/types/auth";

import { apiFetch } from "./api";
import { clearSession, getRefreshToken, setSession } from "./session";

export async function login(username: string, password: string): Promise<void> {
  const tokens = await apiFetch<TokenPair>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password }),
  });
  setSession(tokens, username);
}

export async function logout(): Promise<void> {
  const refreshToken = getRefreshToken();
  clearSession();
  if (refreshToken === null) {
    return;
  }
  try {
    await apiFetch<void>("/auth/logout", {
      method: "POST",
      body: JSON.stringify({ refresh_token: refreshToken }),
      // The page may navigate away immediately; keep the revocation alive across the unload.
      keepalive: true,
    });
  } catch {
    // Revocation is best effort: the local session is already gone and the token will expire.
  }
}
