package com.serfira.payment.api;

import com.serfira.payment.domain.PaymentChannel;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Receive a payment (PRD P-1, DM §1.7). Requires an {@code Idempotency-Key} header (TS §2.2/§2.5)
 * because a double-submitted payment must not be received twice.
 *
 * <p>There is deliberately <b>no</b> {@code paid_at} field: the business instant of a payment comes
 * from the server clock (TS §2.0), so a client cannot backdate or future-date money. Where the money
 * goes is not part of the request either — allocation is the server's job (PRD P-2/P-3/P-4).
 *
 * <p>{@code amount} must be money at scale ≤ 2 and {@code > 0}; a value finer than scale 2 is rejected
 * as 400 {@code VALIDATION_ERROR} instead of being rounded (TS §2.1).
 *
 * @param contractId contract the money is received for; must be ACTIVE (409 otherwise)
 * @param amount     cash received, scale-2 money {@code > 0}
 * @param channel    how the money arrived (manual collection channel)
 */
public record PaymentRequest(
		@NotNull UUID contractId,
		@NotNull BigDecimal amount,
		@NotNull PaymentChannel channel) {
}
