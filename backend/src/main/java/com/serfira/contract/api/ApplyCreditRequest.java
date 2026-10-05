package com.serfira.contract.api;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Body of {@code POST /api/v1/contracts/{id}/credit/apply} (task T14, Addendum §2.2/§2.5).
 *
 * <p>{@code amount} is optional. When present it is the amount of available credit to apply (positive,
 * scale-2 money, at most the available balance) — this is how a caller does a partial application
 * (Addendum §2.2). When omitted/null the endpoint applies the full available balance. The wire name is
 * snake_case (just {@code amount}) via the global Jackson configuration; the field stays camelCase.
 *
 * <p>The annotations reject the obviously malformed value at the boundary; the service re-validates
 * positivity, scale and the balance cap, so a bad value is always 400 {@code VALIDATION_ERROR} rather
 * than a 500 out of the engine (same contract as {@code PaymentRequest}).
 */
public record ApplyCreditRequest(
		@Positive @Digits(integer = 17, fraction = 2) BigDecimal amount) {
}
