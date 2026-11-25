import { useState } from "react";

import { newIdempotencyKey } from "@/lib/idempotency";

/**
 * One `Idempotency-Key` per form mount (FE §1.4): generated when the page opens, not on submit, so a
 * double submit or a retry after a timeout replays the original write instead of creating a second
 * one (ADR-007). The key survives a plain-HTTP context, where `crypto.randomUUID()` is unavailable.
 */
export function useIdempotencyKey(): string {
  const [key] = useState(() => newIdempotencyKey());
  return key;
}
