package com.serfira.settlement.api;

import com.serfira.settlement.domain.Settlement;
import com.serfira.settlement.domain.SettlementAllocation;
import com.serfira.settlement.domain.SettlementCreditApplication;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/settlements} (E2, task T13, ADR-018 D4/D5). The executed settlement: the
 * cash/credit/rebate/admin components, the installment receivable resolutions, and the consumed credit
 * sources. The one balanced {@code SETTLEMENT} journal entry and the contract closure are side effects the
 * caller can observe through the ledger and contract reads; this body is the settlement record.
 *
 * <p>Wire names are snake_case via the global Jackson configuration; the record fields stay camelCase.
 *
 * @param settlementId      the new settlement id
 * @param settlementNo      business number ({@code SET-YYYYMM-XXXX})
 * @param contractId        the settled contract
 * @param quoteId           the executed quote
 * @param cashReceived      cash the customer paid ({@code gross_amount − credit_used})
 * @param creditUsed        AVAILABLE customer credit consumed (ADR-018 D10)
 * @param rebateAmount      the rebate on unearned future interest (never charged, carried for the record)
 * @param adminFee          the flat settlement admin fee
 * @param executedAt        business instant of execution (Asia/Jakarta)
 * @param allocations       the per-installment receivable resolutions (D5)
 * @param creditApplications the consumed credit sources (D5/D10)
 */
public record SettlementExecutionResponse(UUID settlementId, String settlementNo, UUID contractId, UUID quoteId,
		BigDecimal cashReceived, BigDecimal creditUsed, BigDecimal rebateAmount, BigDecimal adminFee,
		OffsetDateTime executedAt, List<SettlementAllocationResponse> allocations,
		List<SettlementCreditApplicationResponse> creditApplications) {

	public SettlementExecutionResponse {
		allocations = List.copyOf(allocations);
		creditApplications = List.copyOf(creditApplications);
	}

	public static SettlementExecutionResponse from(Settlement settlement, List<SettlementAllocation> allocations,
			List<SettlementCreditApplication> creditApplications) {
		return new SettlementExecutionResponse(settlement.getId(), settlement.getSettlementNo(),
				settlement.getContractId(), settlement.getQuoteId(), settlement.getCashReceived(),
				settlement.getCreditUsed(), settlement.getRebateAmount(), settlement.getAdminFee(),
				settlement.getExecutedAt(),
				allocations.stream().map(SettlementAllocationResponse::from).toList(),
				creditApplications.stream().map(SettlementCreditApplicationResponse::from).toList());
	}
}
