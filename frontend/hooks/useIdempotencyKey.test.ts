import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { useIdempotencyKey } from "./useIdempotencyKey";

describe("useIdempotencyKey", () => {
  it("generates a UUID on mount", () => {
    const { result } = renderHook(() => useIdempotencyKey());

    expect(result.current).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  it("keeps the same key across re-renders, so a retry replays instead of duplicating", () => {
    const { result, rerender } = renderHook(() => useIdempotencyKey());
    const first = result.current;

    rerender();

    expect(result.current).toBe(first);
  });

  it("gives separate forms separate keys", () => {
    const first = renderHook(() => useIdempotencyKey()).result.current;
    const second = renderHook(() => useIdempotencyKey()).result.current;

    expect(first).not.toBe(second);
  });
});
