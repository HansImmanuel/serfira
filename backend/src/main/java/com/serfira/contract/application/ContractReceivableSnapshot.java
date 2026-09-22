package com.serfira.contract.application;

import com.serfira.contract.domain.ContractStatus;

import java.util.List;
import java.util.UUID;

/**
 * A contract and its installment receivables, as seen by a caller that needs to resolve money against
 * them (payment C3, settlement E2).
 *
 * <p>Produced only for a contract that may receive money: {@link InstallmentReceivablePort} throws
 * {@code ContractNotFoundException} (404) for an unknown id and {@code ContractStateException} (409)
 * unless the contract is ACTIVE with a schedule.
 *
 * @param contractId   contract the receivables belong to
 * @param contractNo   business number, for messages and journal descriptions (never PII)
 * @param status       always {@link ContractStatus#ACTIVE} — carried so the caller can record and
 *                     re-assert the state it resolved money against
 * @param installments the full schedule in period order, future installments included: which of them
 *                     are eligible is the allocation engine's decision (ADR-009)
 */
public record ContractReceivableSnapshot(UUID contractId, String contractNo, ContractStatus status,
		List<InstallmentReceivable> installments) {
}
