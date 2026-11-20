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
│   │   └── contracts/[id]/      # detail (Jadwal/Kredit/Ledger), statement
│   ├── layout.tsx               # root layout + providers
│   └── providers.tsx            # QueryClient + toaster
├── components/                  # auth, contracts, shared, ui (shadcn)
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
- `/auth/login`, `/auth/refresh` and `/auth/logout` never carry an access token.
- `roles` from the JWT decides which buttons are shown (UX only). The backend's role matrix is the
  enforcement; a forced call still answers 403.

## Money and dates

Money is a scale-2 decimal string from the API and a scale-2 decimal string on the way back. Validation,
comparison, rate conversion and the derived schedule totals use `lib/decimal.ts` (BigInt string
arithmetic) — never `parseFloat`. Display formatting (`Intl`, `id-ID`, `Asia/Jakarta`) is isolated in
`lib/format.ts`; a missing amount (DRAFT outstanding) renders as `–`.

## Tests

```bash
npm test
```

Covered: `apiFetch` envelope/error mapping, bearer rules and single-flight refresh + replay, refresh-failure
logout, the in-memory session store, JWT claim decoding, money/date formatting and exact decimal helpers,
the create-contract Zod schema and payload mapping, the per-mount `Idempotency-Key`, contract-list
loading/empty/error states and DRAFT `–`, role gating, and the installment table's derived totals.
