# T39 — FE Payments Tab, Settlement and Write-off Flows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** From the contract detail page, an operator can:
- read a contract's payment history;
- jump to a payment from its statement row;
- settle the contract early from a live quote;
- write off the contract with a reason.

Every write replays safely when its response is lost.

**Architecture:**
- **Backend:** one new read endpoint, `GET /api/v1/contracts/{id}/payments`.
  - It lives in the `payment` module, because `contract` must not import `payment`.
  - It checks that the contract exists through a new `contract.application` port.
- **Frontend:** a Pembayaran tab, a settlement modal and a write-off dialog on the detail page.
  - The two write dialogs follow the `ApplyCreditDialog` operation pattern: one `Idempotency-Key` per logical operation, kept across close and reopen.
  - The statement links PAYMENT rows to the tab.

**Tech Stack:**
- Backend: Spring Boot 4, JPA, PostgreSQL 16 (Testcontainers ITs), springdoc.
- Frontend: Next.js 16 App Router, TanStack Query v5, shadcn/ui, sonner, Vitest with Testing Library.

**Spec:**
- `docs/tasks.md`, the T39 row (Later sprints);
- `docs/06_FRONTEND_SPEC.md` §2.4, §2.7, §2.8, §1.4 and §3;
- `docs/04_GAPS_ADDENDUM.md` §3.4;
- ADR-001, ADR-015, ADR-017 (idempotency), ADR-018 D6/D8/D9/D10 (settlement), ADR-020 (void), ADR-024 (money as strings).
- Backend contracts:
  - `SettlementQuoteResponse`, `SettlementExecutionResponse` (with `SettlementAllocationResponse` and `SettlementCreditApplicationResponse`);
  - `WriteOffContractRequest`, `WriteOffResponse`;
  - `PaymentResponse`.

**Branch:** `t39-payments-settlement-writeoff`, from `main` @ `12a4ccc` (PR #32, T28b, merged). The PR targets `main`.

## Decisions taken at planning (2026-11-27)

1. **The payment history is `GET /api/v1/contracts/{id}/payments` in the `payment` module.**
   - Controller: `payment/api/ContractPaymentController`.
   - `contract` cannot import `payment` (ADR-001, 02_TECH_SPEC §1), so the endpoint cannot live in `ContractController`.
   - A missing contract is 404 `CONTRACT_NOT_FOUND`, through a new `ContractLookupPort.requireExists(UUID)`.
2. **Roles: `ADMIN_OPERASIONAL` and `FINANCE`; `MANAJEMEN` gets 403.**
   - Payments are transaction-level, like the statement and credit reads. `MANAJEMEN` reads aggregates (Addendum §3.4).
   - The FE gate is a new `useCanReadPayments()` over the same role set.
3. **The list is not paged.** It includes every status, ordered `paid_at desc, payment_no desc`.
   - There is one row per payment, and a contract's lifetime bounds the count.
   - Allocations load in one `IN` query, so there is no N+1.
   - If this is wrong, `page` and `size` can be added later without breaking callers.
4. **A new `ContractPaymentResponse`; `PaymentResponse` is not widened.**
   - The `POST /payments` and void responses stay byte-identical, including stored idempotent replays.
   - Wire shape: `{id, payment_no, amount, channel, paid_at, status, excess_amount, voided_at, void_reason, allocations[]}`.
   - `excess_amount` reuses the EXCESS sum, extracted from `PaymentResponse.fromVoided` into `static BigDecimal excessOf(List<PaymentAllocation>)`.
5. **The settlement "Total Cash Due" is `gross_amount − available_credit`, not the quote's `cash_due`.**
   - A T12 quote always stores `credit_used = 0`, so its `cash_due` equals `gross_amount`.
   - Execution consumes **all** available credit (ADR-018 D10) and revalidates it against the quote's snapshot (D9).
   - The response has no future-interest field, so the modal also derives `Bunga Masa Depan = gross − (principal + billed + accrued + penalty + admin) + rebate`. With it, the displayed lines sum to the gross (the D6 identity, `SettlementQuoteEngine` lines 125–130).
   - These two figures are the browser's only money arithmetic. Both come from one tested pure function using `lib/decimal`.
   - When `available_credit > gross_amount`, the UI blocks execution. The server would answer 409 `CREDIT_EXCEEDS_SETTLEMENT`.
6. **The countdown never compares the browser clock with the server clock.**
   - Lifetime is `valid_until − quoted_at`, both server instants.
   - Elapsed time is measured as a `Date.now()` difference from the moment the quote request was sent (one round trip early, the safe side; changed from "response arrived" in the final-review fix `e2af348`).
   - At zero, "Lunasi Sekarang" is disabled and "Ambil quote baru" appears.
   - The server stays the authority: a late click gets `SETTLEMENT_QUOTE_EXPIRED`, handled below.
7. **One `Idempotency-Key` per quote.**
   - The execution operation is keyed by `quote_id`. A new quote means a new operation and a new key; a new `quote_id` sent under an old key would be `CONFLICT`.
   - As in `ApplyCreditDialog`, the key survives closing and reopening the dialog.
   - While an outcome is unknown, only "Coba lagi" is offered (same key, same quote); "Ambil quote baru" stays hidden.
8. **The write dialogs mount whenever `canWrite` is true, whatever the contract status.**
   - `canStartNew = status === "ACTIVE"` gates only the trigger for a *new* operation. The trigger shows when `open || unresolved || canStartNew`.
   - A settlement or write-off whose response is lost may still have committed. The `onSettled` refetch then shows CLOSED or TERMINATED, and the operation must survive so it can replay (the `ApplyCreditDialog` rule).
9. **A quote invalidates the contract prefix.** `POST /settlements/quote` bills due interest and accrues due penalty before pricing (ADR-018 D8), so the installments and the statement can change.
10. **Statement links.**
    - PAYMENT rows, including their reversal rows (same `ref_id`), link to `/contracts/{id}?tab=payments&payment={ref_id}`.
    - SETTLEMENT rows stay unlinked. Their `ref_id` is the settlement id, and there is no settlement read endpoint; T39 does not add one. If this is wrong, the fix is a later settlement read model plus one link.
    - A WRITE_OFF `ref_id` is the contract itself, and every other ref type is unchanged.
11. **The tab comes from the URL.**
    - `?tab=` (`schedule`, `payments`, `credit` or `ledger`) sets the Tabs `defaultValue`. A tab the role cannot see falls back to `schedule`.
    - `?payment=` highlights and scrolls to that row.
    - A query string, not a hash: App Router client navigation does not reliably scroll to a hash, and the highlight is ours.
12. **No payment-void UI.** It is not in the carry-forward. The history shows voided rows with `voided_at` and `void_reason`.
13. **The estimate goes from 2 to 5 pts:** the read model and role row (1), the tab and links (1), the settlement modal (2) and the write-off dialog (1). The Later-sprints row becomes a full `### T39` ticket under a new `## Sprint 8b` heading.

### Settlement execute: copy and next action

| Answer | Copy (title, then description) | Next action offered |
|---|---|---|
| `STALE_SETTLEMENT_QUOTE` | "Quote sudah kadaluarsa, ambil quote baru" / "Kontrak berubah sejak quote dibuat." | "Ambil quote baru" |
| `SETTLEMENT_QUOTE_EXPIRED` | "Quote sudah kadaluarsa, ambil quote baru" / "Masa berlaku quote sudah lewat." | "Ambil quote baru" |
| `SETTLEMENT_QUOTE_ALREADY_EXECUTED` | "Quote ini sudah dieksekusi." / "Periksa status kontrak sebelum melanjutkan." | none |
| `CREDIT_EXCEEDS_SETTLEMENT` | "Kredit nasabah melebihi total pelunasan." / "Pelunasan tidak dapat dieksekusi; refund kredit di luar MVP." | none |
| `CONFLICT`, `IDEMPOTENCY_KEY_EXPIRED` (`isSpentKeyError`) | the existing spent-key alert | "Mulai form baru" (a new quote) |
| any other 4xx (`CONTRACT_STATE_INVALID`, `CONCURRENT_MODIFICATION`, …) | `ErrorAlert` with the server message | "Lunasi Sekarang" stays enabled; the server re-checks |
| network error or 5xx (outcome unknown) | `ErrorAlert` | "Coba lagi" only (same key, same `quote_id`) |

"kadaluarsa" is the FE §2.8 spelling and is kept verbatim. The TTL is a system parameter, so no copy hard-codes "15 menit".

## Global Constraints

- **Money:**
  - Money stays a decimal string end to end (ADR-024, FE §3.1). Never use `Number(...)`, `parseFloat` or `+`/`−` on money.
  - The only browser arithmetic is Decision 5, through `addMoney`, `subtractMoney` and `compareMoney`.
  - Display goes through `formatMoney`, `formatDateTime` and `MoneyDisplay`.
- **Idempotency:**
  - Every write sends an `Idempotency-Key` that is minted once per logical operation (FE §1.4, ADR-017).
  - Only a remount of the operation component mints a new key, which happens after success or "Mulai form baru".
  - On a network error or 5xx the outcome is unknown: the retry reuses the same key and the same body, and the inputs that form the body are disabled.
  - Every mutation invalidates `["contract", id]` and `["contracts"]` in `onSettled`.
- **Authorization:**
  - The default-deny matcher table (ADR-015) gets exactly one new row: `GET /api/v1/contracts/*/payments` → `hasAnyRole(ADMIN, FINANCE)`.
  - The Addendum §3.4 matrix and `EndpointRoleMatrixIT` get the same row.
  - Roles drive what renders, never what is allowed (ADR-023).
- **Module direction:** `payment` may import `contract.application`; `contract` never imports `payment`.
- **Copy:** Indonesian, matching the existing screens ("Coba lagi", "Mulai form baru", "Batal").
- **Commits:**
  - Messages are `feat(t39): …`, `test(t39): …` or `docs(t39): …`.
  - There is **no** AI co-author or attribution trailer; the repo owner is the sole author.
- **Verification:**
  - Backend: `./gradlew check --rerun-tasks`.
  - Frontend: `npm test`, `npm run lint`, `npm run typecheck` and `npm run build` stay green.
  - GitHub CI is blocked by billing, so local runs are the verification of record.

## Review Focus

1. **A settlement whose response is lost but which committed.**
   - What happens: the refetch shows CLOSED and the header no longer offers a new settlement.
   - Expected: the dialog still offers "Coba lagi". The retry replays with the same key and `quote_id` and ends in the success toast.
   - Covered by Task 3's test `retries a lost response with the same key and quote after the contract closes`. Task 4 has the write-off twin.
2. **A browser clock that is wrong, or a quote left open past its lifetime.**
   - Expected: the countdown starts at the server lifetime even when `quoted_at` is years away from `Date.now()`, and execution disables at zero.
   - Covered by Task 3's `starts the countdown from the server lifetime whatever the browser clock says`.
3. **A contract that holds customer credit.**
   - Expected: the cash due is `gross − credit`, credit equal to the gross shows Rp 0, and credit above the gross blocks execution.
   - Covered by Task 3's `settlementBreakdown` tests and the dialog test `blocks execution when available credit exceeds the gross`.
4. **A re-quote after a stale or expired answer.**
   - Expected: the next execution sends a **different** `Idempotency-Key` with the new `quote_id`.
   - Covered by Task 3's `re-quotes with a new key after STALE_SETTLEMENT_QUOTE`.
5. **Write-off reason edges.**
   - The inputs: whitespace only, exactly 500 characters after trimming, 501 characters, and surrounding spaces.
   - Expected: the reason is sent trimmed, and a retry resends it unchanged.
   - Covered by Task 4's `validateWriteOffReason` tests and `keeps the key and the reason locked after a lost response`.

---

### Task 1: Claim, and the payment-history endpoint (backend)

**Files:**
- Modify: `docs/tasks.md` (the claim; first commit, together with this plan)
- Create: `backend/src/main/java/com/serfira/contract/application/ContractLookupPort.java`
- Modify: `backend/src/main/java/com/serfira/contract/application/ContractQueryService.java` (`implements ContractLookupPort`)
- Modify: `backend/src/main/java/com/serfira/payment/infrastructure/PaymentRepository.java`, `PaymentAllocationRepository.java`
- Modify: `backend/src/main/java/com/serfira/payment/api/PaymentResponse.java` (extract `excessOf`)
- Create: `backend/src/main/java/com/serfira/payment/api/ContractPaymentResponse.java`
- Create: `backend/src/main/java/com/serfira/payment/application/PaymentHistoryQueryService.java`
- Create: `backend/src/main/java/com/serfira/payment/api/ContractPaymentController.java`
- Modify: `backend/src/main/java/com/serfira/shared/security/ResourceServerSecurityConfiguration.java`
- Modify: `docs/04_GAPS_ADDENDUM.md` §3.4 (row after `GET /contracts/{id}/statement`), `docs/03_DOMAIN_MODEL.md` §2 API table, `README.md` endpoint table
- Test: `backend/src/test/java/com/serfira/payment/ContractPaymentHistoryIT.java` (new), `backend/src/test/java/com/serfira/shared/security/EndpointRoleMatrixIT.java`

**Interfaces:**
- Produces:
  - `interface ContractLookupPort { void requireExists(UUID contractId); }`. It throws `ContractNotFoundException` and is implemented with `contracts.existsById`.
  - `List<Payment> PaymentRepository.findByContractIdOrderByPaidAtDescPaymentNoDesc(UUID contractId)`.
  - `List<PaymentAllocation> PaymentAllocationRepository.findByPaymentIdIn(Collection<UUID> paymentIds)`.
  - `record ContractPaymentResponse(UUID id, String paymentNo, BigDecimal amount, PaymentChannel channel, OffsetDateTime paidAt, PaymentStatus status, BigDecimal excessAmount, OffsetDateTime voidedAt, String voidReason, List<PaymentAllocationResponse> allocations)`, with `static ContractPaymentResponse from(Payment, List<PaymentAllocation>)`.
  - `List<ContractPaymentResponse> PaymentHistoryQueryService.forContract(UUID contractId)`, `@Transactional(readOnly = true)`. It calls `requireExists` first; with no payments it skips the allocation query.
  - `GET /api/v1/contracts/{id}/payments` → 200 `ApiResponse.ok(List<ContractPaymentResponse>)`.

- [ ] **Step 1: Claim T39 in `docs/tasks.md` and commit it with this plan.**
  - Add `## Sprint 8b — Payments, Settlement & Write-off UI` after Sprint 8, holding a full `### T39 — FE payments tab, settlement and write-off flows (5 pts)` ticket in the `docs/agents/issue-tracker.md` format:
    - `Status: IN PROGRESS`;
    - Goal, Scope, Business Rules and Implementation Notes summarise Decisions 1–13 and link this plan rather than restating them;
    - Dependencies: T33, T12/T13, T19;
    - Acceptance Criteria come from the Review Focus;
    - Risks: the 2 → 5 pt re-estimate.
  - Remove the T39 row from the Later-sprints table. Reword the table's intro ("Sprint 8b stays a one-line row until its planning") to say that Sprint 8b is now detailed above and that no later story is planned yet.
  - Change "Sprint 8b = 2 pts" to 5, and line 26's "Sprint 8b (T39) is next" to "T39 is IN PROGRESS".
  - Commit: `docs(t39): claim T39 with the Sprint 8b ticket and plan`.
- [ ] **Step 2: Write the failing tests.**
  - `ContractPaymentHistoryIT`:
    - Fixture: copy `PaymentVoidIT`'s fixture (seeding, `PaymentClockTestConfiguration`, terms, `PERIOD_TOTAL = "1573333.33"`) and its `postedPayment` and `voidPayment` helpers.
    - `listsEveryPaymentNewestFirstWithAllocationsExcessAndVoidFields`:
      - Setup, in this order:
        1. Post A = `PERIOD_TOTAL`.
        2. Void A with reason `"operator error"`, so period 1 is open again.
        3. Post B = `1623333.33`, which pays period 1 and leaves `50000.00` as EXCESS.
      - Order: `GET` as ADMIN returns `[B, A]`. Both share `paid_at` under the fixed clock, so `payment_no desc` decides.
      - B: `status` is `POSTED`; `excess_amount` is `"50000.00"`; the allocations include `{installment_id: null, allocation_type: "EXCESS", amount: "50000.00"}`; `voided_at` and `void_reason` are JSON `null`.
      - A: `status` is `VOIDED`, `voided_at` is non-null, `void_reason` is `"operator error"`, and its allocations are still present.
      - `amount` is the string `"1573333.33"`.
    - `aContractWithoutPaymentsAnswersAnEmptyList`: 200, `data` is `[]`.
    - `anUnknownContractIsNotFound`: a random id gives 404 `CONTRACT_NOT_FOUND`.
    - `financeReadsButManajemenIsForbidden`: `FINANCE` gets 200; `MANAJEMEN` gets 403 `FORBIDDEN`.
  - `EndpointRoleMatrixIT`:
    - Add `allowedRoles.put("GET /api/v1/contracts/" + RANDOM_ID + "/payments", List.of(ADMIN, FINANCE));` with a T39 comment, after the statement row.
    - Keep the `GET /api/v1/payments` denied row: there is still no global payment list.
- [ ] **Step 3: Run them and confirm they fail.**
  Run: `cd backend && ./gradlew test --tests '*ContractPaymentHistoryIT' --tests '*EndpointRoleMatrixIT'`
  Expected: the history tests fail (403 from `denyAll`), and the new matrix cells for ADMIN/FINANCE fail.
- [ ] **Step 4: Implement the port, the repository methods, `excessOf`, the response, the service, the controller and the matcher.**
  - Service: group the allocations by `getPayment().getId()`, keeping each list in repository order.
  - Controller:
    - Mirror `ContractCreditController`'s read-endpoint `@Operation` and `@ApiResponses` (200, 401, 403 "allowed roles: ADMIN_OPERASIONAL, FINANCE", 404 `CONTRACT_NOT_FOUND`), under `PaymentController`'s `@Tag`.
    - The Javadoc states Decisions 3 and 4. Also replace `PaymentController`'s "there is no GET /payments/{id} yet" sentence with a pointer to this read.
  - Matcher: placed next to the statement row, with the single-segment-`*` comment style used there.
- [ ] **Step 5: Add the doc rows.**
  - Addendum §3.4: `| \`GET /contracts/{id}/payments\` | ✅ | ✅ | ❌ | — |`.
  - DM §2: `| GET | /api/v1/contracts/{id}/payments | riwayat pembayaran kontrak (POSTED/VOIDED, alokasi) |`.
  - README: `| \`GET\` | \`/api/v1/contracts/{id}/payments\` | Payment history with allocations and void details |`.
- [ ] **Step 6: Run the payment tests, the contract tests and the matrix.**
  Run: `./gradlew test --tests 'com.serfira.payment.*' --tests 'com.serfira.contract.*' --tests '*EndpointRoleMatrixIT'`
  Expected: all green, and `PaymentVoidIT` unchanged after the `excessOf` extraction.
- [ ] **Step 7: Commit:** `feat(t39): GET /contracts/{id}/payments history read model`

### Task 2: Pembayaran tab and statement links (frontend)

**Files:**
- Create: `frontend/lib/paymentLabels.ts` (move `ALLOCATION_LABELS` and `channelLabel` out of `PaymentResult.tsx`)
- Modify: `frontend/components/payments/PaymentResult.tsx` (import them)
- Modify: `frontend/types/payment.ts`, `frontend/hooks/usePayment.ts`, `frontend/hooks/useSession.ts`
- Create: `frontend/components/payments/PaymentHistoryTable.tsx`, `frontend/components/payments/ContractPaymentsPanel.tsx`
- Modify: `frontend/components/contracts/ContractDetailView.tsx`, `frontend/components/contracts/StatementView.tsx` (also its Javadoc line ~62)
- Test: `frontend/components/payments/PaymentHistoryTable.test.tsx` (new), `frontend/components/contracts/ContractDetailView.test.tsx`, `frontend/components/contracts/StatementView.test.tsx`

**Interfaces:**
- Consumes: Task 1's wire shape.
- Produces:
  - `interface ContractPayment { id; payment_no; amount: Money; channel: PaymentChannel; paid_at; status: PaymentStatus; excess_amount: Money; voided_at: string | null; void_reason: string | null; allocations: PaymentAllocation[] }`.
  - `contractPaymentsKey = (id: string) => ["contract", id, "payments"] as const`.
  - `useContractPayments(contractId: string, enabled: boolean)`, which calls `apiFetch<ContractPayment[]>(\`/contracts/${id}/payments\`)`.
  - `useCanReadPayments(): boolean`, over `["ADMIN_OPERASIONAL", "FINANCE"]`.
  - `allocationLabel(type: AllocationType): string` and `channelLabel(channel: PaymentChannel): string`.
  - `PaymentHistoryTable({ payments, installments, highlightId }: { payments: ContractPayment[]; installments: Installment[] | undefined; highlightId: string | null })`.
  - `ContractPaymentsPanel({ contractId, installments, highlightId })`, which handles loading, error with "Coba lagi", and empty states.
  - `paymentLink(contractId: string, paymentId: string): string`, exported from `StatementView.tsx`. It returns `` `/contracts/${contractId}?tab=payments&payment=${paymentId}` ``.

- [ ] **Step 1: Write the failing tests.**
  - `PaymentHistoryTable.test.tsx`:
    - `lists payments as returned with a VOIDED badge, the void time and reason`: the row order equals the input order; a VOIDED row shows `formatDateTime(voided_at)` and the reason.
    - `highlights the linked payment row`: the row `#payment-<id>` has `data-highlighted="true"`, and no other row has it.
    - `expands allocation lines in period order with EXCESS last`: clicking "Rincian" (`aria-expanded` flips) lists the lines with `periodLabel` and the labels "Denda", "Bunga" and "Pokok" in waterfall order per period, then "Kelebihan".
    - `shows the empty message`: "Belum ada pembayaran untuk kontrak ini."
  - `ContractDetailView.test.tsx`:
    - Extend the `next/navigation` mock with `useSearchParams: () => searchParams`, a `let` that each test resets.
    - `shows the Pembayaran tab for FINANCE and hides it for MANAJEMEN`.
    - `opens the tab named in ?tab= and falls back to Jadwal when the role cannot see it`:
      - `?tab=payments&payment=<id>` as FINANCE shows the panel and highlights the row;
      - `?tab=payments` as MANAJEMEN shows Jadwal.
  - `StatementView.test.tsx`:
    - `links PAYMENT rows and their reversal to the payments tab`: both anchors have `href` equal to `paymentLink(contractId, ref_id)`.
    - `leaves SETTLEMENT and WRITE_OFF ids unlinked`: the short id is still shown, with no anchor.
- [ ] **Step 2: Run them and confirm they fail.**
  Run: `cd frontend && npx vitest run components/payments components/contracts`
  Expected: the new tests fail (missing modules or elements).
- [ ] **Step 3: Implement.**
  - Tab order: Jadwal, Pembayaran (`useCanReadPayments`), Kredit, Ledger.
  - `ContractDetailView` reads `useSearchParams()` once and passes the highlight id down. `defaultValue` follows Decision 11.
  - `PaymentHistoryTable`:
    - Sort the allocation lines by the installment's `period_no` (looked up in `installments`), then by waterfall rank PENALTY → INTEREST → PRINCIPAL. EXCESS, which has no installment, goes last. The API returns allocations in no defined order.
    - Scroll the highlighted row into view once, with `element.scrollIntoView?.({ block: "center" })` (jsdom lacks it).
    - A VOIDED amount renders muted.
  - In `StatementView`, a PAYMENT row wraps the short id in a `Link`; every other ref type keeps the current span.
  - Rewrite the T33 Javadoc sentence to state Decision 10.
  - If `npm run build` reports a missing Suspense boundary for `useSearchParams`, wrap `<ContractDetailView>` in `<Suspense>` in `app/(dashboard)/contracts/[id]/page.tsx`.
- [ ] **Step 4: Run the tests, then the full suite.**
  Run: `npx vitest run components/payments components/contracts`, then `npm test && npm run typecheck && npm run lint`.
  Expected: green; `PaymentResult.test.tsx` is unchanged.
- [ ] **Step 5: Commit:** `feat(t39): Pembayaran tab and statement links to payments`

### Task 3: Settlement modal (frontend)

**Files:**
- Create: `frontend/types/settlement.ts`, `frontend/lib/settlement.ts`, `frontend/hooks/useSettlement.ts`, `frontend/components/contracts/SettlementDialog.tsx`
- Modify: `frontend/components/contracts/ContractDetailView.tsx` (mount it in the header)
- Test: `frontend/lib/settlement.test.ts`, `frontend/components/contracts/SettlementDialog.test.tsx` (new)

**Interfaces:**
- Produces:
  - Types:
    - `SettlementQuote` mirrors `SettlementQuoteResponse` field for field, in snake_case: money is `Money`, `contract_version` is a `number` and `status` is a `string`.
    - `SettlementExecution` mirrors `SettlementExecutionResponse` and its two nested records the same way.
  - `settlementBreakdown(quote: SettlementQuote): { futureInterestGross: Money; cashDue: Money | null; creditExceedsGross: boolean }`. `cashDue` is `null` exactly when `creditExceedsGross` is true.
  - `quoteLifetimeMs(quote: Pick<SettlementQuote, "quoted_at" | "valid_until">): number`.
  - `remainingMs(lifetimeMs: number, receivedAtMs: number, nowMs: number): number`, clamped at 0.
  - `formatCountdown(ms: number): string`: `"mm:ss"`, with seconds rounded **up**.
  - `useSettlementQuote(contractId)`: a mutation that sends `POST /settlements/quote` with `{contract_id}` and **no** key. Its `onSettled` invalidates the contract prefix and the list (Decision 9).
  - `useExecuteSettlement(contractId)`: a mutation over `{ quoteId, idempotencyKey }` that sends `POST /settlements` with `{quote_id}` and the `Idempotency-Key` header; `onSettled` is the same.
  - `SettlementDialog({ contractId, canStartNew }: { contractId: string; canStartNew: boolean })`.

- [ ] **Step 1: Write the failing `lib/settlement.test.ts`.**
  - `settlementBreakdown`:
    - The base quote is:
      - `outstanding_principal` `"10000000.00"`, `unpaid_billed_interest` `"150000.00"`, `accrued_interest` `"50000.00"`;
      - `penalty_outstanding` `"25000.00"`, `rebate_amount` `"300000.00"`, `admin_fee` `"100000.00"`;
      - `gross_amount` `"10625000.00"`, `cash_due` `"10625000.00"`, `available_credit` `"0.00"`.
    - Cases:

      | `available_credit` | `futureInterestGross` | `cashDue` | `creditExceedsGross` |
      |---|---|---|---|
      | `"0.00"` | `"600000.00"` | `"10625000.00"` | false |
      | `"125000.00"` | | `"10500000.00"` | |
      | `"10625000.00"` | | `"0.00"` | false |
      | `"10625000.01"` | | `null` | true |
  - `quoteLifetimeMs`:
    - `+07:00` instants 15 minutes apart give `900000`;
    - a `Z` start and a `+07:00` end for the same span give `900000`;
    - microsecond fractions (`10:00:00.123456+07:00` → `10:15:00.123456+07:00`) give `900000`.
  - `remainingMs(900000, 1000, 1000)` is `900000`; `(900000, 1000, 900500)` is `500`; `(900000, 1000, 2_000_000)` is `0`.
  - `formatCountdown`: `900000` → `"15:00"`, `61000` → `"01:01"`, `500` → `"00:01"`, `0` → `"00:00"`.
- [ ] **Step 2: Write the failing `SettlementDialog.test.tsx`.**
  - Mock `apiFetch` by path, as `CreditPanel.test.tsx` does. Mock `sonner`'s `toast`.
  - Use `vi.useFakeTimers({ shouldAdvanceTime: true })` and `userEvent.setup({ advanceTimers: vi.advanceTimersByTime })` where time matters.
  - Tests:
    - `shows the breakdown with the derived cash due after Lihat Quote Pelunasan`:
      - Labels: Outstanding Pokok, Bunga Tertunggak, Bunga Berjalan, Bunga Masa Depan, Rebate, Denda, Admin Fee, Total Pelunasan, Kredit Nasabah, Total Cash Due.
      - Total Cash Due shows `formatMoney("10500000.00")` for credit `"125000.00"`, and the quote's own `cash_due` is not shown as the cash due.
    - `starts the countdown from the server lifetime whatever the browser clock says`:
      - The quote's `quoted_at` and `valid_until` fall in **2020**; the dialog shows "15:00".
      - After `vi.advanceTimersByTime(900_000)`, "Lunasi Sekarang" is disabled and "Ambil quote baru" is visible.
    - `executes with the quote id and one key after confirmation`:
      - "Lunasi Sekarang" opens the confirmation, which shows the cash due.
      - "Ya, lunasi" posts `/settlements` with body `{"quote_id": …}` and an `Idempotency-Key`.
      - The success toast names `settlement_no`, and the dialog closes.
    - `re-quotes with a new key after STALE_SETTLEMENT_QUOTE`:
      - It shows the table copy; "Ambil quote baru" calls the quote again.
      - The next execute carries the new `quote_id` and a different key.
      - The same test is repeated for `SETTLEMENT_QUOTE_EXPIRED`, with `it.each`.
    - `retries a lost response with the same key and quote after the contract closes`:
      - The first execute rejects with `TypeError("Failed to fetch")`; then rerender with `canStartNew={false}`.
      - "Coba lagi" is visible and "Ambil quote baru" is absent.
      - The retry carries the same key and `quote_id` and resolves into the success toast.
    - `blocks execution when available credit exceeds the gross`: the `CREDIT_EXCEEDS_SETTLEMENT` copy shows before any execute call, and "Lunasi Sekarang" is disabled.
    - `a spent key offers only Mulai form baru`: `CONFLICT` shows that alert, and clicking it remounts with no quote.
    - `hides the trigger when no operation is open and the contract is not ACTIVE`.
- [ ] **Step 3: Run them and confirm they fail.**
  Run: `npx vitest run lib/settlement.test.ts components/contracts/SettlementDialog.test.tsx`
  Expected: FAIL, because the modules are missing.
- [ ] **Step 4: Implement.**
  - `settlementBreakdown` computes Decision 5 with `addMoney`, `subtractMoney` and `compareMoney`.
  - `SettlementDialog` mirrors `ApplyCreditDialog`: an outer component holding `{ id, initiallyOpen }`, and an inner `SettlementOperation` remounted by `key`.
  - The quote is state of the inner operation. A new quote_id remounts a nested `SettlementExecutionStep` keyed by `quote_id`, which owns `useIdempotencyKey()` (Decision 7). Capture `receivedAt = Date.now()` when the quote resolves.
  - The countdown is a `setInterval(1000)` inside `SettlementExecutionStep`, cleared on unmount.
  - Success closes the dialog on the detail page. The refetched contract shows CLOSED, which satisfies FE §2.8's "redirect ke contract detail" without navigating.
  - The confirmation is a step inside the same dialog, not a nested dialog.
    - Copy: "Terima kas {cash due} dari nasabah dan tutup kontrak? Tindakan ini tidak dapat dibatalkan." Buttons: "Kembali" and "Ya, lunasi" (pending: "Memproses…").
  - Success toast: `` `Pelunasan ${settlement_no} berhasil. Kas diterima ${formatMoney(cash_received)}; kontrak ditutup.` ``
  - Error copy follows the table in the Decisions.
  - Closing is blocked while a request is pending.
  - In `ContractDetailView`, mount `<SettlementDialog contractId canStartNew={status === "ACTIVE"} />` whenever `canWrite`. The trigger label is "Pelunasan Dipercepat" (outline).
- [ ] **Step 5: Run the tests, then the full suite.**
  Run: `npx vitest run lib/settlement.test.ts components/contracts`, then `npm test && npm run typecheck && npm run lint`.
  Expected: green.
- [ ] **Step 6: Commit:** `feat(t39): settlement quote and execution modal`

### Task 4: Write-off dialog (frontend)

**Files:**
- Modify: `frontend/types/contract.ts`, `frontend/hooks/useContract.ts`, `frontend/components/contracts/ContractDetailView.tsx`
- Create: `frontend/components/contracts/WriteOffDialog.tsx`
- Test: `frontend/components/contracts/WriteOffDialog.test.tsx` (new), `frontend/components/contracts/ContractDetailView.test.tsx`

**Interfaces:**
- Produces:
  - `interface WriteOffResult { contract_id; contract_no; status: ContractStatus; write_off_reason; written_off_at; total_written_off: Money; installments: { installment_id; period_no: number; written_off_amount: Money }[] }`.
  - `useWriteOff(contractId)`: a mutation over `{ reason, idempotencyKey }` that sends `POST /contracts/${id}/write-off` with `{reason}` and the header. Its `onSettled` invalidates the contract prefix and `["contracts"]`.
  - `validateWriteOffReason(input: string): { reason: string } | { error: string }`. It trims; empty gives "Alasan wajib diisi."; more than 500 UTF-16 units after trimming (Java's `String.length()`, which is what `@Size(max = 500)` counts) gives "Alasan maksimal 500 karakter.".
  - `WriteOffDialog({ contractId, contractNo, canStartNew })`.

- [ ] **Step 1: Write the failing tests.**
  - `validateWriteOffReason`:
    - `"   "` gives the empty error;
    - `"  rusak total  "` gives `{ reason: "rusak total" }`;
    - `"a".repeat(500)` passes;
    - `"a".repeat(501)` gives the max error;
    - `" " + "a".repeat(500) + " "` passes.
  - `sends the trimmed reason with one key and reports the written-off total`: the body is `{"reason":"rusak total"}`, and the toast shows `` `Kontrak ${contract_no} di-write-off: ${formatMoney(total_written_off)}.` ``.
  - `keeps the key and the reason locked after a lost response`:
    - A `TypeError` leaves the textarea disabled.
    - After rerendering with `canStartNew={false}`, the retry sends the same key and body.
  - `CONTRACT_STATE_INVALID is definitive`: the server message shows, the textarea is re-enabled, and there is no "Coba lagi".
  - `a spent key remounts with a new key and an empty reason`: after `IDEMPOTENCY_KEY_EXPIRED`, "Mulai form baru" leads to the next submit carrying a different key.
  - In `ContractDetailView`, `shows Pelunasan Dipercepat and Write-off only for ADMIN_OPERASIONAL on an ACTIVE contract`: they are absent for FINANCE, and absent on CLOSED when no operation is open.
- [ ] **Step 2: Run them and confirm they fail.**
  Run: `npx vitest run components/contracts/WriteOffDialog.test.tsx components/contracts/ContractDetailView.test.tsx`
  Expected: FAIL.
- [ ] **Step 3: Implement the dialog with the same outer/inner operation pattern as Task 3.**
  - Trigger: "Write-off", destructive outline.
  - Title: "Write-off kontrak {contract_no}".
  - Description: "Seluruh sisa piutang dihapusbukukan dan kontrak menjadi TERMINATED. Tindakan ini tidak dapat dibatalkan."
  - Field: the label "Alasan write-off" on a `Textarea`, with a `{n}/500` counter.
  - Submit: "Write-off sekarang" (pending: "Memproses…").
  - The dialog mounts whenever `canWrite`, with `canStartNew={status === "ACTIVE"}`.
- [ ] **Step 4: Run the tests, then the full suite.**
  Run: `npm test && npm run typecheck && npm run lint && npm run build`
  Expected: green, and the build succeeds.
- [ ] **Step 5: Commit:** `feat(t39): write-off dialog with reason`

### Task 5: Docs and close-out

**Files:**
- Modify: `docs/06_FRONTEND_SPEC.md` §2.4, §2.7, §2.8
- Modify: `docs/tasks.md`
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Update the FE spec.**
  - §2.4: the tab order and the Pembayaran gate (ADMIN_OPERASIONAL and FINANCE), the `?tab=` and `?payment=` parameters, and the write dialogs' key rule. This is one sentence pointing at the §2.4 Kredit wording.
  - §2.7: replace "Link ke payment/settlement menyusul di T39 …" with Decision 10.
  - §2.8:
    - add the Total Cash Due derivation (Decision 5) and the skew-safe countdown (Decision 6);
    - add the execute error table in short form;
    - note that "Bunga Masa Depan" is shown so the lines sum to the total.
- [ ] **Step 2: Run the full verification and record the counts.**
  Run: `cd backend && ./gradlew check --rerun-tasks`, then `cd ../frontend && npm test && npm run lint && npm run typecheck && npm run build`
  Expected: BUILD SUCCESSFUL with 0 failed, and the frontend green. Note the suite, test and file counts.
- [ ] **Step 3: Close T39 in `docs/tasks.md`.**
  - The ticket: `Status: DONE` plus an implementation note (what shipped, the decisions that held, the verification counts).
  - A Completed Work row.
  - Current Project State:
    - 20 endpoints, adding the payment history to the list at line ~48;
    - the frontend test and file counts;
    - "Sprint 8b COMPLETE";
    - the next-sprint line.
  - Tick T39 in `docs/PROGRESS.md`.
- [ ] **Step 4: Commit:** `docs(t39): FE spec updates and T39 close-out`
