import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { login, logout } from "./auth";
import { clearSession, getAccessToken, getRefreshToken, getSession } from "./session";

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response;
}

describe("login", () => {
  beforeEach(() => {
    clearSession();
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  it("posts the credentials and keeps both tokens plus the entered username in memory", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ data: { access_token: "access-1", refresh_token: "refresh-1" }, error: null }),
    );

    await login("admin", "rahasia123");

    const [url, init] = vi.mocked(fetch).mock.calls[0];
    expect(String(url)).toBe("http://localhost:8080/api/v1/auth/login");
    expect(init?.method).toBe("POST");
    expect(JSON.parse(String(init?.body))).toEqual({ username: "admin", password: "rahasia123" });
    expect((init?.headers as Record<string, string>).Authorization).toBeUndefined();
    expect(getAccessToken()).toBe("access-1");
    expect(getRefreshToken()).toBe("refresh-1");
    expect(getSession().username).toBe("admin");
  });

  it("surfaces the lockout envelope and stays unauthenticated", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse(
        { data: null, error: { code: "ACCOUNT_LOCKED", message: "Akun terkunci, coba lagi dalam 12 menit." } },
        423,
      ),
    );

    await expect(login("admin", "salah-sekali")).rejects.toMatchObject({
      code: "ACCOUNT_LOCKED",
      message: "Akun terkunci, coba lagi dalam 12 menit.",
      status: 423,
    });
    expect(getSession().authenticated).toBe(false);
  });
});

describe("logout", () => {
  beforeEach(() => {
    clearSession();
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  it("revokes the refresh token and then forgets the session", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ data: { access_token: "access-1", refresh_token: "refresh-1" }, error: null }),
    );
    await login("admin", "rahasia123");

    vi.mocked(fetch).mockResolvedValue(jsonResponse({ data: null, error: null }));

    await logout();

    const [url, init] = vi.mocked(fetch).mock.calls[1];
    expect(String(url)).toBe("http://localhost:8080/api/v1/auth/logout");
    expect(JSON.parse(String(init?.body))).toEqual({ refresh_token: "refresh-1" });
    expect(init?.keepalive).toBe(true);
    expect(getSession().authenticated).toBe(false);
  });

  it("drops the local session before the revocation settles, so the tab is logged out immediately", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ data: { access_token: "access-1", refresh_token: "refresh-1" }, error: null }),
    );
    await login("admin", "rahasia123");

    let release!: () => void;
    vi.mocked(fetch).mockReturnValue(
      new Promise<Response>((resolve) => {
        release = () => resolve(jsonResponse({ data: null, error: null }));
      }),
    );

    const pending = logout();

    expect(getSession().authenticated).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();

    release();
    await pending;
  });

  it("still forgets the session when the revoke call fails, so the tab is never left logged in", async () => {
    vi.mocked(fetch).mockResolvedValue(
      jsonResponse({ data: { access_token: "access-1", refresh_token: "refresh-1" }, error: null }),
    );
    await login("admin", "rahasia123");

    vi.mocked(fetch).mockRejectedValue(new TypeError("Failed to fetch"));

    await logout();

    expect(getSession().authenticated).toBe(false);
    expect(getAccessToken()).toBeNull();
  });

  it("does nothing when there is no session", async () => {
    await logout();

    expect(fetch).not.toHaveBeenCalled();
  });
});
