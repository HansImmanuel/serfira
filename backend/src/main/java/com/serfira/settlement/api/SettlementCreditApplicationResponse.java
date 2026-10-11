package com.serfira.settlement.api;

import com.serfira.settlement.domain.SettlementCreditApplication;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One consumed-credit line of a settlement (E2, task T13, ADR-018 D5/D10): which {@code contract_credit}
 * source the settlement consumed, and how much.
 *
 * <p>Wire names are snake_case via the global Jackson configuration; the record fields stay camelCase.
 *
 * @param contractCreditId the consumed credit source
 * @param amount           the amount consumed from that source, scale-2
 */
public record SettlementCreditApplicationResponse(UUID contractCreditId, BigDecimal amount) {

	public static SettlementCreditApplicationResponse from(SettlementCreditApplication application) {
		return new SettlementCreditApplicationResponse(application.getContractCreditId(), application.getAmount());
	}
}
