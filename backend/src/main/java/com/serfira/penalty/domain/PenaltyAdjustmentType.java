package com.serfira.penalty.domain;

/**
 * Kind of a {@link PenaltyAdjustment} (03_DOMAIN_MODEL.md §1.10, 04_GAPS_ADDENDUM.md §16.4): a full
 * {@code WAIVE} or a partial {@code REDUCE} of recognized penalty.
 *
 * <p>Both lower the effective penalty by the row's {@code amount}; the distinction is intent/audit only
 * (why the receivable was written down), not a different calculation. Mirrors the V1
 * {@code ck_penalty_adjustment_type} CHECK ({@code adjustment_type IN ('WAIVE','REDUCE')}).
 */
public enum PenaltyAdjustmentType {

	/** The whole remaining effective penalty of the installment is written off. */
	WAIVE,

	/** Part of the remaining effective penalty of the installment is written off. */
	REDUCE
}
