package com.serfira.penalty.api;

import com.serfira.penalty.domain.PenaltyAdjustmentType;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /api/v1/penalty-adjustments} (task T15, ADR-019 D2, Addendum §16.4).
 *
 * <p>{@code contractId} and {@code installmentId} together name the installment to adjust; the service
 * confirms the installment belongs to that contract (so no new {@code installment → contract} lookup edge
 * is invented). There is deliberately <b>no</b> actor/{@code approved_by} field: the approver is the
 * authenticated JWT {@code sub} (Addendum §3.3), never request-supplied identity.
 *
 * <p>Wire names are snake_case ({@code contract_id}, {@code installment_id}, {@code adjustment_type}) via the
 * global Jackson configuration; the fields stay camelCase. The Bean Validation annotations reject the
 * obviously malformed value at the boundary; the service re-validates positivity, scale and the effective-
 * penalty cap, so a bad value is always 400 {@code VALIDATION_ERROR} rather than a 500.
 */
public record PenaltyAdjustmentRequest(
		@NotNull UUID contractId,
		@NotNull UUID installmentId,
		@NotNull PenaltyAdjustmentType adjustmentType,
		@NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
		@NotBlank String reason) {
}
