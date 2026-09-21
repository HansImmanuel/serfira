# ADR-006: Contract Creation & Activation Semantics (B5)

- **Status:** Accepted
- **Date:** 2026-09-18
- **Deciders:** Hans (solo developer)

---

## Context

Story B5 delivers the first HTTP surface of Serfira: `POST /api/v1/contracts` (draft), `POST
/api/v1/contracts/{id}/activate`, `GET /api/v1/contracts`, `GET /api/v1/contracts/{id}` and `GET
/api/v1/contracts/{id}/installments`. Four documented rules collide with the natural first
implementation:

1. **Create-time start date vs activation date.** PRD C-1 (P0) requires "tanggal mulai" when the
   contract is created, and `06_FRONTEND_SPEC.md` §2.3 makes it a required form field. But
   `03_DOMAIN_MODEL.md` §1.3 defines `start_date` as *tanggal aktivasi*, and V4's
   `ck_contract_draft_coherence` CHECK enforces `DRAFT ⇒ start_date IS NULL`. Storing the drafted
   date in `start_date` would require weakening an invariant that protects the state machine.
2. **When the schedule exists.** PRD C-2, DM §1.3 ("hanya boleh generate jadwal sekali; activation
   idempotent") and V4's `ACTIVE ⇒ start_date IS NOT NULL` all place schedule generation at
   activation.
3. **When the configuration snapshot is taken.** Addendum §1.1 says `grace_period_days` is
   snapshotted *when the contract is created* and `penalty_rate_daily` *at activation*; DM §1.3 says
   both are snapshotted *at activation*. The columns are `NOT NULL`, so a DRAFT row must carry
   *some* value.
4. **How many contracts one financed asset may carry.** DM §1 shows `Customer 1 ─── n Contract n ───
   1 Asset` and states no uniqueness rule for `(customer, asset)`; nothing in the PRD forbids two
   live contracts for the same asset. A double-submitted create would otherwise finance the same
   asset twice, and `asset.serial_no`/`plate_no` are unique only *when present*, so assets without
   an identifier cannot be de-duplicated by identity at all.

---

## Decision

1. **Two dates, two meanings.** `planned_start_date` (nullable column, V6) is what the operator
   authored while drafting (PRD C-1); `start_date` remains the *effective* activation date.
   `POST /contracts` requires `planned_start_date`; `POST /{id}/activate` accepts an **optional**
   `start_date` override and defaults to `planned_start_date` when omitted — so the frontend's
   "Aktivasi" confirmation (FE §2.4, no date input) works unchanged, and a real disbursement date
   can still be recorded.
2. **The schedule is generated only at activation**, in the same transaction as the DRAFT → ACTIVE
   transition. Create writes no installment, and activation is idempotent **by state**: a repeat on
   an ACTIVE contract returns the current representation without writing; a conflicting `start_date`
   on an ACTIVE contract, or a CLOSED/TERMINATED contract, is `409 CONTRACT_STATE_INVALID`.
3. **Configuration is re-snapshotted at activation.** The DRAFT row carries the values in force when
   it was drafted (the columns are `NOT NULL`), and activation overwrites them with the values in
   force on the activation date. This resolves the Addendum §1.1 / DM §1.3 wording in favour of DM
   §1.3 — the row records the configuration that governed the contract while it lived — and is
   harmless because a DRAFT has neither schedule nor penalty. Addendum §1.1 is updated accordingly.
4. **The financed asset is resolved by identity and reused**: `serial_no` first, then `plate_no`;
   otherwise a new row is inserted. An existing row is reused *unchanged* (the request's brand/model
   are not applied to it). `serial_no` and `plate_no` pointing at different assets is `409 CONFLICT`.
5. **New invariant 18 (DM §3): at most one live (DRAFT | ACTIVE) contract per `(customer_id,
   asset_id)`.** Enforced in the application (`409 DUPLICATE_CONTRACT`) *and* by the partial unique
   index `uq_contract_live_asset` (V7), so a race cannot create two. `CLOSED`/`TERMINATED` release
   the asset (refinancing), and the customer is part of the key so a repossessed asset may later be
   financed for someone else. Monetary terms are deliberately **not** part of the guard: keying on
   `principal` would leave a loophole (two live loans on one asset with different amounts).

6. **Customer identity is reused, never guessed**: an existing customer found by `nik_hash` is
   reused when the phone matches; the same NIK with another phone, or a phone owned by another
   customer, is `409 CONFLICT`; customer PII is never silently overwritten (PRD C-5 is deferred).
7. **Outstanding is one definition, two projections.** `InstallmentBalance` (Java) and the JPQL
   aggregate `ContractInstallmentTotals` both compute *principal + recognized interest + penalty
   − (paid + settled + written-off)*, i.e. future scheduled interest is not receivable (PRD §5A).
   `outstanding` is `null` while a contract is DRAFT because it has no schedule yet.
8. **The wire naming is snake_case project-wide**
   (`spring.jackson.property-naming-strategy=SNAKE_CASE`), matching `docs/` and the frontend spec
   (`contract_no`, `planned_start_date`, `total_elements`). Chosen now because it costs one property
   and would be a breaking rename later. Map keys are unaffected, and money is emitted as a plain
   decimal (`20000000.00`), never scientific notation.
9. **Typed module error codes**: `CONTRACT_NOT_FOUND` (404), `CONTRACT_STATE_INVALID` (409),
   `DUPLICATE_CONTRACT` (409); `VALIDATION_ERROR`/`CONFLICT`/`CONCURRENT_MODIFICATION` are reused.
   Controllers stay HTTP-only; domain guards throw the domain's own exception.
10. **`principal` is computed server-side** as `asset_price − down_payment` and is never accepted
    from the client; money is validated at scale ≤ 2, rates at scale ≤ 4 within `0..1`,
    `1 ≤ tenor ≤ 120`, `0 ≤ down_payment < asset_price` (the DB CHECKs remain the backstop).

---

## Alternatives Considered

- **Store the drafted date directly in `start_date`.** Rejected: it contradicts DM §1.3 and forces a
  relaxation of V4's `ck_contract_draft_coherence`, weakening the guard that keeps a DRAFT from
  looking active.
- **Make `start_date` required at activation and drop the create-time field.** Rejected: discards a
  P0 create field (PRD C-1), contradicts FE §2.3/§2.4, and would silently default to "today" —
  losing the contractual date agreed at drafting.
- **Snapshot configuration at create only.** Considered; rejected because DM §1.3 is explicit that
  the values in force at activation are the ones that matter, and a draft may sit for days.
- **Always insert a new customer row per contract.** Rejected: `uk_customer_nik_hash` makes it
  impossible without changing identity semantics; reuse mirrors the DB's own uniqueness model.
- **Enforce the live-contract rule on `(customer, asset, principal)`.** Rejected: leaves a loophole
  for two live loans on one asset with different amounts.
- **Camel-case JSON with per-DTO `@JsonProperty` names.** Rejected: it repeats the convention in
  every DTO and still drifts; one global property keeps docs, DTOs and frontend aligned.

---

## Consequences

- **C-5 dependency (accepted).** Because the guard includes DRAFT, a mistakenly drafted contract for
  an identified asset blocks creating another for the same pair until the draft is corrected or
  cancelled. `PUT /contracts/{id}` (PRD C-5, DRAFT-only) is the natural remedy and stays deferred
  per `05_SPRINT_PLAN.md`; ADR-007 removes the *accidental* case (double submit), leaving only
  genuine mistakes to be fixed by the C-5 story or an ops action.
- **Unidentified assets are not de-duplicated.** An asset without `serial_no` and `plate_no` has no
  identity to match, so only the idempotency key protects it (documented in
  `ContractCommandService.resolveAsset`).
- **`planned_start_date` is nullable in the column** (forward-only safety for pre-B5 rows) but
  required by the API; activation resolves a `null` effective date into `409 CONTRACT_STATE_INVALID`
  rather than an NPE.
- **List reads depend on the entity graph.** `findAll(Specification, Pageable)` is declared with
  `@EntityGraph(customer, asset)`; if that ever stops being applied, the list degrades to N+1 lazy
  loads inside the read-only transaction (correct but slower) — the fallback is a fetch-join query
  for the page's ids.
- **Auth for these endpoints is "authenticated"** (default-deny, ADR-005); the role matrix and the
  full-NIK response variant remain Sprint 6b (F3). PII is masked through `CustomerSummary`.
- Ledger posting for activation/disbursement (story C1) and billing/recognition (C4) are unchanged
  and still pending: activation writes contract state + schedule only, as recorded in the sprint log.
