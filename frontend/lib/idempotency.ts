import { ApiError } from "@/lib/api";

/**
 * Idempotency-key generation (ADR-007, FE §1.4).
 *
 * <p>`crypto.randomUUID()` is only exposed in a secure context, so a plain-HTTP staging deployment
 * would throw on the first form render. The fallback builds the same RFC 4122 v4 shape from
 * `crypto.getRandomValues()`, which is available in insecure contexts. `Math.random()` is never
 * used: an idempotency key must not be predictable.
 */

/** One new RFC 4122 version-4 key. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return uuidV4FromRandomValues();
}

function uuidV4FromRandomValues(): string {
  if (typeof crypto === "undefined" || typeof crypto.getRandomValues !== "function") {
    throw new Error("A cryptographically secure random source is required to generate an Idempotency-Key.");
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  // Version 4 (random) and the RFC 4122 variant bits.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Codes that mean the key this form holds can no longer be used for the request in front of the operator:
 * the key was already used for a different body (409 `CONFLICT`) or is past its retention window
 * (`IDEMPOTENCY_KEY_EXPIRED`, ADR-017). Retrying with the same key cannot succeed, and silently minting a
 * new one could repeat money movement if the first attempt did commit — so the form offers an explicit
 * restart after the operator has checked the outcome.
 */
const SPENT_KEY_CODES: ReadonlySet<string> = new Set(["CONFLICT", "IDEMPOTENCY_KEY_EXPIRED"]);

export function isSpentKeyError(error: unknown): error is ApiError {
  return error instanceof ApiError && SPENT_KEY_CODES.has(error.code);
}
