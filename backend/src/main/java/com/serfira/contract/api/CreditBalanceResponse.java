package com.serfira.contract.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response of {@code GET /api/v1/contracts/{id}/credit} (task T14, Addendum §2.5): the contract's credit
 * standing plus its application history.
 *
 * @param totalCredit      Σ of every {@code contract_credit.amount} booked for the contract
 * @param availableBalance Σ available balance over AVAILABLE credits ({@code amount − Σ applications})
 * @param applications     every application made, oldest first
 */
public record CreditBalanceResponse(BigDecimal totalCredit, BigDecimal availableBalance,
		List<CreditApplicationHistoryItem> applications) {

	public CreditBalanceResponse {
		applications = List.copyOf(applications);
	}
}
