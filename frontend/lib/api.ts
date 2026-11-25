/**
 * The single HTTP entry point (FE §1.3, ADR-023 D1).
 *
 * <p>Every call goes straight from the browser to `NEXT_PUBLIC_API_BASE_URL`; there is no BFF or
 * proxy. This module is the only place that knows the `{data, error}` envelope, the bearer header
 * and the refresh-and-replay rule, so components never see a raw response.
 *
 * <p>Session safety: every protected call captures the session generation before it starts. A
 * response that arrives after a login/logout replaced the session is rejected rather than returned,
 * and a refresh that belongs to a replaced session is never applied (review findings: stale refresh
 * overwriting a newer session, and a stale request replaying with another operator's credentials).
 */

import type { ApiResponse } from "@/types/api";
import type { TokenPair } from "@/types/auth";

import { resolveApiBaseUrl } from "./api-base-url";
import { redirectToLogin } from "./navigation";
import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  getSessionGeneration,
  updateTokens,
} from "./session";

/**
 * The public token endpoints never carry an access token (ADR-022 D7): `permitAll` covers
 * authorization only, and a stale bearer header would be rejected before the controller runs.
 */
export const PUBLIC_AUTH_PATHS: readonly string[] = ["/auth/login", "/auth/refresh", "/auth/logout"];

const API_BASE_URL = resolveApiBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL, process.env.NODE_ENV);

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

function sessionChanged(): ApiError {
  return new ApiError(
    "SESSION_CHANGED",
    "Sesi berubah saat permintaan berlangsung. Silakan ulangi.",
    401,
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
  const isPublic = PUBLIC_AUTH_PATHS.includes(path);
  const generation = getSessionGeneration();
  const result = await send<T>(path, options);

  // A protected response that resolves after the session was replaced belongs to the previous
  // operator. Returning it would leak data across a login and repopulate the new operator's cache.
  if (!isPublic && generation !== getSessionGeneration()) {
    throw sessionChanged();
  }

  if (result.status === 401 && !isPublic) {
    if (alreadyRetried) {
      // A token minted moments ago was rejected again: the session cannot be recovered by rotating
      // once more (an inactive user answers 401 forever), so end it instead of looping.
      expireSession(generation);
    } else {
      await refreshAccessToken();
      // The refresh may have ended or replaced this session; never replay with someone else's token.
      if (generation !== getSessionGeneration()) {
        throw sessionChanged();
      }
      return request<T>(path, options, true);
    }
  }
  if (!result.ok || result.body?.error) {
    throw failure(result);
  }
  return (result.body?.data ?? null) as T;
}

interface RefreshFlight {
  generation: number;
  promise: Promise<string>;
}

let refreshInFlight: RefreshFlight | null = null;

/**
 * Rotates the token pair, sharing one in-flight promise between concurrent callers **of the same
 * session**.
 *
 * <p>The refresh token is single-use (ADR-022 D3), so a second parallel rotation would present an
 * already-revoked value and log the operator out. Every caller in the same generation waits on the
 * same promise; a flight left over from a previous session is ignored so it cannot block a new
 * login's refresh.
 */
export function refreshAccessToken(): Promise<string> {
  const generation = getSessionGeneration();
  if (refreshInFlight !== null && refreshInFlight.generation === generation) {
    return refreshInFlight.promise;
  }
  const refreshToken = getRefreshToken();
  if (refreshToken === null) {
    expireSession(generation);
    return Promise.reject(new ApiError("NO_SESSION", "Sesi tidak ditemukan. Silakan masuk kembali.", 401));
  }

  const flight: RefreshFlight = { generation, promise: Promise.resolve("") };
  flight.promise = (async () => {
    try {
      const result = await send<TokenPair>("/auth/refresh", {
        method: "POST",
        body: JSON.stringify({ refresh_token: refreshToken }),
      });
      if (!result.ok || result.body?.data == null) {
        throw failure(result);
      }
      if (!updateTokens(result.body.data, generation)) {
        // A newer login owns the session now; this rotation must not touch it.
        throw sessionChanged();
      }
      return result.body.data.access_token;
    } catch (error) {
      // Only the session that started the rotation may be ended by its failure.
      if (generation === getSessionGeneration()) {
        expireSession(generation);
      }
      throw error;
    } finally {
      if (refreshInFlight === flight) {
        refreshInFlight = null;
      }
    }
  })();

  refreshInFlight = flight;
  return flight.promise;
}

/** Ends the session only if it is still the one that hit the failure. */
function expireSession(expectedGeneration: number): void {
  if (getSessionGeneration() !== expectedGeneration) {
    return;
  }
  clearSession();
  redirectToLogin();
}
