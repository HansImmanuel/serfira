package com.serfira.contract.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of {@code POST /api/v1/contracts/{id}/credit/apply} (task T14): what this call applied and the
 * contract's available balance afterwards.
 *
 * @param appliedAmount    total credit applied by this call; scale-2 money
 * @param availableBalance available credit balance remaining after this call
 * @param applications     the {@code contract_credit_application} rows this call created
 */
public record CreditApplicationResponse(BigDecimal appliedAmount, BigDecimal availableBalance,
		List<CreditApplicationHistoryItem> applications) {

	public CreditApplicationResponse {
		applications = List.copyOf(applications);
	}
}
