/**
 * Build-time resolution of `NEXT_PUBLIC_API_BASE_URL` (review finding: a production build silently
 * fell back to `http://localhost:8080`).
 *
 * <p>Next.js inlines `NEXT_PUBLIC_*` values at **build** time, so the value baked into the bundle is
 * the one present when `next build` ran — not the one present when `next start` runs. The localhost
 * default is therefore allowed only for development; a production build without an explicit origin
 * fails instead of shipping a client that cannot reach the API.
 */

const DEVELOPMENT_DEFAULT = "http://localhost:8080";

function invalid(value: string, reason: string): Error {
  return new Error(`NEXT_PUBLIC_API_BASE_URL is invalid (${reason}): ${JSON.stringify(value)}`);
}

/**
 * Validates an explicit API origin. Accepts an absolute `http`/`https` URL with no credentials,
 * query or fragment; trailing slashes are removed so `apiUrl` can append the path directly.
 */
export function validateApiBaseUrl(value: string): string {
  const trimmed = value.trim();
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    throw invalid(value, "not an absolute URL");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw invalid(value, "protocol must be http or https");
  }
  if (url.username !== "" || url.password !== "") {
    throw invalid(value, "credentials are not allowed");
  }
  if (url.search !== "" || url.hash !== "") {
    throw invalid(value, "query strings and fragments are not allowed");
  }
  return `${url.origin}${url.pathname}`.replace(/\/+$/, "");
}

/**
 * Resolves the base URL for the current environment.
 *
 * @param raw the inlined `NEXT_PUBLIC_API_BASE_URL` (may be `undefined` when unset)
 * @param nodeEnv `process.env.NODE_ENV`; production requires an explicit value
 */
export function resolveApiBaseUrl(raw: string | undefined, nodeEnv: string | undefined): string {
  const value = raw?.trim() ?? "";
  if (value === "") {
    if (nodeEnv === "production") {
      throw new Error(
        "NEXT_PUBLIC_API_BASE_URL must be set for a production build; it is inlined at build time.",
      );
    }
    return DEVELOPMENT_DEFAULT;
  }
  return validateApiBaseUrl(value);
}

/**
 * Fails the production build early (from `next.config.ts`) when the API origin is missing.
 *
 * <p>Only `next build` is guarded: `next typegen` and `next dev` run with `NODE_ENV=production` /
 * `development` in ways that must not require a deployed origin.
 */
export function assertApiBaseUrlForBuild(): void {
  resolveApiBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL, "production");
}
