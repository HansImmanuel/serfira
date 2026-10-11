package com.serfira.settlement.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One receivable-resolution line a settlement produces (E2, task T13, ADR-018 D5): the amount of one
 * installment's {@code PENALTY}/{@code INTEREST}/{@code PRINCIPAL} the settlement clears. The pure
 * {@link SettlementResolutionEngine} emits these; the application layer turns each into an immutable
 * {@code settlement_allocation} row and a credit on the matching receivable account in the single
 * SETTLEMENT journal entry (ADR-018 D4).
 *
 * @param installmentId  the installment whose recognized receivable is resolved
 * @param type           the resolved component
 * @param amount         scale-2 money, {@code > 0}
 */
public record SettlementResolutionLine(UUID installmentId, SettlementAllocationType type, BigDecimal amount) {
}
