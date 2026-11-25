import { beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "./session";
import { registerSessionCacheCleaner } from "./session-cache";

describe("session cache ownership", () => {
  beforeEach(() => {
    clearSession();
  });

  it("drops cached responses on login and logout, and stops once unregistered", () => {
    const cleaner = vi.fn();
    const unregister = registerSessionCacheCleaner(cleaner);

    setSession({ access_token: "access-1", refresh_token: "refresh-1" }, "admin");
    expect(cleaner).toHaveBeenCalledTimes(1);

    clearSession();
    expect(cleaner).toHaveBeenCalledTimes(2);

    unregister();
    setSession({ access_token: "access-2", refresh_token: "refresh-2" }, "finance");
    expect(cleaner).toHaveBeenCalledTimes(2);
  });
});
