package com.serfira.contract.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Instruction to close a contract by early settlement (E2, task T13, ADR-018 D10) — the input to
 * {@link SettlementClosePort#closeBySettlement}.
 *
 * <p>The settlement module computes the receivable it cleared per installment (the sum of that
 * installment's PENALTY/INTEREST/PRINCIPAL settlement allocations) and passes it here as
 * {@code settledByInstallment}; the {@code contract} module writes those amounts onto its own
 * {@code installment} rows. {@code expectedContractVersion} is the version the quote was priced against:
 * the close rejects (stale quote) if the live contract has moved on, so the optimistic lock and the quote
 * snapshot agree before any money moves.
 *
 * @param contractId              the contract to close; must be ACTIVE
 * @param expectedContractVersion the contract version the quote snapshotted (ADR-018 D9)
 * @param settledByInstallment    receivable cleared per installment id, each {@code >= 0} scale-2 money
 * @param executedAt              business instant of the settlement, used for {@code settled_at} / closure
 */
public record SettlementCloseCommand(UUID contractId, long expectedContractVersion,
		Map<UUID, BigDecimal> settledByInstallment, OffsetDateTime executedAt) {

	public SettlementCloseCommand {
		settledByInstallment = Map.copyOf(settledByInstallment);
	}
}
