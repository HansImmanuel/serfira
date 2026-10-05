package com.serfira.contract.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recorded {@code contract_credit_application}, for the history half of GET …/credit and the
 * response of a successful apply (task T14).
 *
 * @param applicationId the application row id
 * @param creditId      the credit it consumed
 * @param installmentId the installment whose recognized receivable it reduced
 * @param amount        amount applied; scale-2 money
 * @param appliedAt     business instant of the application (Asia/Jakarta)
 */
public record CreditApplicationHistoryItem(UUID applicationId, UUID creditId, UUID installmentId,
		BigDecimal amount, OffsetDateTime appliedAt) {
}
