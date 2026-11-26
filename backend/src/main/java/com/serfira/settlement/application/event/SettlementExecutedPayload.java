package com.serfira.settlement.application.event;

import com.serfira.settlement.domain.Settlement;
import com.serfira.shared.outbox.OutboxPayload;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code SettlementExecuted} (ADR-027): a quote was executed (ADR-018). Always followed, in the same
 * transaction, by the contract's {@code ContractClosed} with reason SETTLEMENT.
 */
public record SettlementExecutedPayload(int schemaVersion, UUID settlementId, String settlementNo, UUID contractId,
		UUID quoteId, BigDecimal cashReceived, BigDecimal creditUsed, BigDecimal rebateAmount, BigDecimal adminFee,
		OffsetDateTime executedAt) implements OutboxPayload {

	public static SettlementExecutedPayload of(Settlement settlement) {
		return new SettlementExecutedPayload(SCHEMA_VERSION, settlement.getId(), settlement.getSettlementNo(),
				settlement.getContractId(), settlement.getQuoteId(), settlement.getCashReceived(),
				settlement.getCreditUsed(), settlement.getRebateAmount(), settlement.getAdminFee(),
				settlement.getExecutedAt());
	}
}
