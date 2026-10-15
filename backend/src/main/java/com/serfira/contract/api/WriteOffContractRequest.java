package com.serfira.contract.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/contracts/{id}/write-off} (F5, task T19; DM §1.3/§1.12, Addendum §13).
 *
 * <p>{@code reason} is the external decision recorded on the contract ({@code write_off_reason}) — e.g.
 * the collection decision reference. It is required and non-blank: a write-off must always carry why it
 * was done (V4 {@code ck_contract_terminated_coherence}). The recording actor is <b>not</b> taken from the
 * body; it comes from the authenticated JWT {@code sub} via {@code AuditContext} (60-security). The wire
 * name is snake_case ({@code reason}) via the global Jackson configuration; the field stays camelCase.
 *
 * <p>The {@code Idempotency-Key} header (not part of this body) makes the write-off retry-safe, exactly
 * like {@code POST /payments} and {@code POST /settlements} (ADR-017).
 */
public record WriteOffContractRequest(
		@NotBlank @Size(max = 500) String reason) {
}
