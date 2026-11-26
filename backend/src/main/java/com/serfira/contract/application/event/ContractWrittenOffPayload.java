package com.serfira.contract.application.event;

import com.serfira.shared.outbox.OutboxPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code ContractWrittenOff} (ADR-027): an ACTIVE contract was terminated by write-off (ADR-018). The write-off
 * reason is free text and is deliberately absent.
 */
public record ContractWrittenOffPayload(int schemaVersion, UUID contractId, String contractNo,
		OffsetDateTime writtenOffAt, BigDecimal totalWrittenOff, int installmentCount) implements OutboxPayload {

	public static ContractWrittenOffPayload of(UUID contractId, String contractNo, OffsetDateTime writtenOffAt,
			BigDecimal totalWrittenOff, int installmentCount) {
		return new ContractWrittenOffPayload(SCHEMA_VERSION, contractId, contractNo, writtenOffAt, totalWrittenOff,
				installmentCount);
	}
}
