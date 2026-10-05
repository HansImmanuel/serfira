package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The arguments for booking a durable credit from a payment's EXCESS allocation
 * (04_GAPS_ADDENDUM.md §2.1, task T14).
 *
 * <p>All three are plain values the {@code payment} module already holds: the contract the excess
 * belongs to, the persisted EXCESS {@code payment_allocation} row id (the uniqueness key that stops a
 * retried payment double-booking the same excess), and the excess amount.
 *
 * @param contractId                contract the credit belongs to
 * @param sourcePaymentAllocationId the EXCESS {@code payment_allocation} id this credit originates from
 * @param amount                    the excess; scale-2 money, {@code > 0}
 */
public record RecordExcessCreditCommand(UUID contractId, UUID sourcePaymentAllocationId, BigDecimal amount) {
}
