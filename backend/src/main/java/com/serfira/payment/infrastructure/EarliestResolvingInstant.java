package com.serfira.payment.infrastructure;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The earliest {@code paid_at} among the POSTED payments that still resolve one installment (E4 void,
 * task T16, ADR-020 D1.3). After a voided payment flips to {@code VOIDED}, the recompute uses this to set
 * the installment's {@code paid_at} to the first <b>surviving</b> resolving payment, so voiding the
 * earliest payment no longer leaves the voided payment's instant behind.
 *
 * @param installmentId the installment the remaining POSTED payments resolve
 * @param paidAt        the earliest {@code paid_at} among those payments (never {@code null})
 */
public record EarliestResolvingInstant(UUID installmentId, OffsetDateTime paidAt) {
}
