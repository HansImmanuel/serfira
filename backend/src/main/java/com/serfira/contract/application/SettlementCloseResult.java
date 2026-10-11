package com.serfira.contract.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The outcome of closing a contract by settlement (E2, task T13, ADR-018 D10): the amount actually written
 * to each installment's {@code settled_amount} by {@link SettlementClosePort#closeBySettlement}, so the
 * settlement module can cross-check it against the settlement allocations and the journal it posts.
 *
 * @param settledByInstallment receivable settled per installment id, exactly as written to the rows
 */
public record SettlementCloseResult(Map<UUID, BigDecimal> settledByInstallment) {

	public SettlementCloseResult {
		settledByInstallment = Map.copyOf(settledByInstallment);
	}
}
