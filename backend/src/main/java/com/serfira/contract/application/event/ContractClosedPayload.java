package com.serfira.contract.application.event;

import com.serfira.contract.domain.ClosedReason;
import com.serfira.contract.domain.Contract;
import com.serfira.shared.outbox.OutboxPayload;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ContractClosed} (ADR-027): {@code Contract.close(...)} performed the transition — MATURITY (payment,
 * credit application or a penalty waiver that cleared the last amount) or SETTLEMENT. Built after the close.
 */
public record ContractClosedPayload(int schemaVersion, UUID contractId, String contractNo, ClosedReason closedReason,
		OffsetDateTime closedAt) implements OutboxPayload {

	public static ContractClosedPayload of(Contract contract) {
		return new ContractClosedPayload(SCHEMA_VERSION, contract.getId(), contract.getContractNo(),
				contract.getClosedReason(), contract.getClosedAt());
	}
}
