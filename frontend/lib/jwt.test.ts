import { describe, expect, it } from "vitest";

import { decodeJwtClaims } from "./jwt";

function encodePayload(payload: unknown): string {
  const json = JSON.stringify(payload);
  const base64 = btoa(json).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `header.${base64}.signature`;
}

describe("decodeJwtClaims", () => {
  it("reads sub, roles and exp from the payload", () => {
    const token = encodePayload({
      sub: "6f1a0f36-0c1a-4b3f-9f3f-2f1f6f4b0a11",
      roles: ["ADMIN_OPERASIONAL"],
      exp: 1795000000,
    });

    expect(decodeJwtClaims(token)).toEqual({
      sub: "6f1a0f36-0c1a-4b3f-9f3f-2f1f6f4b0a11",
      roles: ["ADMIN_OPERASIONAL"],
      exp: 1795000000,
    });
  });

  it("accepts a base64url payload with - and _ characters", () => {
    const token = encodePayload({ sub: "id", roles: ["FINANCE"], exp: 1, note: "???>>><<<~~~" });
    expect(token.split(".")[1]).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(decodeJwtClaims(token)?.roles).toEqual(["FINANCE"]);
  });

  it("returns null for a token that is not a three-part JWT", () => {
    expect(decodeJwtClaims("not-a-token")).toBeNull();
    expect(decodeJwtClaims("")).toBeNull();
  });

  it("returns null when the payload is not decodable JSON", () => {
    expect(decodeJwtClaims("header.%%%.signature")).toBeNull();
  });

  it("returns null when the claims lack a usable sub, roles array or exp", () => {
    expect(decodeJwtClaims(encodePayload({ roles: ["FINANCE"], exp: 1 }))).toBeNull();
    expect(decodeJwtClaims(encodePayload({ sub: "id", exp: 1 }))).toBeNull();
    expect(decodeJwtClaims(encodePayload({ sub: "id", roles: "FINANCE", exp: 1 }))).toBeNull();
    expect(decodeJwtClaims(encodePayload({ sub: "id", roles: [1], exp: 1 }))).toBeNull();
    expect(decodeJwtClaims(encodePayload({ sub: "id", roles: ["FINANCE"] }))).toBeNull();
  });
});
