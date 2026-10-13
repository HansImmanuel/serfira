package com.serfira.payment.api;

import jakarta.validation.constraints.Size;

/**
 * The optional body of {@code POST /api/v1/payments/{id}/void} (E4, task T16): a free-text reason the
 * operator records for the audit trail. The payment id is a path variable, not a body field, and the
 * voiding actor is the JWT subject (never the body) — the request carries only the reason.
 *
 * <p>The whole body is optional (a bare void with no reason is allowed); {@code reason} is bounded so a
 * caller cannot store an unbounded blob. Never put PII in the reason.
 *
 * @param reason why the payment is being voided; may be {@code null}/blank
 */
public record VoidPaymentRequest(@Size(max = 500, message = "reason must be at most 500 characters") String reason) {
}
