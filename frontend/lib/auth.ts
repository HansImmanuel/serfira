/**
 * Login and logout (FE §1.4, §2.1), including the in-memory session handoff.
 *
 * <p>Login stores both tokens and the username that was typed; logout revokes the refresh token
 * (best effort — the endpoint is idempotent) and always forgets the local session.
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
  if (refreshToken === null) {
    clearSession();
    return;
  }
  try {
    await apiFetch<void>("/auth/logout", {
      method: "POST",
      body: JSON.stringify({ refresh_token: refreshToken }),
    });
  } catch {
    // Revocation is best effort: a failed call must not leave the tab looking logged in.
  } finally {
    clearSession();
  }
}
