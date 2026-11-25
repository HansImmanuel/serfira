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
