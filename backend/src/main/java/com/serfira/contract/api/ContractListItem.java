package com.serfira.contract.api;

import com.serfira.contract.application.CustomerSummary;
import com.serfira.contract.domain.Contract;
import com.serfira.contract.domain.ContractStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Contract list row (PRD C-4, FE §2.2 table columns). Deliberately smaller than
 * {@link ContractResponse}: the list needs identity, the masked customer, the asset, money terms,
 * status and outstanding.
 *
 * @param outstanding as in {@link ContractResponse}: {@code null} for DRAFT rows (no schedule yet)
 */
public record ContractListItem(
		UUID id,
		String contractNo,
		CustomerSummary customer,
		AssetResponse asset,
		BigDecimal principal,
		int tenorMonths,
		ContractStatus status,
		BigDecimal outstanding,
		LocalDate plannedStartDate,
		LocalDate startDate,
		OffsetDateTime createdAt) {

	public static ContractListItem from(Contract contract, BigDecimal outstanding) {
		return new ContractListItem(
				contract.getId(),
				contract.getContractNo(),
				CustomerSummary.from(contract.getCustomer()),
				AssetResponse.from(contract.getAsset()),
				contract.getPrincipal(),
				contract.getTenorMonths(),
				contract.getStatus(),
				outstanding,
				contract.getPlannedStartDate(),
				contract.getStartDate(),
				contract.getCreatedAt());
	}
}