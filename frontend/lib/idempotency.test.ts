import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "./api";
import { isOutcomeUnknown, newIdempotencyKey } from "./idempotency";

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("newIdempotencyKey", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("prefers crypto.randomUUID", () => {
    vi.stubGlobal("crypto", { randomUUID: () => "11111111-1111-4111-8111-111111111111" });

    expect(newIdempotencyKey()).toBe("11111111-1111-4111-8111-111111111111");
  });

  it("builds an RFC 4122 v4 key from getRandomValues when randomUUID is unavailable", () => {
    // `crypto.randomUUID()` is only exposed in a secure context; getRandomValues is not.
    vi.stubGlobal("crypto", {
      getRandomValues: (bytes: Uint8Array) => {
        for (let index = 0; index < bytes.length; index += 1) {
          bytes[index] = index;
        }
        return bytes;
      },
    });

    const key = newIdempotencyKey();

    expect(key).toBe("00010203-0405-4607-8809-0a0b0c0d0e0f");
    expect(key).toMatch(UUID_V4);
  });

  it("throws instead of falling back to a predictable Math.random value", () => {
    vi.stubGlobal("crypto", undefined);

    expect(() => newIdempotencyKey()).toThrow(/secure random/);
  });
});

describe("isOutcomeUnknown", () => {
  it("leaves the outcome unknown after a network error or a 5xx", () => {
    expect(isOutcomeUnknown(new TypeError("Failed to fetch"), false)).toBe(true);
    expect(isOutcomeUnknown(new ApiError("SERVICE_UNAVAILABLE", "Layanan tidak tersedia", 503), false)).toBe(true);
  });

  it("takes a 4xx on a first attempt as a definite answer, 401 and 403 included", () => {
    expect(isOutcomeUnknown(new ApiError("CONTRACT_STATE_INVALID", "Bukan ACTIVE", 409), false)).toBe(false);
    expect(isOutcomeUnknown(new ApiError("UNAUTHORIZED", "Sesi berakhir", 401), false)).toBe(false);
    expect(isOutcomeUnknown(new ApiError("FORBIDDEN", "Akses ditolak", 403), false)).toBe(false);
  });

  it("keeps an earlier unknown outcome when the retry is turned away with 401 or 403", () => {
    expect(isOutcomeUnknown(new ApiError("UNAUTHORIZED", "Sesi berakhir", 401), true)).toBe(true);
    expect(isOutcomeUnknown(new ApiError("FORBIDDEN", "Akses ditolak", 403), true)).toBe(true);
  });

  it("settles an earlier unknown outcome with any other 4xx, a spent key included", () => {
    expect(isOutcomeUnknown(new ApiError("CONTRACT_STATE_INVALID", "Bukan ACTIVE", 409), true)).toBe(false);
    expect(isOutcomeUnknown(new ApiError("CONFLICT", "Kunci sudah dipakai", 409), true)).toBe(false);
  });
});
