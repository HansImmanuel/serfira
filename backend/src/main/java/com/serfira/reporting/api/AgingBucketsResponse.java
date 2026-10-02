package com.serfira.reporting.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.serfira.reporting.domain.AgingBreakdown;
import com.serfira.reporting.domain.AgingBucket;

import java.math.BigDecimal;

/**
 * The five delinquency buckets and their total, as the aging report returns them (06_FRONTEND_SPEC.md §2.9).
 *
 * <p>The wire names are the bucket labels from the spec ({@code current}, {@code dpd_1_30}, {@code dpd_31_60},
 * {@code dpd_61_90}, {@code dpd_over_90}, {@code total_outstanding}). They are pinned with
 * {@code @JsonProperty} because the global snake_case strategy cannot derive numeric band names like
 * {@code dpd_1_30} from a camelCase field.
 *
 * <p>{@code totalOutstanding} always equals the sum of the five buckets (ADR-013 A-7).
 */
public record AgingBucketsResponse(
		@JsonProperty("current") BigDecimal current,
		@JsonProperty("dpd_1_30") BigDecimal dpd1To30,
		@JsonProperty("dpd_31_60") BigDecimal dpd31To60,
		@JsonProperty("dpd_61_90") BigDecimal dpd61To90,
		@JsonProperty("dpd_over_90") BigDecimal dpdOver90,
		@JsonProperty("total_outstanding") BigDecimal totalOutstanding) {

	public static AgingBucketsResponse from(AgingBreakdown breakdown) {
		return new AgingBucketsResponse(
				breakdown.amount(AgingBucket.CURRENT),
				breakdown.amount(AgingBucket.DPD_1_30),
				breakdown.amount(AgingBucket.DPD_31_60),
				breakdown.amount(AgingBucket.DPD_61_90),
				breakdown.amount(AgingBucket.DPD_OVER_90),
				breakdown.total());
	}
}
