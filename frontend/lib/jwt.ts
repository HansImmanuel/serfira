/**
 * Reads the access token's claims for UX only (ADR-023 D3).
 *
 * <p>The signature is deliberately not verified: `roles` decides which buttons the UI shows, never
 * whether a call is allowed — the backend's matcher table (ADR-015) is the enforcement, so a forged
 * claim reveals buttons and nothing else. There is no `/me` endpoint in this phase.
 */

export interface JwtClaims {
  sub: string;
  roles: string[];
  exp: number;
}

const BASE64URL_PATTERN = /^[A-Za-z0-9_-]+$/;

export function decodeJwtClaims(token: string): JwtClaims | null {
  if (!token) {
    return null;
  }
  const parts = token.split(".");
  if (parts.length !== 3 || !BASE64URL_PATTERN.test(parts[1])) {
    return null;
  }
  try {
    const base64 = parts[1].replace(/-/g, "+").replace(/_/g, "/");
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, "=");
    const payload: unknown = JSON.parse(atob(padded));
    if (typeof payload !== "object" || payload === null) {
      return null;
    }
    const claims = payload as Record<string, unknown>;
    const roles = claims.roles;
    if (
      typeof claims.sub !== "string" ||
      claims.sub.length === 0 ||
      typeof claims.exp !== "number" ||
      !Array.isArray(roles) ||
      !roles.every((role) => typeof role === "string")
    ) {
      return null;
    }
    return { sub: claims.sub, roles: roles as string[], exp: claims.exp };
  } catch {
    return null;
  }
}
