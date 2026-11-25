import { afterEach, describe, expect, it, vi } from "vitest";

import { newIdempotencyKey } from "./idempotency";

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
