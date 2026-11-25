# ADR-024: Exact Decimal Wire Format for Money and Rates (T32 review)

- **Status:** Accepted
- **Date:** 2026-11-24
- **Deciders:** Hans (solo developer)
- **Relates to:** ADR-016 (decimal magnitude bounds), ADR-023 (browser client boundary),
  `02_TECH_SPEC.md` §2.2, `06_FRONTEND_SPEC.md` §4, steering `20-financial-domain.md`.

---

## Context

The API has always stored money as `NUMERIC(19,2)` and rates as `NUMERIC(7,4)` (ADR-016) and has always
serialized them as JSON numbers. That was harmless while every caller was a server-side client with a decimal
type. Sprint 7 adds a JavaScript browser client (ADR-023), and JavaScript has no decimal type: `JSON.parse`
turns a number into an IEEE-754 double, which carries ~15–16 significant digits.

`NUMERIC(19,2)` allows **17 integer digits** (`99999999999999999.99`). A contract at that magnitude therefore
reaches the browser with its last digits rounded — silently, in the one direction a financial system must never
round: storage and display disagree. The Qodo review of PR #23 flagged this as the "money formatting loses
precision" finding, but the loss happens at `JSON.parse`, before any formatting helper runs, so it cannot be
fixed in the frontend alone.

Two related frontend defects share the same root cause: the display helper parsed the incoming number with
`Number`, and the create form accepted money input beyond the column domain, deferring the rejection to a
database error.

---

## Decision

### D1 — Every `BigDecimal` is a JSON string on the wire

`shared.money.BigDecimalStringModule` (a Jackson 3 `JacksonModule`, registered as a bean and picked up by
Boot's mapper customizer) serializes every `BigDecimal` with `toPlainString()`. Money and rates therefore
travel as the exact decimal text the database holds:

```json
{ "asset_price": "20000000.00", "interest_rate": "0.0150" }
```

`toPlainString()` is deliberate: it never emits scientific notation, and it preserves the stored scale, so
`0.0150` does not degrade to `0.015`.

Integer counters (`tenor_months`, `grace_period_days`, `version`, page metadata) are **not** `BigDecimal` and
stay JSON numbers. The change is scoped to the decimal type, not to "all numbers".

### D2 — Deserialization keeps accepting numbers and strings

Only the serializer is registered. Request bodies that already send `"amount": 20000000.00` keep working, and
a string form is accepted too. No client is forced to migrate, and the idempotency canonicalization path
(which hashes the parsed body) is unaffected because it works on values, not on their wire encoding.

### D3 — The OpenAPI document describes the actual format

`shared.api.OpenApiConfiguration` registers a `ModelConverter` that runs before swagger-core's resolver and
declares `BigDecimal` as `type: string`, `format: decimal`. Without it the document would say `number` and a
generated client would reintroduce the double. springdoc collects `List<ModelConverter>` beans and prepends
them, so the custom converter wins for this type without disabling the built-in ones.

### D4 — The frontend treats money as an exact string end to end

- `types/contract.ts` declares `Money = string`; every amount, rate and derived total in the response types is
  a string.
- `lib/decimal.ts` validates, compares, adds, subtracts and converts rates with BigInt string arithmetic.
- `lib/format.ts` parses the string into a `BigInt` of whole rupiah, rounds half away from zero, and formats
  with `Intl.NumberFormat` (`id-ID`, zero fraction digits). No value passes through `Number`.
- A malformed or missing amount renders as the documented `–` instead of `NaN`.

### D5 — Operator input is bounded to the column domain at the boundary

`normalizeMoney` rejects anything at or beyond `10^19` scale-2 units (17 integer digits, scale 2), matching
`DecimalBounds`. The create-contract schema reports it as a field error, so an out-of-domain amount is a
form error rather than a 400/500 after submit. Positivity and `down_payment < asset_price` remain as they were.

---

## Alternatives Considered

- **Fix it in the frontend formatter only.** Rejected: `JSON.parse` has already rounded the value by the time
  any formatter sees it. `9007199254740993` arrives as `9007199254740992`; no client-side code can recover the
  lost digit.
- **Serialize money as a JSON object (`{ "amount": "20000000.00", "currency": "IDR" }`).** Rejected: it changes
  every response shape and every consumer for a currency that is fixed by the domain. A string is the smallest
  change that is exact.
- **Send integer minor units (cents).** Rejected: it renames and rescales every field, breaks the documented
  contract (`TS §2.2`, DM examples) and forces every reader to know the scale. The decimal string is the value
  the domain already uses.
- **Enable `WRITE_BIGDECIMAL_AS_PLAIN` globally.** Rejected: that is a Jackson 2 feature and, more
  importantly, only suppresses exponent notation — the value is still a JSON number, so the browser still
  parses it as a double.
- **Annotate each DTO field with `@JsonSerialize(using = ...)`.** Rejected: it is repetitive, easy to miss on a
  new record, and the OpenAPI converter would still need the same global treatment.
- **Change the column to `NUMERIC(15,2)` to fit a double.** Rejected: it shrinks the supported domain to work
  around a client limitation, and a double cannot exactly represent most 15-digit decimals either.

---

## Consequences

### Positive

- Money and rates reach the browser with every stored digit; the display and the database agree.
- One serializer and one OpenAPI converter cover present and future `BigDecimal` fields.
- The frontend has no floating-point money path at all, which matches the steering rule literally rather than
  by convention.
- Tests pin the format (`BigDecimalStringModuleTest`, `OpenApiSmokeIT`, the raw-body assertions in
  `ContractApiIT` / `PaymentApiIT`).

### Negative

- The wire format for decimals changed from number to string. Server-side clients that deserialize into
  `double` would break; none exist in this repository, and any future one should use `BigDecimal`.
- Generated clients see `string` and must parse a decimal type. That is the intended outcome.
- Every raw-JSON assertion in the integration tests had to read `.asText()` and parse with
  `BigDecimal`, because `decimalValue()` requires a numeric node.

### Risks

- A future DTO that needs a JSON number for a `BigDecimal` (for example an external feed) would now be a string.
  Mitigation: such a field is a deliberate exception and would carry a field-level serializer plus an ADR note;
  none is known.
- A future developer may "simplify" the frontend formatter back to `Number(value)`. Mitigation:
  `format.test.ts` includes values beyond `Number.MAX_SAFE_INTEGER` and fails if the digits are lost.

---

## References

- `docs/02_TECH_SPEC.md` §2.2; `docs/06_FRONTEND_SPEC.md` §4; `docs/adr/ADR-016`.
- Implementation: `BigDecimalStringModule`, `JacksonMoneyConfiguration`, `OpenApiConfiguration`,
  `frontend/lib/decimal.ts`, `frontend/lib/format.ts`, `frontend/lib/schemas/createContract.ts`.
