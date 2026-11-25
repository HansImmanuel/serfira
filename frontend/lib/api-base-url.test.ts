import { afterEach, describe, expect, it, vi } from "vitest";

import { assertApiBaseUrlForBuild, resolveApiBaseUrl, validateApiBaseUrl } from "./api-base-url";

describe("resolveApiBaseUrl", () => {
  it("falls back to localhost only outside a production build", () => {
    expect(resolveApiBaseUrl(undefined, "development")).toBe("http://localhost:8080");
    expect(resolveApiBaseUrl("   ", "test")).toBe("http://localhost:8080");
  });

  it("fails a production build instead of silently pointing at localhost", () => {
    expect(() => resolveApiBaseUrl(undefined, "production")).toThrow(/NEXT_PUBLIC_API_BASE_URL/);
    expect(() => resolveApiBaseUrl("", "production")).toThrow(/NEXT_PUBLIC_API_BASE_URL/);
  });

  it("uses an explicit origin in every environment", () => {
    expect(resolveApiBaseUrl("https://api.serfira.example", "production")).toBe("https://api.serfira.example");
    expect(resolveApiBaseUrl("http://localhost:8080/", "development")).toBe("http://localhost:8080");
  });
});

describe("validateApiBaseUrl", () => {
  it("accepts http and https origins and strips trailing slashes", () => {
    expect(validateApiBaseUrl("https://api.example.com///")).toBe("https://api.example.com");
    expect(validateApiBaseUrl(" http://127.0.0.1:8080 ")).toBe("http://127.0.0.1:8080");
  });

  it("keeps an intentional path prefix", () => {
    expect(validateApiBaseUrl("https://example.com/serfira-api/")).toBe("https://example.com/serfira-api");
  });

  it("rejects anything that is not an absolute http(s) origin", () => {
    expect(() => validateApiBaseUrl("/api")).toThrow(/absolute URL/);
    expect(() => validateApiBaseUrl("api.example.com")).toThrow(/absolute URL/);
    expect(() => validateApiBaseUrl("ftp://example.com")).toThrow(/http or https/);
    expect(() => validateApiBaseUrl("https://user:secret@example.com")).toThrow(/credentials/);
    expect(() => validateApiBaseUrl("https://example.com?x=1")).toThrow(/query strings/);
    expect(() => validateApiBaseUrl("https://example.com#fragment")).toThrow(/query strings/);
  });
});

describe("assertApiBaseUrlForBuild", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("does not throw when the build defines the API origin", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://ci-build.invalid");

    expect(() => assertApiBaseUrlForBuild()).not.toThrow();
  });

  it("throws when the build leaves it unset", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "");

    expect(() => assertApiBaseUrlForBuild()).toThrow(/NEXT_PUBLIC_API_BASE_URL/);
  });
});
