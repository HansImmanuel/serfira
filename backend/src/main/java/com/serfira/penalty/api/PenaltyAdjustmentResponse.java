package com.serfira.penalty.api;

import com.serfira.penalty.application.PenaltyAdjustmentResult;
import com.serfira.penalty.domain.PenaltyAdjustmentType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/penalty-adjustments} (task T15): the recorded adjustment plus the
 * installment's effective penalty after it, so the caller can confirm the write-down without a second read.
 *
 * <p>Mapped explicitly from {@link PenaltyAdjustmentResult} — the JPA entity is never exposed. Wire names
 * are snake_case via the global Jackson configuration.
 *
 * @param id             the recorded adjustment id
 * @param installmentId  the adjusted installment
 * @param adjustmentType WAIVE or REDUCE
 * @param amount         the write-down, scale-2 money
 * @param reason         the audit justification
 * @param approvedBy     the authenticated actor who approved it
 * @param createdAt      when the row was recorded
 * @param effectivePenalty the installment's effective penalty after this adjustment, {@code >= 0}
 */
public record PenaltyAdjustmentResponse(UUID id, UUID installmentId, PenaltyAdjustmentType adjustmentType,
		BigDecimal amount, String reason, UUID approvedBy, OffsetDateTime createdAt, BigDecimal effectivePenalty) {

	public static PenaltyAdjustmentResponse from(PenaltyAdjustmentResult result) {
		return new PenaltyAdjustmentResponse(result.id(), result.installmentId(), result.adjustmentType(),
				result.amount(), result.reason(), result.approvedBy(), result.createdAt(), result.effective());
	}
}
