import { useState } from "react";

/**
 * One `Idempotency-Key` per form mount (FE §1.4): generated when the page opens, not on submit, so a
 * double submit or a retry after a timeout replays the original write instead of creating a second
 * one (ADR-007).
 */
export function useIdempotencyKey(): string {
  const [key] = useState(() => crypto.randomUUID());
  return key;
}
