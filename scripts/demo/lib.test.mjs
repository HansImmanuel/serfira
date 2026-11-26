import { test } from "node:test";
import assert from "node:assert/strict";

import {
  DemoError,
  createApiClient,
  jakartaToday,
  minusDays,
  isLoopbackUrl,
  isRatioOrNull,
  minusMonths,
  requireCents,
  runTag,
  startLocalStack,
  sumMoney,
  toCents,
  waitForHealth,
} from "./lib.mjs";

test("jakartaToday crosses midnight ahead of UTC", () => {
  assert.equal(jakartaToday(new Date("2026-11-25T18:30:00Z")), "2026-11-26");
  assert.equal(jakartaToday(new Date("2026-11-25T16:59:59Z")), "2026-11-25");
});

test("minusMonths clamps to month end", () => {
  assert.equal(minusMonths("2027-03-31", 1), "2027-02-28");
  assert.equal(minusMonths("2028-03-31", 1), "2028-02-29");
  assert.equal(minusMonths("2026-01-15", 1), "2025-12-15");
});

test("minusDays crosses month and year", () => {
  assert.equal(minusDays("2026-11-25", 70), "2026-09-16");
  assert.equal(minusDays("2026-01-05", 10), "2025-12-26");
});

test("toCents is exact and strict", () => {
  assert.equal(toCents("1573333.33"), 157333333n);
  assert.equal(toCents("240000"), 24000000n);
  assert.equal(toCents("0.5"), 50n);
  assert.equal(toCents("-1.25"), -125n);
  assert.equal(toCents(null), 0n);
  assert.equal(toCents(undefined), 0n);
  assert.throws(() => toCents("1e6"));
  assert.throws(() => toCents("1.234"));
  assert.throws(() => toCents(1.5));
});

test("requireCents converts a present decimal string exactly", () => {
  assert.equal(requireCents("4769959.90", "x"), 476995990n);
  assert.equal(requireCents("0.00", "x"), 0n);
  assert.equal(requireCents("0", "x"), 0n);
  assert.equal(requireCents("-1.25", "x"), -125n);
});

test("requireCents rejects a missing field instead of reading it as zero", () => {
  assert.throws(() => requireCents(undefined, "payment.excess_amount"), /payment\.excess_amount is missing/);
  assert.throws(() => requireCents(null, "credit.total_credit"), /credit\.total_credit is missing/);
});

test("requireCents rejects numbers and malformed decimals", () => {
  assert.throws(() => requireCents(0, "x"));
  assert.throws(() => requireCents(1.5, "x"));
  assert.throws(() => requireCents("", "x"));
  assert.throws(() => requireCents("abc", "x"));
  assert.throws(() => requireCents("1e6", "x"));
  assert.throws(() => requireCents("1.234", "x"));
});

test("toCents still reads an absent optional amount as zero", () => {
  assert.equal(toCents(null), 0n);
  assert.equal(toCents(undefined), 0n);
  assert.equal(toCents("0.00"), 0n);
});

test("sumMoney adds without float drift", () => {
  assert.equal(sumMoney(["0.10", "0.20"]), "0.30");
  assert.equal(sumMoney(["1125000.00", "3644959.90"]), "4769959.90");
  assert.equal(sumMoney([]), "0.00");
});

test("isRatioOrNull accepts a scale-4 decimal string or null", () => {
  for (const ratio of ["0.0000", "0.1234", "1.0000", "12.5000", null]) {
    assert.equal(isRatioOrNull(ratio), true, `${JSON.stringify(ratio)} is a valid ratio`);
  }
});

test("isRatioOrNull rejects JSON numbers, other types, other scales and malformed strings", () => {
  for (const ratio of [0.1234, 1, 0, undefined, true, ["0.1234"], "0.123", "0.12345", "0.12", "1", ".1234", "-0.1234", "0,1234", " 0.1234", "abc", ""]) {
    assert.equal(isRatioOrNull(ratio), false, `${typeof ratio} ${JSON.stringify(ratio)} is not a valid ratio`);
  }
});

test("runTag is a UTC timestamp", () => {
  assert.equal(runTag(new Date("2026-11-25T18:30:05Z")), "20261125183005");
});

/** A fetch double that records every call and answers from a queue of responses. */
function fakeFetch(...responses) {
  const calls = [];
  const impl = async (url, init) => {
    calls.push({ url, init });
    const next = responses.shift();
    if (next instanceof Error) {
      throw next;
    }
    const text = typeof next.body === "string" ? next.body : JSON.stringify(next.body);
    return new Response(text, { status: next.status });
  };
  return { impl, calls };
}

test("request sends auth, idempotency key and JSON body", async () => {
  const { impl, calls } = fakeFetch({ status: 201, body: { data: { id: "c1" }, error: null } });
  const api = createApiClient({ baseUrl: "http://api.test/", fetchImpl: impl });

  const data = await api.request("POST", "/api/v1/payments", {
    token: "tok",
    idempotencyKey: "key-1",
    body: { amount: "1.00" },
    expectStatus: 201,
  });

  assert.deepEqual(data, { id: "c1" });
  assert.equal(calls[0].url, "http://api.test/api/v1/payments");
  assert.equal(calls[0].init.method, "POST");
  assert.equal(calls[0].init.headers.Authorization, "Bearer tok");
  assert.equal(calls[0].init.headers["Idempotency-Key"], "key-1");
  assert.equal(calls[0].init.headers["Content-Type"], "application/json");
  assert.equal(calls[0].init.body, JSON.stringify({ amount: "1.00" }));
});

test("request omits auth and key headers when not given", async () => {
  const { impl, calls } = fakeFetch({ status: 200, body: { data: { ok: true }, error: null } });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  await api.request("GET", "/x");

  assert.equal(calls[0].init.headers.Authorization, undefined);
  assert.equal(calls[0].init.headers["Idempotency-Key"], undefined);
  assert.equal(calls[0].init.body, undefined);
});

test("request returns status and error when a non-2xx status is expected", async () => {
  const { impl } = fakeFetch({ status: 403, body: { data: null, error: { code: "FORBIDDEN" } } });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  const result = await api.request("POST", "/api/v1/payments", { expectStatus: 403 });

  assert.deepEqual(result, { status: 403, error: { code: "FORBIDDEN" } });
});

test("request throws DemoError with the raw body on an unexpected status", async () => {
  const { impl } = fakeFetch({ status: 409, body: { data: null, error: { code: "DUPLICATE_CONTRACT" } } });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  await assert.rejects(api.request("POST", "/api/v1/contracts", { expectStatus: 201 }), (err) => {
    assert.ok(err instanceof DemoError);
    assert.equal(err.method, "POST");
    assert.equal(err.path, "/api/v1/contracts");
    assert.equal(err.status, 409);
    assert.match(err.body, /DUPLICATE_CONTRACT/);
    return true;
  });
});

test("request throws DemoError when a 2xx carries an error envelope", async () => {
  const { impl } = fakeFetch({ status: 200, body: { data: null, error: { code: "X" } } });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  await assert.rejects(api.request("GET", "/x"), (err) => err instanceof DemoError && err.status === 200);
});

test("request throws DemoError with the raw text on a non-JSON body", async () => {
  const { impl } = fakeFetch({ status: 502, body: "<html>bad gateway</html>" });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  await assert.rejects(api.request("GET", "/x"), (err) => {
    assert.ok(err instanceof DemoError);
    assert.equal(err.status, 502);
    assert.equal(err.body, "<html>bad gateway</html>");
    return true;
  });
});

test("request throws DemoError when a 200 body is not JSON", async () => {
  const { impl } = fakeFetch({ status: 200, body: "not json" });
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: impl });

  await assert.rejects(api.request("GET", "/x"), (err) => err instanceof DemoError && err.body === "not json");
});

/** A fetch that never answers, like an accepted connection on a hung server; it only honours its abort signal. */
const hangingFetch = (url, init) =>
  new Promise((resolve, reject) => {
    // AbortSignal.timeout() is unref'd, so hold the event loop open until it fires; older Node 22 test
    // runners would otherwise cancel the test as "event loop has already resolved".
    const keepAlive = setInterval(() => {}, 1 << 30);
    init?.signal?.addEventListener("abort", () => {
      clearInterval(keepAlive);
      reject(init.signal.reason);
    });
  });

test("request fails with a DemoError when the server never answers", async () => {
  const api = createApiClient({ baseUrl: "http://api.test", fetchImpl: hangingFetch, timeoutMs: 50 });

  await assert.rejects(api.request("GET", "/x"), (err) => {
    assert.ok(err instanceof DemoError);
    assert.equal(err.path, "/x");
    assert.match(err.message, /no response within 50 ms/);
    return true;
  });
});

test("waitForHealth gives up at the deadline when every attempt hangs", async () => {
  await assert.rejects(
    waitForHealth({ baseUrl: "http://api.test", fetchImpl: hangingFetch, timeoutMs: 120, intervalMs: 10, attemptTimeoutMs: 30 }),
    (err) => err instanceof DemoError && err.path === "/actuator/health",
  );
});

test("waitForHealth tolerates connection errors until UP", async () => {
  const { impl, calls } = fakeFetch(
    new TypeError("fetch failed"),
    new TypeError("fetch failed"),
    { status: 200, body: { status: "UP" } },
  );
  let sleeps = 0;

  await waitForHealth({
    baseUrl: "http://api.test",
    fetchImpl: impl,
    intervalMs: 1,
    sleep: async () => {
      sleeps += 1;
    },
  });

  assert.equal(calls.length, 3);
  assert.equal(calls[0].url, "http://api.test/actuator/health");
  assert.equal(sleeps, 2);
});

test("waitForHealth gives up at the deadline with a DemoError", async () => {
  let clock = 0;
  const impl = async () => {
    throw new TypeError("connect ECONNREFUSED");
  };

  await assert.rejects(
    waitForHealth({
      baseUrl: "http://api.test",
      fetchImpl: impl,
      timeoutMs: 10_000,
      intervalMs: 5_000,
      now: () => clock,
      sleep: async (ms) => {
        clock += ms;
      },
    }),
    (err) => {
      assert.ok(err instanceof DemoError);
      assert.equal(err.path, "/actuator/health");
      assert.match(err.message, /ECONNREFUSED/);
      return true;
    },
  );
});

test("isLoopbackUrl accepts only loopback hosts, by parsed hostname", () => {
  assert.equal(isLoopbackUrl("http://localhost:8080"), true);
  assert.equal(isLoopbackUrl("http://LOCALHOST:8080/"), true);
  assert.equal(isLoopbackUrl("http://127.0.0.1:8080"), true);
  assert.equal(isLoopbackUrl("http://[::1]:8080"), true);
  assert.equal(isLoopbackUrl("https://production.example.com"), false);
  assert.equal(isLoopbackUrl("http://localhost.example.com:8080"), false);
  assert.equal(isLoopbackUrl("http://127.0.0.1.example.com"), false);
  assert.equal(isLoopbackUrl("http://user@evil.test/localhost"), false);
  assert.equal(isLoopbackUrl("not a url"), false);
  assert.equal(isLoopbackUrl(""), false);
});

/** A compose double that records every command instead of running Docker. */
function recordingCompose() {
  const commands = [];
  return { commands, compose: (...args) => commands.push(args.join(" ")) };
}

test("startLocalStack refuses --up against a remote base URL without running compose", () => {
  const { commands, compose } = recordingCompose();

  assert.throws(
    () => startLocalStack({ up: true, reset: false, baseUrl: "https://production.example.com" }, { compose }),
    /not a loopback address/,
  );
  assert.deepEqual(commands, []);
});

test("startLocalStack refuses --reset against a remote base URL before docker compose down -v", () => {
  const { commands, compose } = recordingCompose();

  assert.throws(
    () => startLocalStack({ up: true, reset: true, baseUrl: "https://production.example.com" }, { compose }),
    /not a loopback address/,
  );
  assert.deepEqual(commands, []);
});

test("startLocalStack refuses --reset with an unparseable base URL", () => {
  const { commands, compose } = recordingCompose();

  assert.throws(() => startLocalStack({ up: true, reset: true, baseUrl: "localhost:8080" }, { compose }));
  assert.deepEqual(commands, []);
});

test("startLocalStack runs up for --up against a local base URL", () => {
  const { commands, compose } = recordingCompose();

  startLocalStack({ up: true, reset: false, baseUrl: "http://localhost:8080" }, { compose });

  assert.deepEqual(commands, ["up --build -d"]);
});

test("startLocalStack runs down -v then up for --reset against a local base URL", () => {
  const { commands, compose } = recordingCompose();

  startLocalStack({ up: true, reset: true, baseUrl: "http://127.0.0.1:8080" }, { compose });

  assert.deepEqual(commands, ["down -v", "up --build -d"]);
});

test("startLocalStack leaves a remote base URL alone when no Compose flag is given", () => {
  const { commands, compose } = recordingCompose();

  startLocalStack({ up: false, reset: false, baseUrl: "https://dev.example.com" }, { compose });

  assert.deepEqual(commands, []);
});
