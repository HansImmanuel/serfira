import { beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  getSession,
  setSession,
  subscribeSession,
  updateTokens,
} from "./session";

function tokenWith(payload: Record<string, unknown>): string {
  const base64 = btoa(JSON.stringify(payload)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `header.${base64}.signature`;
}

const ADMIN_TOKEN = tokenWith({
  sub: "6f1a0f36-0c1a-4b3f-9f3f-2f1f6f4b0a11",
  roles: ["ADMIN_OPERASIONAL"],
  exp: 1795000000,
});

const FINANCE_TOKEN = tokenWith({
  sub: "6f1a0f36-0c1a-4b3f-9f3f-2f1f6f4b0a11",
  roles: ["FINANCE", "MANAJEMEN"],
  exp: 1795000000,
});

describe("session store", () => {
  beforeEach(() => {
    clearSession();
  });

  it("starts unauthenticated", () => {
    expect(getSession()).toEqual({ authenticated: false, username: null, roles: [] });
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it("keeps both tokens and derives the roles from the access token", () => {
    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-1" }, "admin");

    expect(getAccessToken()).toBe(ADMIN_TOKEN);
    expect(getRefreshToken()).toBe("refresh-1");
    expect(getSession()).toEqual({
      authenticated: true,
      username: "admin",
      roles: ["ADMIN_OPERASIONAL"],
    });
  });

  it("keeps the login username across a refresh, because the JWT subject is not a display name", () => {
    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-1" }, "admin");
    updateTokens({ access_token: FINANCE_TOKEN, refresh_token: "refresh-2" });

    expect(getSession()).toEqual({
      authenticated: true,
      username: "admin",
      roles: ["FINANCE", "MANAJEMEN"],
    });
  });

  it("returns a stable snapshot so React can use it as an external store", () => {
    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-1" }, "admin");
    expect(getSession()).toBe(getSession());
  });

  it("notifies subscribers on every change and stops after unsubscribe", () => {
    const listener = vi.fn();
    const unsubscribe = subscribeSession(listener);

    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-1" }, "admin");
    updateTokens({ access_token: FINANCE_TOKEN, refresh_token: "refresh-2" });
    clearSession();

    expect(listener).toHaveBeenCalledTimes(3);

    unsubscribe();
    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-3" }, "admin");
    expect(listener).toHaveBeenCalledTimes(3);
  });

  it("clears the username and both tokens on logout", () => {
    setSession({ access_token: ADMIN_TOKEN, refresh_token: "refresh-1" }, "admin");
    clearSession();

    expect(getSession()).toEqual({ authenticated: false, username: null, roles: [] });
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });

  it("treats an undecodable access token as unauthenticated instead of trusting it", () => {
    setSession({ access_token: "not-a-jwt", refresh_token: "refresh-1" }, "admin");

    expect(getSession().authenticated).toBe(false);
    expect(getSession().roles).toEqual([]);
  });
});
