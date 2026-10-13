package com.serfira.contract.infrastructure;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Σ customer credit applied to one installment through {@code contract_credit_application} (task T14),
 * with the earliest application instant, read by the payment void recompute (E4, task T16, ADR-020 D1.3)
 * so credit-funded resolution — amount and a coherent {@code paid_at} — survives a void of a separate
 * payment that happened to touch the same installment.
 *
 * @param installmentId    the installment the credit was applied to
 * @param applied          total of its surviving {@code contract_credit_application.amount} rows, scale-2
 * @param earliestAppliedAt earliest {@code applied_at} among those rows, a {@code paid_at} candidate when
 *                          no surviving POSTED payment resolves the installment (keeps
 *                          {@code ck_installment_paid_coherence} satisfied)
 */
public record CreditResolvedInstallmentTotal(UUID installmentId, BigDecimal applied,
		OffsetDateTime earliestAppliedAt) {
}
