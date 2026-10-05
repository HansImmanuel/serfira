package com.serfira.contract.domain.credit;

/**
 * The receivable component a credit application resolves, in waterfall order (ADR-009:
 * denda → bunga → pokok).
 *
 * <p>Deliberately {@code contract}-local rather than reusing {@code payment}'s {@code AllocationType}:
 * {@code contract} must not import {@code com.serfira.payment.*} (02_TECH_SPEC.md §1). There is no
 * EXCESS member — credit that cannot be applied to a recognized receivable is simply left on the credit
 * balance, never turned into a new excess (Addendum §2.4).
 */
public enum CreditComponent {

	/** Effective penalty component (gross recognized penalty net of adjustments). */
	PENALTY,

	/** Recognized/billed interest component (never future scheduled interest). */
	INTEREST,

	/** Principal component. */
	PRINCIPAL
}
