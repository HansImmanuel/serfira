/**
 * The single HTTP entry point (FE §1.3, ADR-023 D1).
 *
 * <p>Every call goes straight from the browser to `NEXT_PUBLIC_API_BASE_URL`; there is no BFF or
 * proxy. This module is the only place that knows the `{data, error}` envelope, the bearer header
 * and the refresh-and-replay rule, so components never see a raw response.
 */

import type { ApiResponse } from "@/types/api";
import type { TokenPair } from "@/types/auth";

import { redirectToLogin } from "./navigation";
import { clearSession, getAccessToken, getRefreshToken, updateTokens } from "./session";

/**
 * The public token endpoints never carry an access token (ADR-022 D7): `permitAll` covers
 * authorization only, and a stale bearer header would be rejected before the controller runs.
 */
export const PUBLIC_AUTH_PATHS: readonly string[] = ["/auth/login", "/auth/refresh", "/auth/logout"];

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

export interface ApiFetchOptions extends Omit<RequestInit, "headers"> {
  headers?: Record<string, string>;
}

export function apiUrl(path: string): string {
  return `${API_BASE_URL.replace(/\/+$/, "")}/api/v1${path}`;
}

interface RawResult<T> {
  ok: boolean;
  status: number;
  body: ApiResponse<T> | null;
  text: string;
}

async function send<T>(path: string, options: ApiFetchOptions): Promise<RawResult<T>> {
  const { headers, ...init } = options;
  const token = getAccessToken();
  const requestHeaders: Record<string, string> = {
    "Content-Type": "application/json",
    ...headers,
  };
  if (token !== null && !PUBLIC_AUTH_PATHS.includes(path)) {
    requestHeaders.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(apiUrl(path), { ...init, headers: requestHeaders });
  const text = await response.text();
  let body: ApiResponse<T> | null = null;
  if (text.length > 0) {
    try {
      body = JSON.parse(text) as ApiResponse<T>;
    } catch {
      // A CORS rejection or a gateway error is not the API envelope; keep the text for the message.
      body = null;
    }
  }
  return { ok: response.ok, status: response.status, body, text };
}

function failure<T>(result: RawResult<T>): ApiError {
  if (result.body?.error) {
    return new ApiError(result.body.error.code, result.body.error.message, result.status);
  }
  return new ApiError(
    `HTTP_${result.status}`,
    result.text.trim() || `Permintaan gagal dengan status ${result.status}.`,
    result.status,
  );
}

/**
 * Calls the API and returns the unwrapped `data`.
 *
 * <p>A 401 on a protected path rotates the token pair once and replays the request; a 401 on a
 * public auth path is the answer itself and is never retried. A failed refresh clears the session
 * and sends the operator to `/login`.
 */
export async function apiFetch<T>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  return request<T>(path, options, false);
}

/**
 * @param alreadyRetried set on the replay so a second 401 cannot start another refresh and loop
 */
async function request<T>(path: string, options: ApiFetchOptions, alreadyRetried: boolean): Promise<T> {
  const result = await send<T>(path, options);

  if (result.status === 401 && !PUBLIC_AUTH_PATHS.includes(path)) {
    if (alreadyRetried) {
      // A token minted moments ago was rejected again: the session cannot be recovered by rotating
      // once more (an inactive user answers 401 forever), so end it instead of looping.
      expireSession();
    } else {
      await refreshAccessToken();
      return request<T>(path, options, true);
    }
  }
  if (!result.ok || result.body?.error) {
    throw failure(result);
  }
  return (result.body?.data ?? null) as T;
}

let refreshInFlight: Promise<string> | null = null;

/**
 * Rotates the token pair, sharing one in-flight promise between concurrent callers.
 *
 * <p>The refresh token is single-use (ADR-022 D3), so a second parallel rotation would present an
 * already-revoked value and log the operator out. Every caller waits on the same promise.
 */
export function refreshAccessToken(): Promise<string> {
  if (refreshInFlight !== null) {
    return refreshInFlight;
  }
  const refreshToken = getRefreshToken();
  if (refreshToken === null) {
    expireSession();
    return Promise.reject(new ApiError("NO_SESSION", "Sesi tidak ditemukan. Silakan masuk kembali.", 401));
  }

  refreshInFlight = (async () => {
    try {
      const result = await send<TokenPair>("/auth/refresh", {
        method: "POST",
        body: JSON.stringify({ refresh_token: refreshToken }),
      });
      if (!result.ok || result.body?.data == null) {
        throw failure(result);
      }
      updateTokens(result.body.data);
      return result.body.data.access_token;
    } catch (error) {
      expireSession();
      throw error;
    } finally {
      refreshInFlight = null;
    }
  })();

  return refreshInFlight;
}

function expireSession(): void {
  clearSession();
  redirectToLogin();
}
