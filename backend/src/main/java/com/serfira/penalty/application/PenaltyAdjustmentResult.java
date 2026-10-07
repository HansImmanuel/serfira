package com.serfira.penalty.application;

import com.serfira.penalty.domain.PenaltyAdjustmentType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The outcome of appending one {@link com.serfira.penalty.domain.PenaltyAdjustment} (ADR-019 D2): the
 * recorded row plus the installment's effective penalty after the adjustment, for the API to map into its
 * response. Never exposes the JPA entity across the application boundary.
 *
 * @param id             the recorded adjustment id
 * @param installmentId  the adjusted installment
 * @param adjustmentType WAIVE or REDUCE
 * @param amount         the write-down, scale-2 money
 * @param reason         the audit justification
 * @param approvedBy     the authenticated actor who approved it
 * @param createdAt      when the row was recorded (audit column)
 * @param effective      the installment's effective penalty after this adjustment, {@code >= 0}
 */
public record PenaltyAdjustmentResult(UUID id, UUID installmentId, PenaltyAdjustmentType adjustmentType,
		BigDecimal amount, String reason, UUID approvedBy, OffsetDateTime createdAt, BigDecimal effective) {
}
