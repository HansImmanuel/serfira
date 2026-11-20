import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, getAccessToken, getSession, setSession } from "./session";

vi.mock("./navigation", () => ({
  redirectToLogin: vi.fn(),
}));

import { redirectToLogin } from "./navigation";
import { ApiError, apiFetch, apiUrl, refreshAccessToken } from "./api";

const OLD_TOKEN = "old-access-token";
const NEW_TOKEN = "new-access-token";

function jsonResponse(body: unknown, status = 200) {
  return {
    ok: status >= 200 && status < 300,
    status,
    text: async () => JSON.stringify(body),
  } as Response;
}

function textResponse(text: string, status: number) {
  return {
    ok: false,
    status,
    text: async () => text,
  } as Response;
}

function errorResponse(code: string, message: string, status: number) {
  return jsonResponse({ data: null, error: { code, message } }, status);
}

function bearer(headers: RequestInit["headers"]): string | null {
  const record = (headers ?? {}) as Record<string, string>;
  return record.Authorization ?? null;
}

describe("apiUrl", () => {
  it("prefixes the versioned API path and uses the configured base URL", () => {
    expect(apiUrl("/contracts")).toBe("http://localhost:8080/api/v1/contracts");
  });
});

describe("apiFetch", () => {
  beforeEach(() => {
    clearSession();
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  it("unwraps the envelope and returns only the payload", async () => {
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ data: { id: "c1" }, error: null }));

    await expect(apiFetch<{ id: string }>("/contracts/c1")).resolves.toEqual({ id: "c1" });
  });

  it("sends the bearer token on protected paths", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ data: [], error: null }));

    await apiFetch("/contracts");

    expect(bearer(vi.mocked(fetch).mock.calls[0][1]?.headers)).toBe(`Bearer ${OLD_TOKEN}`);
  });

  it("never sends a token to the public auth paths, even when a session exists", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ data: null, error: null }));

    for (const path of ["/auth/login", "/auth/refresh", "/auth/logout"]) {
      await apiFetch(path, { method: "POST", body: "{}" });
    }

    for (const call of vi.mocked(fetch).mock.calls) {
      expect(bearer(call[1]?.headers)).toBeNull();
    }
  });

  it("lets the caller's own headers through unchanged", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockResolvedValue(jsonResponse({ data: { id: "c1" }, error: null }));

    await apiFetch("/contracts", {
      method: "POST",
      headers: { "Idempotency-Key": "key-1" },
      body: "{}",
    });

    const headers = vi.mocked(fetch).mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("key-1");
    expect(headers.Authorization).toBe(`Bearer ${OLD_TOKEN}`);
  });

  it("throws the envelope's error code, message and status", async () => {
    vi.mocked(fetch).mockResolvedValue(
      errorResponse("CONTRACT_STATE_INVALID", "Kontrak sudah aktif.", 409),
    );

    const failure = apiFetch("/contracts/c1/activate", { method: "POST" });

    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toMatchObject({
      code: "CONTRACT_STATE_INVALID",
      message: "Kontrak sudah aktif.",
      status: 409,
    });
  });

  it("maps a non-JSON failure body instead of crashing on JSON.parse", async () => {
    // The CORS filter answers a disallowed origin with a plain-text body (ADR-023 D6).
    vi.mocked(fetch).mockResolvedValue(textResponse("Invalid CORS request", 403));

    await expect(apiFetch("/contracts")).rejects.toMatchObject({
      code: "HTTP_403",
      message: "Invalid CORS request",
      status: 403,
    });
  });

  it("refreshes once and replays the original request after a 401", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ data: { access_token: NEW_TOKEN, refresh_token: "refresh-2" }, error: null });
      }
      if (bearer(init?.headers) === `Bearer ${NEW_TOKEN}`) {
        return jsonResponse({ data: [{ id: "c1" }], error: null });
      }
      return errorResponse("UNAUTHORIZED", "Token kedaluwarsa.", 401);
    });

    await expect(apiFetch("/contracts")).resolves.toEqual([{ id: "c1" }]);
    expect(getAccessToken()).toBe(NEW_TOKEN);
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"))).toHaveLength(1);
  });

  it("shares one refresh between concurrent 401s, because rotation is single-use", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockImplementation(async (input, init) => {
      const url = String(input);
      if (url.endsWith("/auth/refresh")) {
        return jsonResponse({ data: { access_token: NEW_TOKEN, refresh_token: "refresh-2" }, error: null });
      }
      if (bearer(init?.headers) === `Bearer ${NEW_TOKEN}`) {
        return jsonResponse({ data: url.includes("installments") ? [] : { id: "c1" }, error: null });
      }
      return errorResponse("UNAUTHORIZED", "Token kedaluwarsa.", 401);
    });

    const [contract, installments] = await Promise.all([
      apiFetch<{ id: string }>("/contracts/c1"),
      apiFetch<unknown[]>("/contracts/c1/installments"),
    ]);

    expect(contract).toEqual({ id: "c1" });
    expect(installments).toEqual([]);
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"))).toHaveLength(1);
  });

  it("does not loop when the replay is rejected again, and ends the session", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).endsWith("/auth/refresh")) {
        return jsonResponse({ data: { access_token: NEW_TOKEN, refresh_token: "refresh-2" }, error: null });
      }
      return errorResponse("UNAUTHORIZED", "Token ditolak.", 401);
    });

    await expect(apiFetch("/contracts")).rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => String(url).endsWith("/auth/refresh"))).toHaveLength(1);
    // A freshly rotated token rejected again means rotating once more cannot help (inactive user).
    expect(getSession().authenticated).toBe(false);
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
  });

  it("does not try to refresh a 401 from a public auth path", async () => {
    vi.mocked(fetch).mockResolvedValue(errorResponse("UNAUTHORIZED", "Username atau password salah.", 401));

    await expect(
      apiFetch("/auth/login", { method: "POST", body: JSON.stringify({ username: "admin", password: "wrong" }) }),
    ).rejects.toMatchObject({ status: 401, code: "UNAUTHORIZED" });
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(redirectToLogin).not.toHaveBeenCalled();
  });

  it("clears the session and sends the operator to /login when the refresh fails", async () => {
    setSession({ access_token: OLD_TOKEN, refresh_token: "refresh-1" }, "admin");
    vi.mocked(fetch).mockImplementation(async (input) => {
      if (String(input).endsWith("/auth/refresh")) {
        return errorResponse("UNAUTHORIZED", "Refresh token tidak berlaku.", 401);
      }
      return errorResponse("UNAUTHORIZED", "Token kedaluwarsa.", 401);
    });

    await expect(apiFetch("/contracts")).rejects.toBeInstanceOf(ApiError);
    expect(getSession().authenticated).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
  });
});

describe("refreshAccessToken", () => {
  beforeEach(() => {
    clearSession();
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    clearSession();
  });

  it("rejects without a request when no refresh token is held", async () => {
    await expect(refreshAccessToken()).rejects.toMatchObject({ code: "NO_SESSION" });
    expect(fetch).not.toHaveBeenCalled();
    expect(redirectToLogin).toHaveBeenCalledTimes(1);
  });
});
