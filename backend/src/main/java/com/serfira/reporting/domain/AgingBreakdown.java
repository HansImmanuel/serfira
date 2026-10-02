package com.serfira.reporting.domain;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Outstanding summed per {@link AgingBucket} (ADR-013 A-7). A breakdown is built by adding one owed
 * installment at a time ({@link #add}); the running {@link #total()} always equals the sum of the five
 * buckets, so the report's Σ-bucket = outstanding invariant holds by construction at every level.
 *
 * <p>Pure and mutable only through {@link #add}; the API layer reads the five {@code *Amount()} accessors.
 * All amounts are scale-2 money and are never rounded here — the inputs are already scale-2 installment
 * outstanding values.
 */
public final class AgingBreakdown {

	private final Map<AgingBucket, BigDecimal> amounts = new EnumMap<>(AgingBucket.class);

	public AgingBreakdown() {
		for (AgingBucket bucket : AgingBucket.values()) {
			amounts.put(bucket, BigDecimal.ZERO);
		}
	}

	/**
	 * Adds one owed installment's outstanding to the bucket its days-past-due selects.
	 *
	 * @param daysPastDue aging DPD, {@code >= 0}
	 * @param outstanding installment outstanding, scale-2, {@code > 0}
	 */
	public void add(int daysPastDue, BigDecimal outstanding) {
		Objects.requireNonNull(outstanding, "outstanding");
		if (outstanding.signum() <= 0) {
			throw new IllegalArgumentException("outstanding must be > 0 but was " + outstanding);
		}
		AgingBucket bucket = AgingBucket.of(daysPastDue);
		amounts.merge(bucket, outstanding, BigDecimal::add);
	}

	/** Folds another breakdown into this one, bucket by bucket (used to aggregate the portfolio total). */
	public void addAll(AgingBreakdown other) {
		Objects.requireNonNull(other, "other");
		for (AgingBucket bucket : AgingBucket.values()) {
			amounts.merge(bucket, other.amounts.get(bucket), BigDecimal::add);
		}
	}

	public BigDecimal amount(AgingBucket bucket) {
		return amounts.get(Objects.requireNonNull(bucket, "bucket"));
	}

	/** Σ of the five buckets — by construction equal to the outstanding fed in through {@link #add}. */
	public BigDecimal total() {
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal amount : amounts.values()) {
			total = total.add(amount);
		}
		return total;
	}
}
