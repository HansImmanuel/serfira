# T33 — Payment, Statement Filters and Credit Apply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish Sprint 7's servicing screens: record a payment from the browser and see its allocation, filter the ledger statement, and apply customer credit, with no stale data after a write.

**Architecture:** Frontend-only (`frontend/`), no backend change. New wire types and a Zod schema feed a `RecordPaymentForm` on a new `/contracts/[id]/payments/new` route; every write invalidates the `["contract", id]` query prefix (which covers detail, installments, credit and statement) plus `["contracts"]`. The statement and credit screens from T32 grow filters and an apply action. Installment table and read-only credit tab already exist from T32 and are reused unchanged.

**Tech Stack:** Next.js 16.4.6 App Router, React 19, TanStack Query v5, React Hook Form + Zod 4, shadcn/ui, Vitest + RTL (jsdom).

**Spec:** `docs/tasks.md` § T33; `docs/06_FRONTEND_SPEC.md` §2.4–§2.7, §3; ADR-009, ADR-011, ADR-013 (A-8), ADR-015, ADR-017, ADR-023, ADR-024. Backend contracts: `PaymentController`/`PaymentRequest`/`PaymentResponse`, `ContractCreditController`/`ApplyCreditRequest`/`CreditApplicationResponse`, `ContractController#statement`.

**Branch:** `t33-payment-statement-credit` from `main` @ `3a66c98`.

## Decision taken at planning (2026-11-25)

FE §2.6 and T33 list a "Tanggal Bayar" (paid-at, max now) and a "Catatan" (note) input. The API accepts
only `contract_id`, `amount`, `channel`: `paid_at` is always the server clock (TS §2.0) and `payment` has
no note column. **Decision (user, 2026-11-25): drop both inputs; no backend change.** The success view
shows the server's `paid_at`. Task 6 records this in FE §2.6 and as a `Decision` paragraph on T33.

FE §2.7's "Kontrak Ref — link ke payment/settlement" has no target route (no `GET /payments/{id}`; the
payments tab is T39). The statement shows `ref_type` + a short `ref_id` with the full id in `title`; the
link is noted as T39 scope in Task 6.

## Global Constraints

- Money and rates are exact decimal strings (ADR-024); never `Number`/`parseFloat` on a money value. Use `lib/decimal.ts` (`normalizeMoney`, `compareMoney`) and `lib/format.ts` (`formatMoney`, `formatDate`, `formatDateTime`).
- Operator money input: `> 0`, at most 17 integer digits, scale ≤ 2, never rounded (FE §3.1).
- Only `lib/api.ts` unwraps the `{data, error}` envelope; hooks call `apiFetch<T>`.
- Wire fields are snake_case; types live in `frontend/types/`.
- `Idempotency-Key` is generated once per form mount (`useIdempotencyKey`) and reused across retries; disabling submit is UX, not the guarantee (FE §1.4, ADR-017). A fresh key only comes from remounting the form.
- Write actions (record payment, apply credit) render only for `ADMIN_OPERASIONAL` (`useCanWrite`); credit/statement only for `ADMIN_OPERASIONAL`/`FINANCE`. UX only — the backend matrix enforces (ADR-015).
- The UI displays allocation, never computes it (ADR-009/011). No running balance on the statement (ADR-013 A-8).
- UI copy is Bahasa Indonesia, matching existing screens.
- Commit messages: `feat(t33): …` / `docs(t33): …`, with no AI co-author or attribution trailer (the repo owner is the sole author).

## Review Focus

1. **Operator edits the amount after a failed or timed-out submit.** Same key + different body → 409 `CONFLICT`. Expected: a clear message telling them to check the statement first, and an explicit "Mulai form baru" action that remounts the form with a new key — never automatic key rotation (that could pay twice). Test in Task 2.
2. **Retry after a network error where the first request may have committed.** Both calls carry the identical `Idempotency-Key`; the replayed response renders the same `payment_no`. Test in Task 2.
3. **Statement `from` after `to`.** Expected: inline error, no request issued (the API would answer 400). Test in Task 4.
4. **Credit apply amount above the available balance, or blank.** Blank = apply the full balance (`{}` body); above balance → inline error, no request. Test in Task 5.
5. **Opening `/contracts/[id]/payments/new` for a non-ACTIVE contract.** Expected: an explanatory message and no form (the API would answer 409 `CONTRACT_STATE_INVALID`). Test in Task 3.

---

### Task 1: Payment wire types, form schema and create-payment hook

**Files:**
- Create: `frontend/types/payment.ts`
- Create: `frontend/lib/schemas/payment.ts`
- Create: `frontend/lib/schemas/payment.test.ts`
- Create: `frontend/hooks/usePayment.ts`
- Create: `frontend/hooks/usePayment.test.tsx`
- Modify: `frontend/test/utils.tsx` (`renderWithQueryClient` accepts an optional client and returns it)

**Interfaces:**
- Produces (`types/payment.ts`):
  - `type PaymentChannel = "CASH" | "BANK_TRANSFER" | "VA_STUB" | "EWALLET_STUB"`
  - `type AllocationType = "PENALTY" | "INTEREST" | "PRINCIPAL" | "EXCESS"`
  - `type PaymentStatus = "POSTED" | "VOIDED"`
  - `interface PaymentPayload { contract_id: string; amount: string; channel: PaymentChannel }`
  - `interface PaymentAllocation { installment_id: string | null; allocation_type: AllocationType; amount: Money }`
  - `interface Payment { id; payment_no; contract_id; amount: Money; channel: PaymentChannel; paid_at: string; status: PaymentStatus; excess_amount: Money; allocations: PaymentAllocation[] }`
- Produces (`lib/schemas/payment.ts`):
  - `PAYMENT_CHANNEL_OPTIONS: { value: PaymentChannel; label: string }[]` — labels `Tunai`, `Transfer bank`, `Virtual account (stub)`, `E-wallet (stub)`.
  - `paymentSchema` (Zod object `{ amount: string; channel: PaymentChannel }`), `type PaymentValues`, `emptyPaymentValues: PaymentValues` (`amount: ""`, `channel: "CASH"`).
  - `toPaymentPayload(contractId: string, values: PaymentValues): PaymentPayload`
- Produces (`hooks/usePayment.ts`):
  - `interface CreatePaymentCommand { payload: PaymentPayload; idempotencyKey: string }`
  - `useCreatePayment(contractId: string)` → `useMutation<Payment, Error, CreatePaymentCommand>`; POST `/payments` with header `Idempotency-Key`; on success invalidates `["contract", contractId]` and `["contracts"]`.
- Produces (`test/utils.tsx`): `renderWithQueryClient(ui, client?: QueryClient): RenderResult & { queryClient: QueryClient }`.

- [ ] **Step 1: Write the failing schema tests** in `lib/schemas/payment.test.ts`

```ts
it.each(["", "0", "0.00", "-5", "1.234", "1e3", "100000000000000000"])("rejects amount %j", (amount) => {
  expect(paymentSchema.safeParse({ amount, channel: "CASH" }).success).toBe(false);
});
it("normalizes a valid amount to scale 2", () => {
  expect(paymentSchema.parse({ amount: "1500000", channel: "BANK_TRANSFER" }).amount).toBe("1500000.00");
});
it("rejects an unknown channel", () => {
  expect(paymentSchema.safeParse({ amount: "1", channel: "CHEQUE" }).success).toBe(false);
});
it("builds the API payload with no paid_at or note", () => {
  const values = paymentSchema.parse({ amount: "250000.5", channel: "CASH" });
  expect(toPaymentPayload("c1", values)).toEqual({ contract_id: "c1", amount: "250000.50", channel: "CASH" });
});
```

- [ ] **Step 2: Run to verify they fail** — `cd frontend; npx vitest run lib/schemas/payment.test.ts` → FAIL (module not found).

- [ ] **Step 3: Implement `types/payment.ts` and `lib/schemas/payment.ts`.** Amount rule = the create-contract `moneyField` (`normalizeMoney`) plus `compareMoney(normalized, "0.00") > 0`, message `Jumlah bayar harus lebih dari 0, maksimal 17 digit bulat dan 2 desimal.`. Do not export or modify `createContract.ts`'s private `moneyField`; a local equivalent is fine.

- [ ] **Step 4: Run to verify they pass** — same command → PASS.

- [ ] **Step 5: Write the failing hook test** in `hooks/usePayment.test.tsx` (mock `@/lib/api` as `ContractDetailView.test.tsx` does):
  - `posts to /payments with the Idempotency-Key header and JSON body` — `expect(apiFetch).toHaveBeenCalledWith("/payments", { method: "POST", headers: { "Idempotency-Key": "key-1" }, body: JSON.stringify(payload) })`.
  - `invalidates the contract prefix and the contract list on success` — seed `queryClient.setQueryData(["contract","c1","installments"], [])`, `["contract","c1","statement", …]` and `["contracts", {}]`; after `mutateAsync`, each `queryClient.getQueryState(key)?.isInvalidated` is `true`.
  - `does not invalidate on failure` — `apiFetch` rejects with `new ApiError("CONTRACT_STATE_INVALID","…",409)`; seeded state stays `isInvalidated === false`.

- [ ] **Step 6: Run to verify it fails**, then **implement `useCreatePayment`** and the `renderWithQueryClient` change (use `renderHook` with a wrapper built from the same client, or render a tiny harness component — either is fine).

- [ ] **Step 7: Run** `npx vitest run hooks/usePayment.test.tsx lib/schemas/payment.test.ts` → PASS; `npm test` → all prior tests still pass.

- [ ] **Step 8: Commit** — `git add frontend/types/payment.ts frontend/lib/schemas/payment.ts frontend/lib/schemas/payment.test.ts frontend/hooks/usePayment.ts frontend/hooks/usePayment.test.tsx frontend/test/utils.tsx` → `feat(t33): add payment types, schema and create-payment hook`.

---

### Task 2: Payment form and allocation result

**Files:**
- Create: `frontend/components/payments/RecordPaymentForm.tsx`
- Create: `frontend/components/payments/PaymentResult.tsx`
- Create: `frontend/components/contracts/installmentLabels.ts`
- Create: `frontend/components/payments/RecordPaymentForm.test.tsx`
- Create: `frontend/components/payments/PaymentResult.test.tsx`

**Interfaces:**
- Consumes: `useCreatePayment`, `paymentSchema`, `toPaymentPayload`, `PAYMENT_CHANNEL_OPTIONS`, `emptyPaymentValues` (Task 1); `useIdempotencyKey`; `useInstallments(id)`.
- Produces:
  - `periodLabel(installments: Installment[] | undefined, installmentId: string | null): string` in `installmentLabels.ts` — `"Angsuran ke-<period_no>"`, `"Kredit nasabah"` for `null`, the first 8 chars of the id when not found or installments are undefined.
  - `RecordPaymentForm({ contractId, onPosted, onRestart }: { contractId: string; onPosted: (payment: Payment) => void; onRestart: () => void })`
  - `PaymentResult({ contractId, payment, onRecordAnother }: { contractId: string; payment: Payment; onRecordAnother: () => void })`

- [ ] **Step 1: Write the failing `RecordPaymentForm` tests** (mock `@/lib/api`, `@/lib/idempotency` → `newIdempotencyKey` returning `"key-1"` then `"key-2"`):
  - `shows inline errors and does not submit for an invalid amount` — type `0`, submit → text `Jumlah bayar harus lebih dari 0` visible; `apiFetch` not called.
  - `submits contract_id, normalized amount and channel only` — type `1500000`, choose `Transfer bank`, submit → body `{"contract_id":"c1","amount":"1500000.00","channel":"BANK_TRANSFER"}`; `onPosted` called with the resolved `Payment`.
  - `reuses the same Idempotency-Key when retrying after a failure` — first call rejects `new TypeError("Failed to fetch")`, second resolves; submit twice → both calls' `headers["Idempotency-Key"] === "key-1"`. *(Review Focus 2)*
  - `on 409 CONFLICT shows the key-reuse message and a restart action instead of retrying` — reject `new ApiError("CONFLICT","…",409)` → text `Formulir ini sudah dipakai untuk pembayaran dengan isian berbeda` and button `Mulai form baru`; clicking it calls `onRestart`; the submit button is disabled. Same for `IDEMPOTENCY_KEY_EXPIRED`. *(Review Focus 1)*
  - `disables submit while pending` — pending promise → submit button disabled with text `Menyimpan…`.

- [ ] **Step 2: Run to verify they fail** — `npx vitest run components/payments` → FAIL.

- [ ] **Step 3: Implement `RecordPaymentForm`** following `CreateContractForm`'s structure (RHF + `zodResolver`, shadcn `Input`/`Select`/`Button`, `FieldError`). Fields: Jumlah Bayar (text, `inputMode="decimal"`), Channel (select). Helper text under the form: `Tanggal bayar dicatat otomatis oleh server saat pembayaran diterima.` Other `ApiError`s render through `ErrorAlert` and leave submit enabled (same key on retry).

- [ ] **Step 4: Write the failing `PaymentResult` tests:**
  - `lists allocation lines in API order with period labels` — installments `[{id:"i1",period_no:1,…}]`, allocations `PENALTY i1 10000.00`, `INTEREST i1 200000.00`, `EXCESS null 50000.00` → rows read `Angsuran ke-1 · Denda · Rp 10.000`, `Angsuran ke-1 · Bunga · Rp 200.000`, `Kredit nasabah · Kelebihan · Rp 50.000` (assert by row text content).
  - `warns about excess credit when excess_amount > 0` — text `Kelebihan Rp 50.000 disimpan sebagai kredit nasabah` visible; with `excess_amount: "0.00"` the warning is absent.
  - `shows server paid_at, payment_no and a link back to the contract` — `formatDateTime(paid_at)` text present; link `Kembali ke kontrak` has `href="/contracts/c1"`; button `Catat pembayaran lain` calls `onRecordAnother`.

- [ ] **Step 5: Implement `PaymentResult` and `periodLabel`.** Component labels: `PENALTY→Denda`, `INTEREST→Bunga`, `PRINCIPAL→Pokok`, `EXCESS→Kelebihan`. Excess check is `compareMoney(payment.excess_amount, "0.00") > 0`. Warning text continues: `…dan tidak dipakai otomatis. Terapkan dari tab Kredit.`

- [ ] **Step 6: Write `periodLabel` cases** inside `PaymentResult.test.tsx` (`null` → `Kredit nasabah`; unknown id `abcdef12-…` → `abcdef12`; undefined installments → short id).

- [ ] **Step 7: Run** `npx vitest run components/payments` → PASS.

- [ ] **Step 8: Commit** → `feat(t33): add payment form with stable idempotency key and allocation result`.

---

### Task 3: Payment route and the detail-page entry point

**Files:**
- Create: `frontend/app/(dashboard)/contracts/[id]/payments/new/page.tsx`
- Create: `frontend/components/payments/RecordPaymentView.tsx`
- Create: `frontend/components/payments/RecordPaymentView.test.tsx`
- Modify: `frontend/components/contracts/ContractDetailView.tsx` (header actions)
- Modify: `frontend/components/contracts/ContractDetailView.test.tsx`

**Interfaces:**
- Consumes: `RecordPaymentForm`, `PaymentResult` (Task 2); `useContract`; `RequireWrite`.
- Produces: `RecordPaymentView({ contractId }: { contractId: string })`. Page = `<RequireWrite><RecordPaymentView contractId={params.id} /></RequireWrite>`, `useParams` as in the statement page.

- [ ] **Step 1: Write failing `RecordPaymentView` tests:**
  - `renders the form for an ACTIVE contract` — heading `Catat Pembayaran` and the contract number visible.
  - `shows no form for a non-ACTIVE contract` — `it.each(["DRAFT","CLOSED","TERMINATED"])` → text `Pembayaran hanya dapat dicatat untuk kontrak berstatus ACTIVE.`; no `Jumlah Bayar` field. *(Review Focus 5)*
  - `swaps to the result after posting and back to a fresh form on "Catat pembayaran lain"` — after a resolved post, `PaymentResult` shows; clicking `Catat pembayaran lain` renders an empty form whose next submit uses `"key-2"`.
  - `"Mulai form baru" after CONFLICT remounts with a new key` — next submit uses `"key-2"`.

- [ ] **Step 2: Run to verify failure**, then **implement `RecordPaymentView`.** State: `formInstance: number` and `posted: Payment | null`; render `<RecordPaymentForm key={formInstance} … onRestart={() => setFormInstance(n => n + 1)} />`. `onRecordAnother` clears `posted` and increments `formInstance`. Loading/error states use `LoadingSpinner`/`ErrorAlert` like `ContractDetailView`.

- [ ] **Step 3: Add the header button in `ContractDetailView`:** `Catat Pembayaran` (`Link` to `/contracts/${id}/payments/new`) when `detail.status === "ACTIVE" && canWrite`.

- [ ] **Step 4: Extend `ContractDetailView.test.tsx`:** `shows "Catat Pembayaran" for ADMIN_OPERASIONAL on an ACTIVE contract` (href asserted); `hides it for FINANCE`; `hides it on a DRAFT contract`.

- [ ] **Step 5: Run** `npx vitest run components/payments components/contracts/ContractDetailView.test.tsx` → PASS; `npm run typecheck` → clean (new route typegen).

- [ ] **Step 6: Commit** → `feat(t33): add payment route and detail-page entry point`.

---

### Task 4: Statement filters

**Files:**
- Modify: `frontend/hooks/useStatement.ts`
- Modify: `frontend/types/contract.ts` (`StatementRow.ref_type: LedgerRefType`)
- Modify: `frontend/components/contracts/StatementView.tsx`
- Modify: `frontend/components/contracts/StatementView.test.tsx`

**Interfaces:**
- Produces:
  - `type LedgerRefType = "CONTRACT_ACTIVATION" | "PAYMENT" | "BILLING" | "PENALTY_ACCRUAL" | "PENALTY_WAIVER" | "CREDIT_APPLICATION" | "SETTLEMENT" | "WRITE_OFF"` and `LEDGER_REF_TYPES` (same order) in `types/contract.ts`.
  - `interface StatementFilters { refType: LedgerRefType | null; from: string | null; to: string | null }` (dates `YYYY-MM-DD`).
  - `statementPath(id: string, page: number, filters: StatementFilters): string` — exported for tests; builds with `URLSearchParams` in the order `page, size, ref_type, from, to`, omitting nulls.
  - `useStatement(id: string, page: number, filters: StatementFilters, enabled = true)`; query key `["contract", id, "statement", page, filters]` (still under the `["contract", id]` prefix, so Task 1's invalidation covers it).

- [ ] **Step 1: Write failing tests:**
  - `statementPath` — `{refType:"PAYMENT",from:"2026-11-01",to:"2026-11-30"}` page 0 → `/contracts/c1/statement?page=0&size=20&ref_type=PAYMENT&from=2026-11-01&to=2026-11-30`; all-null → `/contracts/c1/statement?page=0&size=20`.
  - `StatementView sends the selected ref type and date range` — set the `Ref type` select to `PAYMENT` and both date inputs → last `apiFetch` call path equals the string above.
  - `StatementView resets to page 0 when a filter changes` — go to page 2 (`Berikutnya`), change ref type → last call has `page=0`.
  - `StatementView blocks a from-after-to range` — `from=2026-12-01`, `to=2026-11-01` → text `Tanggal awal tidak boleh setelah tanggal akhir.`; no call with those dates. *(Review Focus 3)*
  - `StatementView shows ref_type and a short ref id with the full id as title` — cell text `PAYMENT` and `abcdef12`, element `title` = full uuid.
  - Existing role-gating tests keep passing.

- [ ] **Step 2: Run to verify failure**, then **implement.** Filter bar above the table: `Dari` / `Sampai` (`<Input type="date">`), `Ref type` select with `Semua` (= null) plus the eight values, and `Reset filter`. Filters live in component state (not the URL). A changed filter sets `page` to 0. When `from > to` (string compare on ISO dates), keep the last valid filters for the query and show the inline error. Update the header comment that says filters land with T33.

- [ ] **Step 3: Run** `npx vitest run components/contracts/StatementView.test.tsx` → PASS; `npm run typecheck` → clean.

- [ ] **Step 4: Commit** → `feat(t33): wire statement ref-type and date-range filters`.

---

### Task 5: Apply customer credit

**Files:**
- Modify: `frontend/types/contract.ts` (apply types)
- Modify: `frontend/hooks/useContract.ts` (`useApplyCredit`)
- Create: `frontend/components/contracts/ApplyCreditDialog.tsx`
- Modify: `frontend/components/contracts/CreditPanel.tsx` (dialog entry + period labels)
- Create: `frontend/components/contracts/CreditPanel.test.tsx`

**Interfaces:**
- Consumes: `periodLabel` (Task 2); `useInstallments`; `normalizeMoney`, `compareMoney`; `useCanWrite`.
- Produces:
  - `interface ApplyCreditPayload { amount?: string }`; `interface CreditApplicationResult { applied_amount: Money; available_balance: Money; applications: CreditApplication[] }`.
  - `useApplyCredit(contractId: string)` → mutation of `ApplyCreditPayload` → `CreditApplicationResult`; POST `/contracts/${id}/credit/apply`, body `JSON.stringify(payload)`, **no** `Idempotency-Key`; on success invalidates `["contract", id]` and `["contracts"]`.
  - `validateCreditAmount(input: string, available: Money): { payload: ApplyCreditPayload } | { error: string }` exported from `ApplyCreditDialog.tsx`.
  - `ApplyCreditDialog({ contractId, availableBalance }: { contractId: string; availableBalance: Money })`.
  - `CreditPanel` gains prop `contractStatus: ContractStatus` (passed by `ContractDetailView`).

- [ ] **Step 1: Write failing tests** (`CreditPanel.test.tsx`):
  - `validateCreditAmount` — `""`/whitespace → `{payload:{}}`; `"100000"` with available `"250000.00"` → `{payload:{amount:"100000.00"}}`; `"300000"` → error `Melebihi saldo tersedia (Rp 250.000).`; `"0"`, `"1.234"` → error. *(Review Focus 4)*
  - `shows "Terapkan Kredit" only to ADMIN_OPERASIONAL on an ACTIVE contract with a positive balance` — visible for admin/ACTIVE/`"250000.00"`; absent for FINANCE, for CLOSED, and for `"0.00"`.
  - `applies the full balance with an empty body and refreshes the contract` — blank amount, confirm → `apiFetch("/contracts/c1/credit/apply", { method: "POST", body: "{}" })`; no `Idempotency-Key` header; seeded `["contract","c1","installments"]` invalidated.
  - `shows the API error inline` — reject `ApiError("CREDIT_NOT_APPLICABLE", "…", 409)` → message and code visible in the dialog; dialog stays open.
  - `labels application rows by period` — application with `installment_id: "i1"` and installment period 3 → `Angsuran ke-3`.

- [ ] **Step 2: Run to verify failure**, then **implement.** Dialog uses shadcn `Dialog`, an optional `Jumlah` input (placeholder `Kosongkan untuk menerapkan seluruh saldo`), and a confirm button disabled while pending; close on success with `toast.success("Kredit diterapkan: Rp …")` from `applied_amount`. Keep the CreditPanel docblock accurate (remove "lands with T33").

- [ ] **Step 3: Run** `npx vitest run components/contracts` → PASS.

- [ ] **Step 4: Commit** → `feat(t33): apply customer credit from the Kredit tab`.

---

### Task 6: Docs, backlog close-out and full verification

**Files:**
- Modify: `docs/06_FRONTEND_SPEC.md` §2.6 (fields table: remove Tanggal Bayar and Catatan; add "`paid_at` = server clock (TS §2.0), shown on the success view"), §2.7 (Kontrak Ref: short `ref_id`; link arrives with T39)
- Modify: `docs/tasks.md` (T33 `Decision (2026-11-25)` paragraph, `Status: DONE`, implementation note with counts; Completed Work row; Current Project State; Sprint 7 scope line)
- Modify: `docs/PROGRESS.md` (tick T33)
- Modify: `frontend/README.md` (screens list, if it enumerates them)

- [ ] **Step 1: Full frontend gate** — `cd frontend; npm run lint; npm run typecheck; npm test; npm run build` (build with `NEXT_PUBLIC_API_BASE_URL=https://ci-build.invalid`). Expected: 0 lint problems, typecheck clean, test count > 108 with 0 failed, build lists the new `/contracts/[id]/payments/new` route.
- [ ] **Step 2: Backend unchanged** — `cd backend; ./gradlew check` → green, 121 suites / 932 tests / 0 failed (no backend file touched).
- [ ] **Step 3: Manual browser check** against Compose backend + `npm run dev`: login `admin` → ACTIVE contract → record payment → result shows allocation → back to detail: Jadwal, Kredit and statement show the payment without reload; overpay once to see the excess warning, then apply credit. Record what was checked in the implementation note.
- [ ] **Step 4: Update the docs listed above**; the implementation note records the decision, the files, and the verification commands with test counts.
- [ ] **Step 5: Commit** → `docs(t33): record payment-field decision and close T33`.
