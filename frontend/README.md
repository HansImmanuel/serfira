# Serfira frontend

The operational UI for the Serfira lending core: a thin Next.js client of the `/api/v1` API. It owns no
business rule — schedule, allocation, penalty, ledger and authorization all stay on the server.

Specification: `docs/06_FRONTEND_SPEC.md`. Browser/API boundary and the stack pin:
`docs/adr/ADR-023-browser-client-boundary.md`.

## Stack

| Concern | Choice |
| --- | --- |
| Framework | Next.js **16.4.6** (App Router, Turbopack), React 19 |
| UI | Tailwind CSS v4 + shadcn/ui (radix base, nova preset) |
| Server state | TanStack Query v5 |
| Forms | React Hook Form + Zod |
| Tests | Vitest + React Testing Library (jsdom) |
| Node | **24.16.0** (`frontend/.nvmrc`); `engines.node >= 22.0.0` |

Node 22+ is required by the shadcn CLI's `cn` package; Next.js 16 itself requires only 20.9+.

## Setup

```bash
cd frontend
nvm use            # or install the version in .nvmrc
npm ci
cp .env.example .env.local
npm run dev        # http://localhost:3000
```

The backend must be running on `http://localhost:8080`, and that origin of the browser app
(`http://localhost:3000`) must be in the backend's CORS allowlist:

```properties
SERFIRA_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:3000
```

The dev/compose profiles already set it (ADR-023).

### `NEXT_PUBLIC_API_BASE_URL`

Next.js **inlines** `NEXT_PUBLIC_*` values at build time, so the origin baked into the bundle is the
one present when `next build` ran — not the one present when `next start` runs. The rules:

- Development (`npm run dev`) may omit the variable; it falls back to `http://localhost:8080`.
- A **production build** must set an explicit absolute `http`/`https` origin. Without it `next build`
  fails instead of shipping a client that points at localhost.
- The value must have no credentials, query string or fragment; trailing slashes are stripped.
- CI builds against the reserved placeholder `https://ci-build.invalid` because it has no deployed API.
- Rebuild after changing it: editing `.env.local` alone does not change an existing production bundle.

## Scripts

| Command | What it does |
| --- | --- |
| `npm run dev` | Dev server on port 3000 |
| `npm run build` | Production build |
| `npm start` | Serve the production build |
| `npm run lint` | ESLint (Next config) |
| `npm run typecheck` | `next typegen` (typed routes) then `tsc --noEmit` |
| `npm test` | Vitest run (unit + component) |
| `npm run test:watch` | Vitest watch mode |

CI runs `npm ci` → `lint` → `typecheck` → `test` → `build` as its own job (`frontend` in
`.github/workflows/ci.yml`), independent of the Gradle jobs.

## Layout

```
frontend/
├── app/
│   ├── (auth)/login/            # sign-in
│   ├── (dashboard)/             # sidebar + header + client guard
│   │   ├── contracts/           # list, create
│   │   └── contracts/[id]/      # detail (Jadwal/Kredit/Ledger), statement, payments/new
│   ├── layout.tsx               # root layout + providers
│   └── providers.tsx            # QueryClient + toaster
├── components/                  # auth, contracts, payments, shared, ui (shadcn)
├── hooks/                       # Query hooks + session
├── lib/                         # api client, auth, session, decimal, format, zod schemas
├── test/                        # test helpers
└── types/                       # wire types (snake_case)
```

## Session model

- Access and refresh tokens live **in module memory only** — never `localStorage`, `sessionStorage`,
  cookies, URLs or logs. A reload therefore requires a new login; that is the documented behaviour of this
  phase (ADR-023 D2).
- `lib/api.ts` is the only place that knows the `{data, error}` envelope. A 401 on a protected path rotates
  the token pair **once** and replays the request; concurrent 401s share one in-flight refresh because the
  refresh token is single-use. A failed refresh clears the session and reloads `/login`.
- The session carries a **generation** that changes on login, logout and expiry (a token rotation does not).
  Async work captures it, so a refresh or a response that belongs to a replaced session is discarded instead
  of overwriting the newer operator's tokens or cache.
- Logout clears tokens, the reactive snapshot and the Query cache synchronously, then fires the best-effort
  revocation with `keepalive` and hard-navigates to `/login`; a failed revocation cannot leave a session
  behind.
- `/auth/login`, `/auth/refresh` and `/auth/logout` never carry an access token.
- `roles` from the JWT decides which buttons and tabs are shown (UX only). The backend's role matrix is the
  enforcement; a forced call still answers 403. Credit and the ledger statement are limited to
  `ADMIN_OPERASIONAL`/`FINANCE`; `MANAJEMEN` sees contract detail and the schedule only. The **Laporan**
  pages (aging, outstanding) are offered to all three roles.

## Money and dates

Money is an **exact decimal string** from the API (`"20000000.00"`, ADR-024) and a scale-2 decimal string on
the way back. Validation, comparison, rate conversion and the derived schedule totals use `lib/decimal.ts`
(BigInt string arithmetic) — never `parseFloat`. Display formatting (`lib/format.ts`) parses the string with
`BigInt` and formats whole rupiah with `Intl` (`id-ID`, `Asia/Jakarta`), so no amount is ever routed through
a binary float; a missing amount (DRAFT outstanding) renders as `–`. Operator input is bounded to the
server's `NUMERIC(19,2)` domain (17 integer digits, scale 2).

## Running the demo

From the repository root, with Docker running:

```bash
node scripts/demo.mjs --up    # Compose backend + the scripted flow (see the root README, "Demo")
cd frontend && npm ci && npm run dev
```

Open http://localhost:3000, log in as `admin` / `Admin123!` (Addendum §18.3, demo profile only), and
search for the contract number the script printed. The Jadwal tab shows installment 1 `PAID` and
installment 2 `PARTIALLY_PAID`. The Ledger tab and **Rekening koran** show the activation, billing, penalty
and payment rows. Logging in as `finance` or `manager` shows the role split: `manager` has no Kredit,
Ledger or statement view. **Laporan → Aging** and **Laporan → Outstanding** show the reports to all three
users; the NPL badge marks contracts with an amount more than 90 days past due.

## Known limitations

- **A reload means logging in again.** Tokens live in memory only (ADR-023 D2); there is no refresh cookie.
- **The Denda column shows the penalty still owed**, not the gross penalty charged (ADR-019). For a late
  installment that has been paid, Denda is `Rp 0` and Terbayar can exceed Total Tagihan, because the paid
  amount includes the penalty.
- **Not built yet:** the payments-history tab and the settlement and write-off dialogs (T39).
- **No frontend container.** The supported path is `npm run dev` against the Compose backend; a
  production build needs `NEXT_PUBLIC_API_BASE_URL` (above).

## Tests

```bash
npm test
```

Covered: `apiFetch` envelope/error mapping, bearer rules, single-flight refresh + replay, stale-session
guards (a replaced session is never overwritten by an old refresh or response), refresh-failure logout,
cache clearing on logout/login, the in-memory session store, JWT claim decoding, exact money/date formatting
including values beyond `Number.MAX_SAFE_INTEGER`, exact decimal helpers and the `NUMERIC(19,2)` bound, the
create-contract Zod schema and payload mapping (phone normalization, money magnitude), the per-mount
`Idempotency-Key` with its insecure-context fallback, API base-URL validation, contract-list
loading/empty/error states and DRAFT `–`, role gating for credit/statement, and the installment table's
derived totals. T33 adds the payment schema and payload (no `paid_at`/note), the per-mount key reused on
retry and the deliberate restart after a spent key (`CONFLICT`/`IDEMPOTENCY_KEY_EXPIRED`), allocation and
excess-credit rendering, cache invalidation after a payment or credit application, statement filter wiring
(page reset, inverted-range guard), and the credit-apply amount rules and role/status gating.
