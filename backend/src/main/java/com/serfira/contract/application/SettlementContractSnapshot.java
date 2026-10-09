package com.serfira.contract.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A contract and its installments as the settlement quote prices them (E1, task T12, ADR-018).
 *
 * <p>Produced only for a contract that may be settled: {@link SettlementReceivablePort} throws
 * {@code ContractNotFoundException} (404) for an unknown id and {@code ContractStateException} (409)
 * unless the contract is ACTIVE with a schedule — settlement, like payment, only applies to a serviced
 * contract (ADR-018 D10).
 *
 * <p>{@code contractVersion} is the optimistic-lock {@code version} the quote snapshots
 * ({@code settlement_quote.contract_version}, DM §1.5): T13 execution rejects the quote
 * ({@code STALE_SETTLEMENT_QUOTE}) if the contract has moved on since this version (ADR-018 D9).
 *
 * @param contractId        contract the installments belong to
 * @param contractNo        business number, for messages and the quote (never PII)
 * @param contractVersion   the contract's current {@code @Version}, snapshotted into the quote
 * @param contractStartDate the effective start date; the ACT/30 window of period 1 begins here (ADR-018 D7)
 * @param installments      the full schedule in period order, future installments included
 */
public record SettlementContractSnapshot(UUID contractId, String contractNo, long contractVersion,
		LocalDate contractStartDate, List<SettlementInstallment> installments) {

	public SettlementContractSnapshot {
		installments = List.copyOf(installments);
	}
}
