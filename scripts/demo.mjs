#!/usr/bin/env node
// Serfira end-to-end demo (T34, Addendum §18, PRD §5 scenarios 1, 2, 3 and 6).
//
//   node scripts/demo.mjs            # against a stack that is already running
//   node scripts/demo.mjs --up       # docker compose up --build -d first
//   node scripts/demo.mjs --reset    # docker compose down -v first (DELETES the local database volume)
//   options: --base-url <url> (default $SERFIRA_API_BASE_URL or http://localhost:8080), --skip-roles
//   --up/--reset only accept a loopback base URL (localhost, 127.0.0.1, [::1]); without them any URL works.
//
// Every customer and contract goes through the API (ADR-004): no SQL, no plaintext PII rows. The only
// seeded data are the three demo users of Addendum §18.3. Each run creates two new contracts with a
// per-run asset serial and fresh idempotency keys, so it can be re-run against the same database.
// Dates are relative to today in Asia/Jakarta (T34 Decision 2026-11-25): the API cannot backdate a
// payment, and activation refuses a start date more than one year old.

import { spawnSync } from "node:child_process";
import { randomUUID } from "node:crypto";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  DemoError,
  createApiClient,
  jakartaToday,
  minusDays,
  minusMonths,
  requireCents,
  runTag,
  startLocalStack,
  sumMoney,
  waitForHealth,
} from "./demo/lib.mjs";

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const API = "/api/v1";

// Addendum §18.3 — documented demo-only credentials, seeded by R__demo_seed.sql under the demo profile.
const USERS = {
  admin: { username: "admin", password: "Admin123!" },
  finance: { username: "finance", password: "Finance123!" },
  manager: { username: "manager", password: "Manager123!" },
};

function parseArgs(argv) {
  const options = {
    up: false,
    reset: false,
    skipRoles: false,
    baseUrl: process.env.SERFIRA_API_BASE_URL || "http://localhost:8080",
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === "--up") {
      options.up = true;
    } else if (arg === "--reset") {
      options.reset = true;
      options.up = true;
    } else if (arg === "--skip-roles") {
      options.skipRoles = true;
    } else if (arg === "--base-url" && argv[i + 1]) {
      options.baseUrl = argv[(i += 1)];
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`unknown argument: ${arg} (try --help)`);
    }
  }
  return options;
}

const log = (line) => console.log(line);
const ok = (line) => log(`  ✓ ${line}`);

function check(condition, message) {
  if (!condition) {
    throw new Error(`assertion failed: ${message}`);
  }
}

// Every amount asserted here is a field the API must return: an absent one fails, never reads as zero.
function checkMoney(actual, expected, label) {
  check(requireCents(actual, label) === requireCents(expected, `${label} (expected)`), `${label}: expected ${expected}, got ${actual}`);
}

function compose(...args) {
  log(`$ docker compose ${args.join(" ")}`);
  const result = spawnSync("docker", ["compose", ...args], { cwd: REPO_ROOT, stdio: "inherit" });
  if (result.error || result.status !== 0) {
    throw new Error(`docker compose ${args.join(" ")} failed (${result.error?.message ?? `exit ${result.status}`})`);
  }
}

async function login(api, user) {
  const tokens = await api.request("POST", `${API}/auth/login`, {
    body: { username: user.username, password: user.password },
  });
  check(typeof tokens?.access_token === "string", `login ${user.username} returned an access_token`);
  return tokens.access_token;
}

async function createAndActivate(api, token, label, request) {
  const created = await api.request("POST", `${API}/contracts`, {
    token,
    idempotencyKey: randomUUID(),
    body: request,
    expectStatus: 201,
  });
  check(created.status === "DRAFT", `${label} created as DRAFT (got ${created.status})`);
  checkMoney(created.principal, sumMoney([request.asset_price, `-${request.down_payment}`]), `${label} principal`);
  ok(`${label}: created ${created.contract_no} (DRAFT, principal ${created.principal})`);

  const active = await api.request("POST", `${API}/contracts/${created.id}/activate`, { token, body: {} });
  check(active.status === "ACTIVE", `${label} activated (got ${active.status})`);
  ok(`${label}: activated, start date ${active.start_date}`);
  return active;
}

async function installments(api, token, contractId) {
  return api.request("GET", `${API}/contracts/${contractId}/installments`, { token });
}

function assertSchedule(label, rows, golden) {
  check(rows.length === golden.count, `${label} has ${golden.count} installments (got ${rows.length})`);
  // Every row must carry both amounts, so an absent one can never be summed as zero.
  for (const row of rows) {
    requireCents(row.principal_amount, `${label} installment ${row.period_no} principal_amount`);
    requireCents(row.interest_amount, `${label} installment ${row.period_no} interest_amount`);
  }
  const first = rows[0];
  const last = rows[rows.length - 1];
  checkMoney(first.principal_amount, golden.firstPrincipal, `${label} installment 1 principal`);
  checkMoney(first.interest_amount, golden.firstInterest, `${label} installment 1 interest`);
  checkMoney(last.principal_amount, golden.lastPrincipal, `${label} installment ${golden.count} principal`);
  checkMoney(last.interest_amount, golden.lastInterest, `${label} installment ${golden.count} interest`);
  checkMoney(sumMoney(rows.map((row) => row.principal_amount)), golden.totalPrincipal, `${label} Σ principal`);
  checkMoney(sumMoney(rows.map((row) => row.interest_amount)), golden.totalInterest, `${label} Σ interest`);
  ok(`${label}: schedule matches the Addendum §18 golden values (${rows.length} installments)`);
}

async function pay(api, token, contractId, amount, channel, idempotencyKey = randomUUID()) {
  const payment = await api.request("POST", `${API}/payments`, {
    token,
    idempotencyKey,
    body: { contract_id: contractId, amount, channel },
    expectStatus: 201,
  });
  const lineAmounts = payment.allocations.map((line) => {
    requireCents(line.amount, `payment ${payment.payment_no} ${line.allocation_type} allocation amount`);
    return line.amount;
  });
  checkMoney(sumMoney(lineAmounts), amount, `payment ${payment.payment_no} Σ allocations`);
  return { payment, idempotencyKey };
}

function describeAllocations(payment) {
  return payment.allocations.map((line) => `${line.allocation_type} ${line.amount}`).join(", ");
}

/** Contract B (§18.2, EFFECTIVE): PRD scenario 1 (on-time full payment) and scenario 6 (retry). */
async function contractB(api, token, today, tag) {
  log("\nContract B — EFFECTIVE, on-time payment (PRD scenarios 1 and 6)");
  const contract = await createAndActivate(api, token, "B", {
    customer: { full_name: "Siti Rahayu", nik: "3275015506850002", phone: "081200000002" },
    asset: { asset_type: "CAR", brand: "Toyota", model: "Avanza", serial_no: `DEMO-B-${tag}` },
    asset_price: "200000000.00",
    down_payment: "50000000.00",
    tenor_months: 36,
    interest_scheme: "EFFECTIVE",
    interest_rate: "0.0075",
    planned_start_date: minusMonths(today, 1),
  });
  const schedule = await installments(api, token, contract.id);
  assertSchedule("B", schedule, {
    count: 36,
    firstPrincipal: "3644959.90",
    firstInterest: "1125000.00",
    lastPrincipal: "4734451.45",
    lastInterest: "35508.39",
    totalPrincipal: "150000000.00",
    totalInterest: "21718556.34",
  });

  const { payment, idempotencyKey } = await pay(api, token, contract.id, "4769959.90", "BANK_TRANSFER");
  const firstId = schedule[0].id;
  check(payment.allocations.every((line) => line.installment_id === firstId), "B payment lands on installment 1 only");
  check(!payment.allocations.some((line) => line.allocation_type === "PENALTY"), "B on-time payment carries no penalty");
  const byType = Object.fromEntries(payment.allocations.map((line) => [line.allocation_type, line.amount]));
  checkMoney(byType.INTEREST, "1125000.00", "B interest allocation");
  checkMoney(byType.PRINCIPAL, "3644959.90", "B principal allocation");
  check(requireCents(payment.excess_amount, "B payment excess_amount") === 0n, `B payment has no excess (got ${payment.excess_amount})`);
  ok(`B: payment ${payment.payment_no} 4769959.90 → ${describeAllocations(payment)}`);

  const replay = await pay(api, token, contract.id, "4769959.90", "BANK_TRANSFER", idempotencyKey);
  // "Saldo tidak dobel" (PRD scenario 6): installment 2 is not due yet, so a second application would land
  // as customer credit (ADR-009), never on the schedule. The credit balance is what must stay at zero.
  const credit = await api.request("GET", `${API}/contracts/${contract.id}/credit`, { token });
  check(requireCents(credit.available_balance, "B credit available_balance") === 0n
    && requireCents(credit.total_credit, "B credit total_credit") === 0n,
    `B retry created no customer credit (available ${credit.available_balance}, total ${credit.total_credit})`);
  check(replay.payment.id === payment.id && replay.payment.payment_no === payment.payment_no,
    `B retry with the same Idempotency-Key replays ${payment.payment_no} (got ${replay.payment.payment_no})`);
  ok(`B: retry with the same Idempotency-Key replays ${payment.payment_no}; no second payment, credit stays 0.00`);

  const after = await installments(api, token, contract.id);
  check(after[0].status === "PAID", `B installment 1 PAID (got ${after[0].status})`);
  ok("B: installment 1 PAID");
  return contract;
}

/** Contract A (§18.1, FLAT): PRD scenario 2 (late, penalty first) and scenario 3 (partial payment). */
async function contractA(api, token, today, tag) {
  log("\nContract A — FLAT, late and partial payment (PRD scenarios 2 and 3)");
  const contract = await createAndActivate(api, token, "A", {
    customer: { full_name: "Budi Santoso", nik: "3171012501900001", phone: "081200000001" },
    asset: { asset_type: "MOTORCYCLE", brand: "Honda", model: "Beat", serial_no: `DEMO-A-${tag}` },
    asset_price: "20000000.00",
    down_payment: "4000000.00",
    tenor_months: 12,
    interest_scheme: "FLAT",
    interest_rate: "0.0150",
    planned_start_date: minusDays(today, 70),
  });
  const schedule = await installments(api, token, contract.id);
  assertSchedule("A", schedule, {
    count: 12,
    firstPrincipal: "1333333.33",
    firstInterest: "240000.00",
    lastPrincipal: "1333333.37",
    lastInterest: "240000.00",
    totalPrincipal: "16000000.00",
    totalInterest: "2880000.00",
  });

  const { payment } = await pay(api, token, contract.id, "2000000.00", "CASH");
  // Oldest due installment first, and inside it penalty → interest → principal (ADR-009).
  const firstInstallmentOrder = payment.allocations
    .filter((line) => line.installment_id === schedule[0].id)
    .map((line) => line.allocation_type)
    .join(",");
  check(payment.allocations[0].installment_id === schedule[0].id && firstInstallmentOrder === "PENALTY,INTEREST,PRINCIPAL",
    `A pays installment 1 first as PENALTY,INTEREST,PRINCIPAL (got ${firstInstallmentOrder})`);
  ok(`A: payment ${payment.payment_no} 2000000.00 → ${describeAllocations(payment)}`);

  const after = await installments(api, token, contract.id);
  check(after[0].status === "PAID", `A installment 1 PAID (got ${after[0].status})`);
  check(after[1].status === "PARTIALLY_PAID", `A installment 2 PARTIALLY_PAID (got ${after[1].status})`);
  check(requireCents(after[1].outstanding, "A installment 2 outstanding") > 0n, `A installment 2 still owes (got ${after[1].outstanding})`);
  check(after[2].status === "PENDING", `A installment 3 PENDING (got ${after[2].status})`);
  ok(`A: installment 1 PAID, installment 2 PARTIALLY_PAID (outstanding ${after[1].outstanding}), installment 3 PENDING`);
  return contract;
}

async function statement(api, token, contract) {
  log(`\nStatement — ${contract.contract_no} (ledger-literal, ADR-013 A-8)`);
  // Oldest entry first and one row per journal line, so a backdated contract spans several pages.
  const rows = [];
  for (let pageNo = 0; ; pageNo += 1) {
    const page = await api.request("GET", `${API}/contracts/${contract.id}/statement?size=100&page=${pageNo}`, { token });
    rows.push(...page.content);
    if (pageNo + 1 >= page.total_pages) {
      break;
    }
  }
  const byRefType = new Map();
  for (const row of rows) {
    const group = byRefType.get(row.ref_type) ?? { rows: 0, debits: [], credits: [] };
    group.rows += 1;
    group.debits.push(row.debit ?? "0");
    group.credits.push(row.credit ?? "0");
    byRefType.set(row.ref_type, group);
  }
  for (const required of ["CONTRACT_ACTIVATION", "BILLING", "PENALTY_ACCRUAL", "PAYMENT"]) {
    check(byRefType.has(required), `statement has a ${required} row (got ${[...byRefType.keys()].join(", ")})`);
  }
  log(`    ${"ref_type".padEnd(20)}${"rows".padStart(5)}${"Σ debit".padStart(18)}${"Σ credit".padStart(18)}`);
  for (const [refType, group] of byRefType) {
    log(`    ${refType.padEnd(20)}${String(group.rows).padStart(5)}`
      + `${sumMoney(group.debits).padStart(18)}${sumMoney(group.credits).padStart(18)}`);
  }
  for (const row of rows.filter((line) => line.ref_type === "PAYMENT")) {
    log(`    payment line: ${row.account_code.padEnd(14)} D ${String(row.debit ?? "0.00").padStart(12)}`
      + `  C ${String(row.credit ?? "0.00").padStart(12)}`);
  }
  const totalDebit = sumMoney(rows.map((row) => row.debit ?? "0"));
  const totalCredit = sumMoney(rows.map((row) => row.credit ?? "0"));
  checkMoney(totalDebit, totalCredit, "statement Σ debit vs Σ credit");
  ok(`statement: ${rows.length} rows; activation, billing, penalty and payment posted; Σ debit = Σ credit = ${totalDebit}`);
}

async function aging(api, token) {
  const report = await api.request("GET", `${API}/reports/aging`, { token });
  check(requireCents(report.portfolio?.total_outstanding, "aging portfolio total_outstanding") > 0n, "aging total outstanding > 0");
  ok(`aging as of ${report.as_of}: total outstanding ${report.portfolio.total_outstanding}`);
}

async function roleSplit(api, contract) {
  log("\nRole split — authorization stays server-side (ADR-015)");
  const financeToken = await login(api, USERS.finance);
  await api.request("GET", `${API}/contracts/${contract.id}/statement`, { token: financeToken });
  ok("finance: statement → 200");
  await api.request("POST", `${API}/payments`, {
    token: financeToken,
    idempotencyKey: randomUUID(),
    body: { contract_id: contract.id, amount: "1.00", channel: "CASH" },
    expectStatus: 403,
  });
  ok("finance: record payment → 403");

  const managerToken = await login(api, USERS.manager);
  await api.request("GET", `${API}/reports/aging`, { token: managerToken });
  ok("manager: aging report → 200");
  await api.request("GET", `${API}/contracts/${contract.id}/statement`, { token: managerToken, expectStatus: 403 });
  ok("manager: statement → 403");
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    log("usage: node scripts/demo.mjs [--up] [--reset] [--base-url <url>] [--skip-roles]");
    return;
  }
  // Validates the base URL before any Compose command: --reset must never wipe the local volume and then
  // send the demo's writes to a remote server.
  startLocalStack(options, { compose, log });

  const api = createApiClient({ baseUrl: options.baseUrl });
  const timeoutMs = Number(process.env.SERFIRA_DEMO_HEALTH_TIMEOUT_MS) || 300_000;
  log(`Waiting for ${options.baseUrl}/actuator/health …`);
  await waitForHealth({ baseUrl: options.baseUrl, timeoutMs });
  ok("backend health UP");

  const today = jakartaToday();
  const tag = runTag();
  log(`Business date (Asia/Jakarta): ${today}; run tag ${tag}`);

  const adminToken = await login(api, USERS.admin);
  ok("logged in as admin (ADMIN_OPERASIONAL)");

  const b = await contractB(api, adminToken, today, tag);
  const a = await contractA(api, adminToken, today, tag);
  await statement(api, adminToken, a);
  await aging(api, adminToken);
  if (!options.skipRoles) {
    await roleSplit(api, a);
  }

  log(`\nFollow along: cd frontend && npm run dev → http://localhost:3000, log in as admin, search ${a.contract_no}`);
  log(`DEMO OK — contract A ${a.contract_no}, contract B ${b.contract_no}`);
}

main().catch((err) => {
  console.error(`\nDEMO FAILED: ${err.message}`);
  if (err instanceof DemoError && err.status !== undefined) {
    console.error(`  ${err.method} ${err.path} → HTTP ${err.status}`);
  }
  process.exitCode = 1;
});
