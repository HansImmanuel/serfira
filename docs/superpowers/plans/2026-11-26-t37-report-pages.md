# T37 — G4 Frontend Report Pages and Customer Name in the Aging API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operators, finance users and managers can read the aging and outstanding reports in the browser. The pages show numbers the backend computed and compute none of their own.

**Architecture:** The backend change is small. The `contract` module's aging snapshot gains the customer's full name, which reaches `ContractAgingRow` as `customer_name`. The frontend adds two routes under `app/(dashboard)/reports/`. They share one paged per-contract table built on the existing `/reports/aging` rows. The outstanding page adds metric cards from `/reports/outstanding`. The browser derives exactly one thing, the NPL badge (`dpd_over_90 > 0`, ADR-026 D4).

**Tech Stack:** Spring Boot 4 / JPA / PostgreSQL (Testcontainers ITs). Next.js App Router, TanStack Query v5, shadcn/ui, Vitest + Testing Library.

**Spec:** `docs/tasks.md` § T37. `docs/06_FRONTEND_SPEC.md` §2.9, §2.10, §3.1, §3.4, §3.5. ADR-026 (D1–D8), ADR-013 A-6/A-7, ADR-023, ADR-024, ADR-004 with Addendum §9. Backend contracts: `AgingReportResponse`, `ContractAgingRow`, `AgingBucketsResponse`, `OutstandingReportResponse` (with `NplSummary`, `CollectionSummary`, `SettlementCashSummary`).

**Branch:** `t37-report-pages` from `main` @ `b6dfc24` (PR #28, T36, merged). The PR targets `main`.

## Decisions taken at planning (2026-11-26)

1. **The customer name comes from a scalar projection query, not a `join fetch` of `Customer`.** Hydrating `Customer` runs `AesGcmStringAttributeConverter` and decrypts every ACTIVE customer's NIK and phone on every report request, only to read the unencrypted `full_name`. Instead, `ContractRepository.findActiveContractCustomerNames()` selects `c.id` and `cu.fullName` only.
2. **`AgingReportSourceService` reads at `REPEATABLE_READ`.** The source now runs two statements: installments, then customer names. Both filter on `status = ACTIVE`. Under READ COMMITTED, a contract that closes between the two statements would have installments but no name. Under REPEATABLE READ, both statements, plus the penalty-adjustment read, see one PostgreSQL snapshot. A read-only transaction at this level cannot fail with a serialization error. The only change is `isolation = Isolation.REPEATABLE_READ` on the existing `@Transactional(readOnly = true)`. A missing name then signals corrupted data, and the service throws `IllegalStateException`.
3. **The aging page never sends `as_of`.** The backend defaults `as_of` to its own today (Asia/Jakarta). Sending the browser's date would answer 400 `INVALID_AS_OF_DATE` whenever the browser's zone is on a different calendar day (e.g. a UTC laptop between 17:00 and 24:00 UTC). The locked field shows the response's `as_of`, read-only, and shows "–" while loading. This meets "never sends an `as_of` other than today" in the strongest way: no value is sent at all.
4. **A `null` ratio renders `EMPTY_VALUE` ("–"), the app's one empty-value dash.** T37 and FE §2.10 write "—". ADR-026 does not fix the character, and `MoneyDisplay` already uses "–" for a missing value. Task 6 aligns the FE §2.10 wording.
5. **No placeholder data across pages.** This follows `useStatement`: on a page change, a financial table shows skeleton rows until its own response arrives, never the previous page's rows.
6. **Page size 20** (the controller default; max 100).

## Global Constraints

- Money and ratios stay decimal strings end to end (ADR-024, FE §3.1). No `Number(...)`, `parseFloat` or arithmetic on any money or ratio value in the browser. Display goes through `lib/format.ts`.
- No metric arithmetic in the browser. Totals come from `portfolio`, never from summing the page's rows. The only derived value is `dpd_over_90 > 0`, decided as a `BigInt`-free string check (see Task 4).
- `reporting` does not read `customer`. The name travels `contract` → `AgingReportSourcePort` → `reporting` (02_TECH_SPEC §1, ADR-026 D8).
- NIK and phone never appear in any report response or page (ADR-004, Addendum §9).
- Roles drive what renders, never what is allowed (ADR-023). The reporting roles are `ADMIN_OPERASIONAL`, `FINANCE` and `MANAJEMEN`, the same matcher `ResourceServerSecurityConfiguration` applies to `/api/v1/reports/**`.
- Copy is Indonesian, matching the existing screens ("Laporan", "Nasabah", "Coba lagi").
- Commit messages are `feat(t37): …`, `test(t37): …` or `docs(t37): …`, with **no** AI co-author or attribution trailer. The repo owner is the sole author.
- `./gradlew check`, frontend `npm test`, `npm run lint`, `npm run typecheck` and `npm run build` stay green.

## Review Focus

1. **The browser's zone is ahead of or behind Jakarta.** A page loaded at 23:30 UTC must still render, not show a 400. Covered by Decision 3. Task 4's test asserts that the request path for `/reports/aging` never contains `as_of`.
2. **More than one page of contracts.** The summary row and the cards must not change when the user pages. Task 4's test renders page 2 with rows whose bucket sums differ from `portfolio`, and asserts that the summary row still shows the `portfolio` values.
3. **An empty book** (no ACTIVE contract with outstanding). Money is `"0.00"`, both ratios are `null`, and the rows are `[]`. Task 5's test asserts that the cards show `Rp 0` and "–" with no crash, and that the table shows the empty message.
4. **Ratios at the edges.** `"0.0000"`, `"1.0000"` (100%), `"0.0005"`, and a value above 1. `"1.2500"` cannot occur for NPL, but the formatter must not clamp. Task 2's formatter test covers each.
5. **A contract that closes while the report is being read.** It must not answer 500. Covered by Decision 2. There is no deterministic test, so the reviewer checks the `isolation` attribute in Task 1.

---

### Task 1: `customer_name` on the aging rows (backend)

**Files:**
- Modify: `docs/tasks.md` (T37 `Status: IN PROGRESS`, Current sprint line; do this first, in its own commit)
- Create: `backend/src/main/java/com/serfira/contract/infrastructure/ContractCustomerName.java`
- Modify: `backend/src/main/java/com/serfira/contract/infrastructure/ContractRepository.java`
- Modify: `backend/src/main/java/com/serfira/contract/application/InstallmentAgingSnapshot.java`
- Modify: `backend/src/main/java/com/serfira/contract/application/AgingReportSourceService.java`
- Modify: `backend/src/main/java/com/serfira/reporting/api/ContractAgingRow.java`
- Modify: `backend/src/main/java/com/serfira/reporting/application/AgingReportService.java` (`ContractAccumulator`, `page(...)`)
- Test: `backend/src/test/java/com/serfira/reporting/application/AgingReportServiceTest.java`, `OutstandingMetricsCalculatorTest.java`, `OutstandingReportServiceTest.java` (constructor arity), `backend/src/test/java/com/serfira/reporting/AgingReportIT.java`

**Interfaces:**
- Produces:
  - `interface ContractCustomerName { UUID getContractId(); String getFullName(); }`, a Spring Data interface projection.
  - `List<ContractCustomerName> ContractRepository.findActiveContractCustomerNames()`, with JPQL `select c.id as contractId, cu.fullName as fullName from Contract c join c.customer cu where c.status = com.serfira.contract.domain.ContractStatus.ACTIVE`.
  - `record InstallmentAgingSnapshot(UUID contractId, String contractNo, String customerName, LocalDate dueDate, int daysPastDue, BigDecimal outstanding)`. `customerName` is non-null.
  - `record ContractAgingRow(UUID contractId, String contractNo, String customerName, AgingBucketsResponse buckets)`. On the wire it is `{contract_id, contract_no, customer_name, buckets}`.

- [ ] **Step 1: Write the failing tests**
  - `AgingReportServiceTest`: in the existing grouping test, give contract A the name `"Budi Santoso"` and B `"Siti Rahayu"`, then assert `row.customerName()` for each row.
  - `AgingReportIT`, new test `everyRowCarriesTheCustomerNameAndNoOtherPii`: seed two ACTIVE contracts for two customers. Assert:
    - `$.data.contracts.content[*].customer_name` contains both customers' `full_name`, in contract-number order.
    - `$.data.contracts.content[0].nik` and `.phone` do not exist.
  - Update every `new InstallmentAgingSnapshot(...)` call in the three unit tests to pass a name (e.g. `"Budi Santoso"`) as the third argument.
- [ ] **Step 2: Run them and confirm they fail to compile or fail**
  Run: `cd backend && ./gradlew test --tests 'com.serfira.reporting.*'`
  Expected: compilation failure on the snapshot arity.
- [ ] **Step 3: Implement the projection, the repository method, the snapshot component and the row component.**
  - In `AgingReportSourceService`:
    - Load the names once into a `Map<UUID, String>`.
    - Look each snapshot's name up; a missing entry throws `IllegalStateException("no customer name for ACTIVE contract " + id)`.
    - Set `@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)`.
    - Add one Javadoc sentence explaining why (Decision 2).
  - Thread `customerName` through `ContractAccumulator`.
  - Add `@param customerName` to the `ContractAgingRow` and `InstallmentAgingSnapshot` Javadoc. The text is the customer's `full_name`, unencrypted (Addendum §9); NIK and phone are never carried. springdoc derives the OpenAPI field from the record.
- [ ] **Step 4: Run the reporting tests and the role matrix**
  Run: `./gradlew test --tests 'com.serfira.reporting.*' --tests '*EndpointRoleMatrixIT'`
  Expected: all green, and `OutstandingReportIT` unchanged.
- [ ] **Step 5: Commit**: `feat(t37): customer_name on aging rows via the contract port`

### Task 2: Ratio → percent formatter

**Files:**
- Modify: `frontend/lib/format.ts`
- Test: `frontend/lib/format.test.ts`

**Interfaces:**
- Produces: `formatRatioPercent(value: string | null | undefined): string`. It takes a scale-≤4 fraction string and returns an id-ID percent with exactly 2 decimals. `null`, `undefined` or an unparseable value returns `EMPTY_VALUE`.

- [ ] **Step 1: Write the failing test** `describe("formatRatioPercent")`:
  - `"0.0150"` → `"1,50%"`
  - `"0.4393"` → `"43,93%"`
  - `"0.0005"` → `"0,05%"`
  - `"0.0000"` → `"0,00%"`
  - `"1.0000"` → `"100,00%"`
  - `"12.5000"` → `"1.250,00%"` (grouping, no clamp)
  - `"0.015"` → `"1,50%"` (scale < 4 is padded)
  - `null`, `undefined`, `"abc"`, `"-0.0100"` and `"0.12345"` (scale > 4) → `EMPTY_VALUE`
  - Above float precision, like the money tests: `"123456789012345.0000"` → `"12.345.678.901.234.500,00%"`
- [ ] **Step 2: Run it**: `cd frontend && npx vitest run lib/format.test.ts`. Expected: FAIL (`formatRatioPercent` is not exported).
- [ ] **Step 3: Implement it in `lib/format.ts`.**
  - Reuse `parseDecimal`.
  - Reject `scale > 4` or a negative value.
  - Scale the units to 4 decimals (`units * 10n ** BigInt(4 - scale)`). Those units are exactly the percent at scale 2.
  - Format the whole part with a module-level `new Intl.NumberFormat("id-ID", { maximumFractionDigits: 0 })` applied to the `BigInt`.
  - Append `,` + the 2-digit fraction (padded) + `%`.
  - Do not use `Intl`'s `style: "percent"`, which needs a `Number`.
- [ ] **Step 4: Run it**. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(t37): ratio-to-percent formatter`

### Task 3: Report types, query hooks, role helper and the "Laporan" navigation

**Files:**
- Create: `frontend/types/report.ts`
- Create: `frontend/hooks/useReports.ts`
- Modify: `frontend/hooks/useSession.ts`
- Modify: `frontend/components/shared/AppSidebar.tsx`
- Test: `frontend/components/shared/AppSidebar.test.tsx` (new), `frontend/hooks/useReports.test.ts` (new, path builder only)

**Interfaces:**
- Produces:
  - `types/report.ts`. These mirror the Java records in snake_case. `Money` comes from `@/types/contract`, and `type Ratio = string`.
    - `AgingBuckets { current; dpd_1_30; dpd_31_60; dpd_61_90; dpd_over_90; total_outstanding }`, all `Money`.
    - `ContractAgingRow { contract_id: string; contract_no: string; customer_name: string; buckets: AgingBuckets }`.
    - `AgingReport { as_of: string; portfolio: AgingBuckets; contracts: PageResponse<ContractAgingRow> }`.
    - `OutstandingReport`:
      - `as_of`, `total_outstanding`, `accounts_receivable`
      - `npl: { contract_count: number; outstanding: Money; ratio: Ratio | null }`
      - `collection: { period_from: string; period_to: string; collected: Money; due: Money; rate: Ratio | null }`
      - `settlement_cash: { period_from: string; period_to: string; amount: Money }`
      - `active_contract_count: number`
  - `hooks/useReports.ts`:
    - `export const REPORT_PAGE_SIZE = 20`
    - `agingReportPath(page: number): string` returns `/reports/aging?page=<page>&size=20`, never with `as_of`.
    - `useAgingReport(page: number, enabled = true)` uses key `["reports", "aging", page]` with no `placeholderData`.
    - `useOutstandingReport(enabled = true)` uses key `["reports", "outstanding"]`.
  - `hooks/useSession.ts`: `useCanReadReports(): boolean`, true for any of `REPORT_ROLES = ["ADMIN_OPERASIONAL", "FINANCE", "MANAJEMEN"] as const`.
  - `AppSidebar`: a "Laporan" group with links "Aging" → `/reports/aging` and "Outstanding" → `/reports/outstanding`. It renders only when `useCanReadReports()` is true.

- [ ] **Step 1: Write the failing tests.**
  - `useReports.test.ts`:
    - `agingReportPath(0) === "/reports/aging?page=0&size=20"`
    - `agingReportPath(3)` has no `as_of`
  - `AppSidebar.test.tsx`:
    - For each of the three roles (using `setSession` + `accessTokenWithRoles`), both report links are present with the right `href`, and "Laporan" is visible.
    - With roles `[]`, neither link renders.
    - With pathname `/reports/aging`, the Aging link has `aria-current="page"`.
- [ ] **Step 2: Run them**: `npx vitest run hooks/useReports.test.ts components/shared/AppSidebar.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement the types, hooks, role helper and sidebar.** In the sidebar, keep the "Kontrak" item. Render the report links as a labelled sub-list, using the same link styling and `BarChart3`/`PieChart` icons from `lucide-react`.
- [ ] **Step 4: Run them**. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(t37): report types, hooks and Laporan navigation`

### Task 4: Shared contract table and the `/reports/aging` page

**Files:**
- Create: `frontend/components/reports/AgingContractTable.tsx`
- Create: `frontend/components/reports/AgingReportView.tsx`
- Create: `frontend/app/(dashboard)/reports/aging/page.tsx`
- Test: `frontend/components/reports/AgingReportView.test.tsx`

**Interfaces:**
- Consumes: `useAgingReport`, `REPORT_PAGE_SIZE`, `useCanReadReports`, `formatMoney`/`MoneyDisplay`, `ErrorAlert`, `AccessDenied`, `Skeleton`, `Table*`.
- Produces: `AgingContractTable({ query, page, onPageChange, showNplBadge }: { query: UseQueryResult<AgingReport>; page: number; onPageChange: (page: number) => void; showNplBadge: boolean })`.
  - Columns: No. Kontrak (a `Link` to `/contracts/{contract_id}`), Nasabah, Outstanding, Current, 1–30, 31–60, 61–90, >90.
  - 5 skeleton rows while pending. The empty message is "Tidak ada kontrak ACTIVE dengan outstanding." `ErrorAlert` with retry on error.
  - Previous/next buttons and "`{total_elements}` kontrak · halaman `{page+1}` dari `{max(total_pages,1)}`".
  - When `showNplBadge`, a `Badge` "NPL" appears next to the contract number exactly when `isNplRow(row)` is true.
  - `export function isNplRow(row: ContractAgingRow): boolean` is `!/^0+(\.0+)?$/.test(row.buckets.dpd_over_90)`. It is a string test because buckets are never negative (each bucket amount is ≥ 0, ADR-013 A-7), so no arithmetic is needed.
- Produces: `AgingReportView()`. It renders:
  - The title "Laporan Aging".
  - A read-only `Input` labelled "Per tanggal" (`readOnly`, `aria-readonly`) showing `formatDate(data.as_of)`, or "–" while pending.
  - A summary row (`aria-label="Total portofolio"`) of the six `portfolio` values.
  - `AgingContractTable` with `showNplBadge={false}`.
  - `AccessDenied` (with no request) when `useCanReadReports()` is false.

- [ ] **Step 1: Write the failing test**, mocking `apiFetch` the way `ContractListView.test.tsx` does:
  - **Loading:** skeletons are visible while `apiFetch` is unresolved.
  - **Populated:** one row shows `MF-202611-0001`, the customer name and `Rp 1.573.333`. The contract number links to `/contracts/<id>`.
  - **Summary from portfolio:** respond to page 1 with rows whose buckets sum to something else. The summary row shows the `portfolio` values.
  - **Pagination:** clicking "Berikutnya" calls `apiFetch` with `"/reports/aging?page=1&size=20"`. "Sebelumnya" is disabled on page 0.
  - **Locked `as_of`:** the input is read-only and shows `formatDate(as_of)`. No `apiFetch` call path contains `as_of`.
  - **Empty:** `content: []` shows the empty message.
  - **Error:** `ApiError` shows the message and code. "Coba lagi" refetches.
  - **Access:** roles `[]` render `AccessDenied` and `apiFetch` is never called.
  - **`isNplRow` unit cases:** `"0.00"` → false, `"0"` → false, `"0.01"` → true, `"1500000.00"` → true.
- [ ] **Step 2: Run it**: `npx vitest run components/reports/AgingReportView.test.tsx`. Expected: FAIL.
- [ ] **Step 3: Implement the table, the view and `page.tsx`.** `page.tsx` is a `"use client"` wrapper, like `contracts/page.tsx`.
- [ ] **Step 4: Run it**. Expected: PASS.
- [ ] **Step 5: Commit**: `feat(t37): aging report page`

### Task 5: The `/reports/outstanding` page

**Files:**
- Create: `frontend/components/reports/OutstandingReportView.tsx`
- Create: `frontend/app/(dashboard)/reports/outstanding/page.tsx`
- Test: `frontend/components/reports/OutstandingReportView.test.tsx`

**Interfaces:**
- Consumes: `useOutstandingReport`, `useAgingReport`, `AgingContractTable` (with `showNplBadge`), `formatRatioPercent`, `formatMoney`, `formatDate`, `Card*`.
- Produces: `OutstandingReportView()`. It renders five cards, each with its value as returned by the API (formatting only):
  - "Total Outstanding": `total_outstanding`, sub-line "`{active_contract_count}` kontrak aktif".
  - "Total AR": `accounts_receivable`, sub-line "Jatuh tempo s.d. hari ini, termasuk masa tenggang".
  - "NPL": `formatRatioPercent(npl.ratio)` large, sub-line "`{formatMoney(npl.outstanding)}` · `{npl.contract_count}` kontrak".
  - "Collection Rate MTD": `formatRatioPercent(collection.rate)` large, sub-line "`{formatMoney(collected)}` / `{formatMoney(due)}`" plus the period label "`{formatDate(period_from)}` – `{formatDate(period_to)}`".
  - "Settlement Cash MTD": `settlement_cash.amount` with the same period label.

  Below the cards is `AgingContractTable` with `showNplBadge`. The cards and the table load and fail independently: a failure in one shows its own `ErrorAlert` and leaves the other on screen. `AccessDenied` applies as in Task 4.

- [ ] **Step 1: Write the failing test**, with `apiFetch` mocked per path:
  - **Populated:** use the `OutstandingReportIT` collection fixture (`collected "2073333.33"`, `due "4719999.99"`, `rate "0.4393"`, AR `"1073333.33"`). The cards show `Rp 2.073.333`, `Rp 4.720.000`, `43,93%` and `Rp 1.073.333`.
  - **Null ratios:** an empty-book fixture (all `"0.00"`, both ratios `null`) renders "–" for both ratios and `Rp 0` for money.
  - **Period label:** shows both dates formatted.
  - **NPL badge:** exactly the rows with non-zero `dpd_over_90` carry "NPL". Count the badges against the fixture.
  - **No status column:** there is no header "Status".
  - **Independent failure:** the outstanding request rejects while the aging request resolves. The `ErrorAlert` shows and the table rows still render.
  - **Loading:** the card skeletons show while pending.
- [ ] **Step 2: Run it**. Expected: FAIL.
- [ ] **Step 3: Implement the view and `page.tsx`.**
- [ ] **Step 4: Run the whole frontend suite and the static checks**
  Run: `npm test && npm run lint && npm run typecheck && npm run build`
  Expected: all green, and the test count rises above 203.
- [ ] **Step 5: Commit**: `feat(t37): outstanding report page`

### Task 6: Verify against the Compose stack, docs close-out, PR

**Files:**
- Modify: `docs/06_FRONTEND_SPEC.md` §2.9/§2.10: confirm them against the shipped pages. The table now has a `customer_name` source. Change the null-ratio dash to "–" (Decision 4). The `as_of` is shown from the response and never sent (Decision 3).
- Modify: `README.md`: add the report pages to the frontend section, and `customer_name` to the aging row in the API table, if rows are listed.
- Modify: `docs/tasks.md`:
  - T37 → `Status: DONE` with an implementation note (decisions 1–5, verification commands, counts).
  - A Completed Work row.
  - Current Project State: verified counts, implemented list, and next task T38.
- Modify: `docs/PROGRESS.md`: tick T37.

- [ ] **Step 1: Run the full backend check**: `cd backend && ./gradlew check --rerun-tasks`. Expected: BUILD SUCCESSFUL. Record the suite and test counts (baseline 126/968).
- [ ] **Step 2: Bring up the stack and seed data**: `node scripts/demo.mjs --up`. Expected: exit 0.
- [ ] **Step 3: Run the frontend against it** (`npm run dev` via `preview_start`). For each of `admin`, `finance` and `manager` (the Addendum §18.3 demo users, a local stack):
  - Open `/reports/aging` and `/reports/outstanding`.
  - Check that the summary row equals `GET /api/v1/reports/aging`'s `portfolio`, and that each card equals the `/reports/outstanding` JSON apart from formatting.
  - Check that the NPL badges appear only on rows with `dpd_over_90 > 0`.
  - Check in the network panel that no request carries `as_of`.
  - Take a screenshot of each page.
- [ ] **Step 4: Update the docs listed above.**
- [ ] **Step 5: Commit**: `docs(t37): close T37`. Push, then open the PR (base per the Branch note). The PR body has no attribution line.
