package com.serfira.reporting.domain;

/**
 * Delinquency buckets of the aging report (PRD glossary "Aging", 06_FRONTEND_SPEC.md §2.9, ADR-013 A-7).
 * An installment lands in exactly one bucket by its days-past-due on the report's business date; the bucket
 * carries that installment's outstanding.
 *
 * <pre>
 * CURRENT     DPD = 0   (not yet due, or still within the grace window)
 * DPD_1_30    1  .. 30
 * DPD_31_60   31 .. 60
 * DPD_61_90   61 .. 90
 * DPD_OVER_90 > 90
 * </pre>
 *
 * <p>The boundaries are inclusive on both ends of each closed band, matching the DPD-start definition of
 * ADR-013 A-4 (DPD becomes 1 on {@code due_date + grace + 1}). Buckets are summed per contract and across
 * the portfolio; at every level {@code Σ bucket = outstanding}, because each owed installment contributes
 * its whole outstanding to one bucket.
 */
public enum AgingBucket {

	CURRENT,
	DPD_1_30,
	DPD_31_60,
	DPD_61_90,
	DPD_OVER_90;

	/**
	 * The bucket an installment belongs to for a given days-past-due.
	 *
	 * @param daysPastDue aging DPD, {@code >= 0}
	 * @throws IllegalArgumentException if {@code daysPastDue} is negative
	 */
	public static AgingBucket of(int daysPastDue) {
		if (daysPastDue < 0) {
			throw new IllegalArgumentException("daysPastDue must be >= 0 but was " + daysPastDue);
		}
		if (daysPastDue == 0) {
			return CURRENT;
		}
		if (daysPastDue <= 30) {
			return DPD_1_30;
		}
		if (daysPastDue <= 60) {
			return DPD_31_60;
		}
		if (daysPastDue <= 90) {
			return DPD_61_90;
		}
		return DPD_OVER_90;
	}
}
