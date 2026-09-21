package com.serfira.contract.api;

import com.serfira.contract.application.CustomerSummary;
import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;
import com.serfira.contract.domain.InterestScheme;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Contract representation (PRD C-4).
 *
 * <p>The customer is projected through {@link CustomerSummary}, so PII crosses the API boundary
 * masked only (ADR-004 / review H-3).
 *
 * @param outstanding recognized receivable still owed (PRD §5A: principal residual + recognized
 *                    interest residual + effective penalty residual). {@code null} while the
 *                    contract is DRAFT, because a draft has no schedule and therefore no receivable —
 *                    the frontend renders "–".
 */
public record ContractResponse(
		UUID id,
		String contractNo,
		ContractStatus status,
		CustomerSummary customer,
		AssetResponse asset,
		BigDecimal assetPrice,
		BigDecimal principal,
		BigDecimal downPayment,
		int tenorMonths,
		InterestScheme interestScheme,
		BigDecimal interestRate,
		int gracePeriodDays,
		BigDecimal penaltyRateDaily,
		LocalDate plannedStartDate,
		LocalDate startDate,
		OffsetDateTime closedAt,
		ClosedReason closedReason,
		BigDecimal outstanding,
		long version,
		OffsetDateTime createdAt) {

	public static ContractResponse from(Contract contract, BigDecimal outstanding) {
		return new ContractResponse(
				contract.getId(),
				contract.getContractNo(),
				contract.getStatus(),
				CustomerSummary.from(contract.getCustomer()),
				AssetResponse.from(contract.getAsset()),
				contract.getAssetPrice(),
				contract.getPrincipal(),
				contract.getDownPayment(),
				contract.getTenorMonths(),
				contract.getInterestScheme(),
				contract.getInterestRate(),
				contract.getGracePeriodDays(),
				contract.getPenaltyRateDaily(),
				contract.getPlannedStartDate(),
				contract.getStartDate(),
				contract.getClosedAt(),
				contract.getClosedReason(),
				outstanding,
				contract.getVersion(),
				contract.getCreatedAt());
	}
}