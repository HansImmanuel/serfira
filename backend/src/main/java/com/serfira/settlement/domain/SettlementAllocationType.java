package com.serfira.settlement.domain;

/**
 * Receivable component a {@link SettlementAllocation} resolved (E2, task T13, ADR-018 D5): the waterfall
 * components {@code PENALTY → INTEREST → PRINCIPAL}, mirroring the {@code ck_settlement_allocation_type}
 * CHECK (V1).
 *
 * <p>No {@code EXCESS}: unlike a payment, a settlement never overpays into customer credit — future
 * interest income, the admin fee, the rebate and consumed credit are journal lines and settlement columns,
 * never per-installment receivable resolutions (ADR-018 D5). A settlement-owned enum (not a reuse of
 * {@code payment.domain.AllocationType}) keeps the module boundary intact (02_TECH_SPEC.md §1).
 */
public enum SettlementAllocationType {

	PENALTY,
	INTEREST,
	PRINCIPAL
}
