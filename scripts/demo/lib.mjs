// Helpers for scripts/demo.mjs (T34). Dependency-free so the demo runs on any Node >= 22.
//
// Money is an exact decimal string on the wire (ADR-024) and is only ever compared as BigInt cents
// here, never as a float. "Today" is the Asia/Jakarta business date (ADR-003), not the machine's zone.

const BUSINESS_ZONE = "Asia/Jakarta";
const MONEY_PATTERN = /^(-?)(\d+)(?:\.(\d{1,2}))?$/;

/** A failed demo step: carries enough of the HTTP exchange to diagnose it without a rerun. */
export class DemoError extends Error {
  constructor(message, { method, path, status, body } = {}) {
    super(message);
    this.name = "DemoError";
    this.method = method;
    this.path = path;
    this.status = status;
    this.body = body;
  }
}

/** The Asia/Jakarta calendar date of {@code now}, as YYYY-MM-DD. */
export function jakartaToday(now = new Date()) {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: BUSINESS_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}

function parseIsoDate(isoDate) {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate);
  if (!match) {
    throw new Error(`not an ISO date: ${isoDate}`);
  }
  return { year: Number(match[1]), month: Number(match[2]), day: Number(match[3]) };
}

function formatUtcDate(date) {
  return date.toISOString().slice(0, 10);
}

/** {@code isoDate} minus {@code days} calendar days. */
export function minusDays(isoDate, days) {
  const { year, month, day } = parseIsoDate(isoDate);
  return formatUtcDate(new Date(Date.UTC(year, month - 1, day - days)));
}

/** {@code isoDate} minus {@code months}, clamped to the last day of the target month. */
export function minusMonths(isoDate, months) {
  const { year, month, day } = parseIsoDate(isoDate);
  const firstOfTarget = new Date(Date.UTC(year, month - 1 - months, 1));
  const lastDay = new Date(Date.UTC(firstOfTarget.getUTCFullYear(), firstOfTarget.getUTCMonth() + 1, 0)).getUTCDate();
  firstOfTarget.setUTCDate(Math.min(day, lastDay));
  return formatUtcDate(firstOfTarget);
}

/** Exact cents of a decimal money string; null/undefined is zero. Rejects floats and scale > 2. */
export function toCents(amount) {
  if (amount === null || amount === undefined) {
    return 0n;
  }
  if (typeof amount !== "string") {
    throw new Error(`money must be a decimal string, got ${typeof amount}: ${amount}`);
  }
  const match = MONEY_PATTERN.exec(amount);
  if (!match) {
    throw new Error(`not a scale<=2 decimal money string: ${amount}`);
  }
  const [, sign, whole, fraction = ""] = match;
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
  return sign === "-" ? -cents : cents;
}

/**
 * Exact cents of a money field the API must return. Unlike {@link toCents}, an absent value is an error,
 * so a field the backend stopped sending can never satisfy a "must be zero" assertion.
 */
export function requireCents(amount, label) {
  if (amount === null || amount === undefined) {
    throw new Error(`${label} is missing from the response (got ${amount})`);
  }
  return toCents(amount);
}

function formatCents(cents) {
  const negative = cents < 0n;
  const abs = negative ? -cents : cents;
  const text = `${abs / 100n}.${String(abs % 100n).padStart(2, "0")}`;
  return negative ? `-${text}` : text;
}

/** Exact sum of decimal money strings, as a scale-2 string. */
export function sumMoney(amounts) {
  return formatCents(amounts.reduce((total, amount) => total + toCents(amount), 0n));
}

/** A per-run tag (UTC YYYYMMDDHHmmss) that keeps asset serials unique across demo runs. */
export function runTag(now = new Date()) {
  return now.toISOString().replace(/[-:T]/g, "").slice(0, 14);
}

/**
 * A thin client for the {data, error} envelope. Returns {@code data} when the status is the expected
 * 2xx; for an expected non-2xx (a role check) returns {status, error}. Anything else is a DemoError.
 */
export function createApiClient({ baseUrl, fetchImpl = fetch, timeoutMs = 60_000 }) {
  const base = baseUrl.replace(/\/+$/, "");

  async function request(method, path, { token, body, idempotencyKey, expectStatus = 200 } = {}) {
    const headers = { Accept: "application/json", "Content-Type": "application/json" };
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }
    if (idempotencyKey) {
      headers["Idempotency-Key"] = idempotencyKey;
    }
    // A server that accepts the connection but never answers must still end the demo with an error.
    let response;
    let text;
    try {
      response = await fetchImpl(`${base}${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs),
      });
      text = await response.text();
    } catch (err) {
      const reason = err?.name === "TimeoutError" ? `no response within ${timeoutMs} ms` : (err?.cause?.message ?? err?.message);
      throw new DemoError(`${method} ${path}: ${reason}`, { method, path, body: String(reason) });
    }
    const fail = (reason) =>
      new DemoError(`${method} ${path}: ${reason} (HTTP ${response.status})\n${text}`, {
        method,
        path,
        status: response.status,
        body: text,
      });

    if (response.status !== expectStatus) {
      throw fail(`expected HTTP ${expectStatus}`);
    }
    let envelope;
    try {
      envelope = JSON.parse(text);
    } catch {
      throw fail("response body is not JSON");
    }
    if (expectStatus >= 300) {
      return { status: response.status, error: envelope?.error ?? null };
    }
    if (envelope === null || typeof envelope !== "object" || envelope.error) {
      throw fail("response carries an error envelope");
    }
    return envelope.data;
  }

  return { request };
}

const LOOPBACK_HOSTS = new Set(["localhost", "127.0.0.1", "[::1]"]);

/** True when {@code baseUrl} parses as a URL whose host is this machine's loopback interface. */
export function isLoopbackUrl(baseUrl) {
  try {
    return LOOPBACK_HOSTS.has(new URL(baseUrl).hostname);
  } catch {
    return false;
  }
}

/**
 * Runs the Compose lifecycle for --up/--reset. Compose only ever starts the local stack, so the demo's
 * API target must be that stack too: a remote base URL is refused before any Compose command, above all
 * before the destructive {@code down -v}. Without --up/--reset nothing runs and any base URL is allowed.
 */
export function startLocalStack({ up, reset, baseUrl }, { compose, log = () => {} }) {
  if (!up && !reset) {
    return;
  }
  if (!isLoopbackUrl(baseUrl)) {
    throw new Error(
      `--up/--reset start the local Compose stack, but the base URL ${baseUrl} is not a loopback address `
        + "(localhost, 127.0.0.1 or [::1]), so the demo would write to another server. Use "
        + "--base-url http://localhost:8080, or drop --up/--reset to run against an already-running stack.",
    );
  }
  if (reset) {
    log("--reset: deleting the local Compose database volume");
    compose("down", "-v");
  }
  compose("up", "--build", "-d");
}

/** Polls GET /actuator/health until UP; connection errors are expected while the stack boots. */
export async function waitForHealth({
  baseUrl,
  fetchImpl = fetch,
  timeoutMs = 300_000,
  intervalMs = 5_000,
  attemptTimeoutMs = 10_000,
  sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  now = () => Date.now(),
}) {
  const path = "/actuator/health";
  const url = `${baseUrl.replace(/\/+$/, "")}${path}`;
  const deadline = now() + timeoutMs;
  let lastProblem = "no attempt made";
  for (;;) {
    try {
      const response = await fetchImpl(url, { method: "GET", signal: AbortSignal.timeout(attemptTimeoutMs) });
      const text = await response.text();
      if (response.ok && JSON.parse(text)?.status === "UP") {
        return;
      }
      lastProblem = `HTTP ${response.status}: ${text}`;
    } catch (err) {
      lastProblem = err?.cause?.message ?? err?.message ?? String(err);
    }
    if (now() + intervalMs > deadline) {
      throw new DemoError(`GET ${path} did not report UP within ${timeoutMs} ms; last: ${lastProblem}`, {
        method: "GET",
        path,
        body: lastProblem,
      });
    }
    await sleep(intervalMs);
  }
}
