package com.serfira.settlement.api;

import com.serfira.settlement.domain.SettlementAllocation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One receivable-resolution line of a settlement (E2, task T13, ADR-018 D5): which installment's
 * {@code PENALTY}/{@code INTEREST}/{@code PRINCIPAL} the settlement cleared, and how much. Income, admin
 * fee, rebate and credit consumption are not allocations and never appear here.
 *
 * <p>Wire names are snake_case via the global Jackson configuration; the record fields stay camelCase.
 *
 * @param installmentId  the installment whose recognized receivable was resolved
 * @param allocationType the resolved component ({@code PENALTY}/{@code INTEREST}/{@code PRINCIPAL})
 * @param amount         the resolved amount, scale-2
 */
public record SettlementAllocationResponse(UUID installmentId, String allocationType, BigDecimal amount) {

	public static SettlementAllocationResponse from(SettlementAllocation allocation) {
		return new SettlementAllocationResponse(allocation.getInstallmentId(),
				allocation.getAllocationType().name(), allocation.getAmount());
	}
}
